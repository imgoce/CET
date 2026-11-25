package com.opensrc.cet.db;

import android.os.Handler;
import android.os.Looper;

import com.opensrc.cet.data.AnalysisInputs;
import com.opensrc.cet.data.CollectionInput;
import com.opensrc.cet.data.CollectionResult;
import com.opensrc.cet.data.DrawResult;
import com.opensrc.cet.data.EquipResult;
import com.opensrc.cet.data.FeedResult;
import com.opensrc.cet.data.FeedbackInput;
import com.opensrc.cet.data.FeedbackResult;
import com.opensrc.cet.data.Ranking;
import com.opensrc.cet.data.ShadowSites;
import com.opensrc.cet.data.SiteEnvironment;
import com.opensrc.cet.data.UserState;
import com.opensrc.cet.net.HttpTransport;
import com.opensrc.cet.net.HttpUrlConnectionTransport;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * {@link TeamDb}의 Supabase 구현 (③ 곽예찬). backend/ 의 SQL 함수를 HTTP 로 호출한다.
 *
 * <pre>
 * TeamDb db = new SupabaseTeamDb(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY, false);
 * TeamDb testDb = new SupabaseTeamDb(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY, true); // 테스트 모드
 * </pre>
 *
 * <ul>
 *   <li>요청은 백그라운드 스레드에서 보내고, 콜백은 메인 스레드에서 부른다.</li>
 *   <li>DB가 돌려준 계약서 오류 코드는 {@link TeamDbErrorCode}로 바꾼다 (retryable = false).</li>
 *   <li>네트워크 실패·서버 오류(5xx)는 DB_UNAVAILABLE (retryable = true). 같은 ID로 다시 보내면 된다.</li>
 *   <li>테스트 모드 인스턴스는 commitCollection 을 테스트 저장 공간에만 기록한다 (DC-7).</li>
 * </ul>
 */
public final class SupabaseTeamDb implements TeamDb {

    /** JSON 응답을 결과 객체로 바꾸는 함수 */
    interface Parser<T> {
        T parse(JSONObject json) throws JSONException;
    }

    private final String rpcBaseUrl;
    private final String apiKey;
    private final boolean testMode;
    private final HttpTransport transport;
    private final Executor networkExecutor;
    private final Executor callbackExecutor;

    public SupabaseTeamDb(String supabaseUrl, String publishableKey, boolean testMode) {
        this(supabaseUrl, publishableKey, testMode, new HttpUrlConnectionTransport(),
                Executors.newFixedThreadPool(2), new MainThreadExecutor());
    }

