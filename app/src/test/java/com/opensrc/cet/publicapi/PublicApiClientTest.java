package com.opensrc.cet.publicapi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.opensrc.cet.data.Measurement;
import com.opensrc.cet.net.HttpTransport;

import org.junit.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.time.Instant;
import java.util.Map;

/** 공용 API 1·2 요청 형식과 결과 분류 (인터넷 불필요) */
public class PublicApiClientTest {

    private static final class FakeTransport implements HttpTransport {
        String url;
        Response response;
        IOException failure;

        @Override
        public Response send(String method, String url, Map<String, String> headers, String body) throws IOException {
            this.url = url;
            if (failure != null) throw failure;
            return response;
        }
    }

    private final FakeTransport http = new FakeTransport();
    private final PublicApiClient client =
            new PublicApiClient("http://203.255.81.72:10021", http, Runnable::run, Runnable::run);

    private static Measurement opensrc1() {
        // 계약서 §05 예시: sensor 07, eCO2 710, 29.5℃, Unix 1795402800
        return new Measurement(7, "88:A2:9E:9B:5F:09", Instant.ofEpochSecond(1795402800L),
                Instant.ofEpochSecond(1795402803L), -58, 29.5, 55.0, null, 710, 2, 200);
    }

    @Test
    public void 업로드_쿼리는_계약서_예시와_같다() {
        String url = client.uploadUrl(opensrc1(), "10000000-0000-4000-8000-000000000002");
        assertEquals("http://203.255.81.72:10021/sensor/sensing/?mac=88%3AA2%3A9E%3A9B%3A5F%3A09"
                + "&sensor=sensor%2007&sender=10000000-0000-4000-8000-000000000002&mode=mobile"
                + "&temp=29.5&co2=710&time=1795402800&rssi=-58", url);
    }

    @Test(expected = IllegalArgumentException.class)
    public void 온도_없는_31_32는_업로드하지_않는다() {
        Measurement m = new Measurement(31, "B8:27:EB:D3:40:06", Instant.now(), Instant.now(),
                -53, null, null, null, 715, 2, 200);
        client.uploadUrl(m, "device");
    }

    @Test
    public void 성공_200과_id() {
        http.response = new HttpTransport.Response(200, "{\"status\":\"success\",\"message\":\"Data received\",\"id\":123}");
        UploadResult r = client.uploadSync("u");
        assertEquals(UploadResult.Status.SUCCESS, r.status);
        assertEquals(Long.valueOf(123), r.publicId);
    }

    @Test
    public void 입력오류_400() {
        http.response = new HttpTransport.Response(400, "{\"error\":\"missing temp\"}");
        UploadResult r = client.uploadSync("u");
        assertEquals(UploadResult.Status.INPUT_ERROR, r.status);
        assertEquals("missing temp", r.error);
    }

    @Test
    public void 연결_실패는_NOT_SENT_재시도가능() {
        http.failure = new HttpTransport.NotSentException(new ConnectException("refused"));
        assertEquals(UploadResult.Status.NOT_SENT, client.uploadSync("u").status);
    }

    @Test
    public void 보낸_뒤_응답유실은_UNKNOWN_자동재전송금지() {
        http.failure = new IOException("read timed out");
        assertEquals(UploadResult.Status.UNKNOWN, client.uploadSync("u").status);
        http.failure = null;
        http.response = new HttpTransport.Response(502, "Bad Gateway");
        assertEquals(UploadResult.Status.UNKNOWN, client.uploadSync("u").status);
    }

    @Test
    public void 지도_응답_해석() {
        http.response = new HttpTransport.Response(200, "[{\"sensor\":\"sensor 18\",\"latitude\":36.62752,"
                + "\"longitude\":127.453162,\"temperature\":8,\"co2\":1112,\"time\":\"Tue Nov 10 07:09:55 2026\","
                + "\"fresh\":false,\"mode\":\"direct\"},{\"sensor\":\"pws01\",\"latitude\":1,\"longitude\":2,"
                + "\"temperature\":null,\"co2\":null,\"time\":\"Tue Nov  3 07:09:55 2026\",\"fresh\":true,\"mode\":\"direct\"}]");
        MapResult r = client.fetchMapSync();

        assertTrue(r.ok);
        assertEquals("http://203.255.81.72:10021/sensor/api/map", http.url);
        PublicMapRow s18 = r.rows.get(0);
        assertEquals(Integer.valueOf(18), s18.sensorNo);
        assertEquals(Double.valueOf(8), s18.temperatureC);
        // KST 07:09:55 = UTC 전날 22:09:55
        assertEquals(Instant.parse("2026-11-09T22:09:55Z"), s18.measuredAt);
        PublicMapRow pws = r.rows.get(1);
        assertNull(pws.sensorNo);
        assertNull(pws.temperatureC);
        assertEquals(Instant.parse("2026-11-02T22:09:55Z"), pws.measuredAt);
    }

    @Test
    public void 센서이름_번호변환() {
        assertEquals(Integer.valueOf(7), PublicApiClient.sensorNo("sensor 07"));
        assertEquals(Integer.valueOf(34), PublicApiClient.sensorNo("sensor 34"));
        assertNull(PublicApiClient.sensorNo("sensor 35"));
        assertNull(PublicApiClient.sensorNo("vs01"));
    }
}
