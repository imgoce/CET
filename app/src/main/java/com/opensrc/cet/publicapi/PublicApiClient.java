package com.opensrc.cet.publicapi;

import android.os.Handler;
import android.os.Looper;

import com.opensrc.cet.data.Measurement;
import com.opensrc.cet.net.HttpTransport;
import com.opensrc.cet.net.HttpUrlConnectionTransport;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 수업 공용 서버 클라이언트 (③ 곽예찬, 계약서 §03·§05).
 *
 * <ul>
 *   <li>API 1 {@link #fetchMap}: GET /sensor/api/map — 지도 최신값 (SM-1)</li>
 *   <li>API 2 {@link #upload}: GET /sensor/sensing/?… — 수집 업로드 (DC-5)</li>
 * </ul>
 * 재시도(5초 간격 3회)와 대기열 보관은 결과의 status 를 보고 앱(①)이 정한다. 이 클래스는 1번만 보낸다.
 * 31·32(수리 전)와 테스트 모드는 공용 업로드를 하지 않는다 (호출하지 않는다).
 */
public final class PublicApiClient {

    public static final String DEFAULT_BASE_URL = "http://203.255.81.72:10021";

    /** 결과를 받는 콜백. 메인 스레드에서 호출된다. */
    public interface Callback<T> {
        void onResult(T result);
    }

    private static final Pattern SENSOR_NAME = Pattern.compile("^sensor\\s*(\\d{1,2})$");
    private static final DateTimeFormatter MAP_TIME =
            DateTimeFormatter.ofPattern("MMM d HH:mm:ss yyyy", Locale.ENGLISH);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final String baseUrl;
    private final HttpTransport transport;
    private final Executor networkExecutor;
    private final Executor callbackExecutor;

    public PublicApiClient() {
        this(DEFAULT_BASE_URL, new HttpUrlConnectionTransport(), Executors.newFixedThreadPool(2),
                new MainThreadExecutor());
    }

    /** 테스트용: 주소·통로·스레드를 바꿔 끼울 수 있다 */
    PublicApiClient(String baseUrl, HttpTransport transport, Executor networkExecutor, Executor callbackExecutor) {
        String base = Objects.requireNonNull(baseUrl, "baseUrl");
        this.baseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.transport = Objects.requireNonNull(transport, "transport");
        this.networkExecutor = Objects.requireNonNull(networkExecutor, "networkExecutor");
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
    }

    // ── API 1: 지도 ──────────────────────────────────────────────

    public void fetchMap(Callback<MapResult> callback) {
        Objects.requireNonNull(callback, "callback");
        networkExecutor.execute(() -> {
            MapResult result = fetchMapSync();
            callbackExecutor.execute(() -> callback.onResult(result));
        });
    }

    MapResult fetchMapSync() {
        HttpTransport.Response response;
        try {
            response = transport.send("GET", baseUrl + "/sensor/api/map",
                    Collections.singletonMap("Accept", "application/json"), null);
        } catch (IOException e) {
            return MapResult.failure("공용 서버에 연결할 수 없습니다: " + e.getMessage());
        }
        if (response.status != 200) {
            return MapResult.failure("공용 서버 오류 (" + response.status + ")");
        }
        try {
            JSONArray arr = new JSONArray(response.body);
            List<PublicMapRow> rows = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                rows.add(mapRow(arr.getJSONObject(i)));
            }
            return MapResult.success(rows);
        } catch (JSONException e) {
            return MapResult.failure("공용 서버 응답을 해석할 수 없습니다: " + e.getMessage());
        }
    }

    private static PublicMapRow mapRow(JSONObject o) throws JSONException {
        String name = o.getString("sensor");
        String time = o.isNull("time") ? null : o.getString("time");
        return new PublicMapRow(
                name,
                sensorNo(name),
                o.getDouble("latitude"),
                o.getDouble("longitude"),
                o.isNull("temperature") ? null : o.getDouble("temperature"),
                o.isNull("co2") ? null : o.getInt("co2"),
                time,
                time == null ? null : parseMapTime(time),
                o.optBoolean("fresh", false),
                o.optString("mode", ""));
    }

    /** "sensor 07" → 7, "sensor 34" → 34, 그 밖은 null */
    static Integer sensorNo(String name) {
        Matcher m = SENSOR_NAME.matcher(name.trim());
        if (!m.matches()) return null;
        int n = Integer.parseInt(m.group(1));
        return n >= 1 && n <= 34 ? n : null;
    }

    /** "Tue Nov 10 07:09:55 2026" (KST) → Instant. 요일은 무시한다. 해석 실패 시 null */
    static Instant parseMapTime(String text) {
        String[] parts = text.trim().split("\\s+");
        if (parts.length != 5) return null;
        String withoutWeekday = parts[1] + " " + parts[2] + " " + parts[3] + " " + parts[4];
        try {
            return LocalDateTime.parse(withoutWeekday, MAP_TIME).atZone(KST).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    // ── API 2: 수집 업로드 ───────────────────────────────────────

    /**
     * 측정 1건을 공용 서버에 올린다. sender 는 이 폰의 deviceId (계약서 §02).
     * 수업용 센서는 eCO₂ 를 co2 칸에 넣고, 습도·AQI·TVOC 는 보내지 않는다.
     *
     * @throws IllegalArgumentException 온도가 없는 측정 (31·32 는 업로드하지 않는다)
     */
    public void upload(Measurement measurement, String deviceId, Callback<UploadResult> callback) {
        Objects.requireNonNull(callback, "callback");
        String url = uploadUrl(measurement, deviceId);
        networkExecutor.execute(() -> {
            UploadResult result = uploadSync(url);
            callbackExecutor.execute(() -> callback.onResult(result));
        });
    }

    String uploadUrl(Measurement m, String deviceId) {
        Objects.requireNonNull(m, "measurement");
        Objects.requireNonNull(deviceId, "deviceId");
        if (m.temperatureC == null) {
            throw new IllegalArgumentException("temp 는 필수다. 31·32 는 공용 업로드를 생략한다 (TEMP_UNAVAILABLE)");
        }
        Integer co2 = m.co2Ppm != null ? m.co2Ppm : m.eco2Ppm;
        if (co2 == null) {
            throw new IllegalArgumentException("co2Ppm 또는 eco2Ppm 이 필요하다");
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put("mac", m.sensorMac);
        query.put("sensor", String.format(Locale.ROOT, "sensor %02d", m.sensorNo));
        query.put("sender", deviceId);
        query.put("mode", "mobile");
        query.put("temp", number(m.temperatureC));
        query.put("co2", String.valueOf(co2));
        query.put("time", String.valueOf(m.measuredAt.getEpochSecond()));
        query.put("rssi", String.valueOf(m.rssiDbm));

        StringBuilder url = new StringBuilder(baseUrl).append("/sensor/sensing/?");
        boolean first = true;
        for (Map.Entry<String, String> e : query.entrySet()) {
            if (!first) url.append('&');
            url.append(e.getKey()).append('=').append(encode(e.getValue()));
            first = false;
        }
        return url.toString();
    }

    UploadResult uploadSync(String url) {
        HttpTransport.Response response;
        try {
            response = transport.send("GET", url, Collections.singletonMap("Accept", "application/json"), null);
        } catch (HttpTransport.NotSentException e) {
            return UploadResult.notSent("공용 서버에 연결할 수 없습니다: " + e.getMessage());
        } catch (IOException e) {
            return UploadResult.unknown("요청 후 응답을 받지 못했습니다: " + e.getMessage());
        }

        if (response.status == 200) {
            try {
                JSONObject json = new JSONObject(response.body);
                long id = json.optLong("id", 0);
                if ("success".equals(json.optString("status")) && id > 0) {
                    return UploadResult.success(id);
                }
            } catch (JSONException ignored) {
                // 아래에서 UNKNOWN 으로 처리
            }
            return UploadResult.unknown("성공 응답의 형식이 다릅니다: " + response.body);
        }
        if (response.status == 400) {
            String error = response.body;
            try {
                error = new JSONObject(response.body).optString("error", response.body);
            } catch (JSONException ignored) {
                // 본문 그대로 사용
            }
            return UploadResult.inputError(error);
        }
        // 5xx 등: 서버가 저장했는지 알 수 없으므로 자동 재전송하지 않는다
        return UploadResult.unknown("공용 서버 오류 (" + response.status + ")");
    }

    private static String number(double value) {
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class MainThreadExecutor implements Executor {
        private final Handler handler = new Handler(Looper.getMainLooper());

        @Override
        public void execute(Runnable command) {
            handler.post(command);
        }
    }
}