    /** 테스트용: 통로와 스레드를 바꿔 끼울 수 있다 */
    SupabaseTeamDb(String supabaseUrl, String publishableKey, boolean testMode, HttpTransport transport,
                   Executor networkExecutor, Executor callbackExecutor) {
        String base = Objects.requireNonNull(supabaseUrl, "supabaseUrl");
        this.rpcBaseUrl = (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/rest/v1/rpc/";
        this.apiKey = Objects.requireNonNull(publishableKey, "publishableKey");
        this.testMode = testMode;
        this.transport = Objects.requireNonNull(transport, "transport");
        this.networkExecutor = Objects.requireNonNull(networkExecutor, "networkExecutor");
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
    }

    // ── §08 닉네임 접속 ───────────────────────────────────────────

    @Override
    public void registerUser(UUID requestId, String nickname, Callback<UserState> callback) {
        call("register_user", params -> params
                .put("p_request_id", requestId.toString())
                .put("p_nickname", nickname), TeamDbJson::userState, callback);
    }

    @Override
    public void loginByNickname(String nickname, Callback<UserState> callback) {
        call("login_by_nickname", params -> params.put("p_nickname", nickname), TeamDbJson::userState, callback);
    }

    @Override
    public void getUserState(UUID userId, Callback<UserState> callback) {
        call("get_user_state", params -> params.put("p_user_id", userId.toString()), TeamDbJson::userState, callback);
    }

    // ── §06 수집 / §09 피드백 ────────────────────────────────────

    @Override
    public void commitCollection(CollectionInput input, Callback<CollectionResult> callback) {
        call("commit_collection", params -> params
                .put("p_input", TeamDbJson.collectionInput(input))
                .put("p_test", testMode), TeamDbJson::collectionResult, callback);
    }

    @Override
    public void submitFeedback(FeedbackInput input, Callback<FeedbackResult> callback) {
        call("submit_feedback", params -> params.put("p_input", TeamDbJson.feedbackInput(input)),
                TeamDbJson::feedbackResult, callback);
    }

    // ── §10·07·12 조회 ───────────────────────────────────────────

    @Override
    public void getSiteEnvironment(int sensorNo, Callback<SiteEnvironment> callback) {
        call("get_site_environment", params -> params.put("p_sensor_no", sensorNo),
                TeamDbJson::siteEnvironment, callback);
    }

    @Override
    public void getShadowSites(Callback<ShadowSites> callback) {
        call("get_shadow_sites", params -> params, TeamDbJson::shadowSites, callback);
    }

    @Override
    public void getAnalysisInputs(LocalDate asOfDate, Callback<AnalysisInputs> callback) {
        call("get_analysis_inputs", params -> params.put("p_as_of_date", asOfDate.toString()),
                TeamDbJson::analysisInputs, callback);
    }

    // ── §11 먹이·장식·랭킹 ───────────────────────────────────────

    @Override
    public void feedPet(UUID userId, UUID requestId, Callback<FeedResult> callback) {
        call("feed_pet", params -> params
                .put("p_user_id", userId.toString())
                .put("p_request_id", requestId.toString()), TeamDbJson::feedResult, callback);
    }

    @Override
    public void drawCostume(UUID userId, UUID requestId, Callback<DrawResult> callback) {
        call("draw_costume", params -> params
                .put("p_user_id", userId.toString())
                .put("p_request_id", requestId.toString()), TeamDbJson::drawResult, callback);
    }

    @Override
    public void equipCostume(UUID userId, UUID requestId, String costume, Callback<EquipResult> callback) {
        call("equip_costume", params -> params
                .put("p_user_id", userId.toString())
                .put("p_request_id", requestId.toString())
                .put("p_costume", costume == null ? JSONObject.NULL : costume), TeamDbJson::equipResult, callback);
    }

    @Override
    public void getRanking(UUID userId, Callback<Ranking> callback) {
        call("get_ranking", params -> params.put("p_user_id", userId.toString()), TeamDbJson::ranking, callback);
    }

    // ── 공통 처리 ─────────────────────────────────────────────────

    /** 요청 파라미터를 채우는 함수 */
    interface ParamsBuilder {
        JSONObject fill(JSONObject params) throws JSONException;
    }

    private <T> void call(String function, ParamsBuilder params, Parser<T> parser, Callback<T> callback) {
        Objects.requireNonNull(callback, "callback");
        networkExecutor.execute(() -> {
            TeamDbResult<T> result = invoke(function, params, parser);
            callbackExecutor.execute(() -> callback.onResult(result));
        });
    }

    /** 동기 호출. 백그라운드 스레드에서만 부른다 (단위 테스트는 직접 부른다). */
    <T> TeamDbResult<T> invoke(String function, ParamsBuilder params, Parser<T> parser) {
        String body;
        try {
            body = params.fill(new JSONObject()).toString();
        } catch (JSONException | RuntimeException e) {
            return TeamDbResult.failure(TeamDbErrorCode.INVALID_INPUT, "요청을 만들 수 없습니다: " + e.getMessage(), false);
        }

        HttpTransport.Response response;
        try {
            response = transport.send("POST", rpcBaseUrl + function, headers(), body);
        } catch (IOException e) {
            return TeamDbResult.failure(TeamDbErrorCode.DB_UNAVAILABLE, "DB에 연결할 수 없습니다: " + e.getMessage(), true);
        }

        if (response.status >= 200 && response.status < 300) {
            try {
                return TeamDbResult.success(parser.parse(new JSONObject(response.body)));
            } catch (JSONException | RuntimeException e) {
                return TeamDbResult.failure(TeamDbErrorCode.DB_UNAVAILABLE,
                        "응답을 해석할 수 없습니다: " + e.getMessage(), false);
            }
        }
        return TeamDbResult.failure(toError(response));
    }

    /** HTTP 오류 응답 → 계약서 오류 코드 */
    static TeamDbError toError(HttpTransport.Response response) {
        String message = "";
        String details = "";
        try {
            JSONObject json = new JSONObject(response.body);
            message = json.optString("message", "");
            details = json.isNull("details") ? "" : json.optString("details", "");
        } catch (JSONException ignored) {
            // 본문이 JSON 이 아니면 상태 코드로만 판단
        }

        for (TeamDbErrorCode code : TeamDbErrorCode.values()) {
            if (code.name().equals(message)) {
                return new TeamDbError(code, details.isEmpty() ? message : details, false);
            }
        }
        if (response.status >= 500 || response.status == 408) {
            return new TeamDbError(TeamDbErrorCode.DB_UNAVAILABLE, "DB 서버 오류 (" + response.status + ") " + message, true);
        }
        return new TeamDbError(TeamDbErrorCode.INVALID_INPUT, "요청 오류 (" + response.status + ") " + message, false);
    }

    private Map<String, String> headers() {
        Map<String, String> headers = new HashMap<>();
        headers.put("apikey", apiKey);
        headers.put("Content-Type", "application/json; charset=utf-8");
        headers.put("Accept", "application/json");
        return headers;
    }

    /** 콜백을 Android 메인 스레드에서 실행 */
    private static final class MainThreadExecutor implements Executor {
        private final Handler handler = new Handler(Looper.getMainLooper());

        @Override
        public void execute(Runnable command) {
            handler.post(command);
        }
    }
}
