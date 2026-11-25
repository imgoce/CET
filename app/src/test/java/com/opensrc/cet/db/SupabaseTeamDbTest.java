package com.opensrc.cet.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.opensrc.cet.data.CollectionInput;
import com.opensrc.cet.data.CollectionResult;
import com.opensrc.cet.data.FeedResult;
import com.opensrc.cet.data.GrantReason;
import com.opensrc.cet.data.Measurement;
import com.opensrc.cet.data.PetStage;
import com.opensrc.cet.data.PublicRoute;
import com.opensrc.cet.data.SiteEnvironment;
import com.opensrc.cet.data.UserState;
import com.opensrc.cet.net.HttpTransport;

import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** 미리 정한 응답으로 요청 형식·응답 해석·오류 코드 변환을 확인한다 (인터넷 불필요). */
public class SupabaseTeamDbTest {

    /** 마지막 요청을 기록하고 정해진 응답을 돌려주는 가짜 통로 */
    private static final class FakeTransport implements HttpTransport {
        String url;
        String body;
        Map<String, String> headers;
        Response response;
        IOException failure;

        @Override
        public Response send(String method, String url, Map<String, String> headers, String body) throws IOException {
            this.url = url;
            this.body = body;
            this.headers = headers;
            if (failure != null) throw failure;
            return response;
        }
    }

    private final FakeTransport http = new FakeTransport();

    private SupabaseTeamDb db(boolean testMode) {
        return new SupabaseTeamDb("https://example.supabase.co/", "sb_publishable_test", testMode, http,
                Runnable::run, Runnable::run);
    }

    /** 콜백 결과를 받아 돌려준다 (가짜 스레드라 즉시 실행된다) */
    private static <D> TeamDbResult<D> await(java.util.function.Consumer<TeamDb.Callback<D>> call) {
        java.util.List<TeamDbResult<D>> box = new java.util.ArrayList<>();
        call.accept(box::add);
        return box.get(0);
    }

    @Test
    public void registerUser_요청형식과_UserState_해석() throws Exception {
        http.response = new HttpTransport.Response(200, "{\"pet\": {\"level\": 1, \"stage\": \"EGG\", \"progress\": 0,"
                + " \"drawCount\": 0, \"foodBalance\": 0, \"nextDrawCost\": 24, \"ownedCostumes\": [],"
                + " \"equippedCostume\": null}, \"userId\": \"b64d28cf-c21e-4c63-8d21-28321a9b9e33\","
                + " \"nickname\": \"t27630\", \"normalTotal\": 0, \"shadowTotal\": 0, \"normalRemainder\": 0,"
                + " \"shadowRemainder\": 0}");
        UUID requestId = UUID.randomUUID();

        TeamDbResult<UserState> r = await(cb -> db(false).registerUser(requestId, "우왕이", cb));

        assertEquals("https://example.supabase.co/rest/v1/rpc/register_user", http.url);
        assertEquals("sb_publishable_test", http.headers.get("apikey"));
        JSONObject sent = new JSONObject(http.body);
        assertEquals(requestId.toString(), sent.getString("p_request_id"));
        assertEquals("우왕이", sent.getString("p_nickname"));

        assertTrue(r.ok);
        assertEquals("t27630", r.data.nickname);
        assertEquals(PetStage.EGG, r.data.pet.stage);
        assertEquals(24, r.data.pet.nextDrawCost);
        assertNull(r.data.pet.equippedCostume);
        assertTrue(r.data.pet.ownedCostumes.isEmpty());
    }

    @Test
    public void 계약서_오류코드는_그대로_변환_재시도불가() {
        http.response = new HttpTransport.Response(409, "{\"code\":\"PT409\",\"details\":\"이미 사용 중인 닉네임입니다\","
                + "\"hint\":null,\"message\":\"NICKNAME_TAKEN\"}");
        TeamDbResult<UserState> r = await(cb -> db(false).registerUser(UUID.randomUUID(), "dup", cb));

        assertFalse(r.ok);
        assertEquals(TeamDbErrorCode.NICKNAME_TAKEN, r.error.code);
        assertEquals("이미 사용 중인 닉네임입니다", r.error.message);
        assertFalse(r.error.retryable);
    }

    @Test
    public void DAILY_LIMIT_429도_계약서_코드로() {
        http.response = new HttpTransport.Response(429, "{\"message\":\"DAILY_LIMIT\",\"details\":\"x\"}");
        assertEquals(TeamDbErrorCode.DAILY_LIMIT, SupabaseTeamDb.toError(http.response).code);
    }

    @Test
    public void 네트워크_실패는_DB_UNAVAILABLE_재시도가능() {
        http.failure = new IOException("timeout");
        TeamDbResult<UserState> r = await(cb -> db(false).getUserState(UUID.randomUUID(), cb));

        assertFalse(r.ok);
        assertEquals(TeamDbErrorCode.DB_UNAVAILABLE, r.error.code);
        assertTrue(r.error.retryable);
    }

    @Test
    public void 서버_5xx는_DB_UNAVAILABLE_재시도가능() {
        TeamDbError e = SupabaseTeamDb.toError(new HttpTransport.Response(503, "Service Unavailable"));
        assertEquals(TeamDbErrorCode.DB_UNAVAILABLE, e.code);
        assertTrue(e.retryable);
    }

    @Test
    public void commitCollection_테스트모드와_요청JSON() throws Exception {
        http.response = new HttpTransport.Response(200, "{\"grants\": [{\"food\": 2, \"reason\": \"FIRST_SENSOR_DAY\"}],"
                + " \"credited\": true, \"replayed\": false, \"foodBalance\": 2, \"collectionId\":"
                + " \"10000000-0000-4000-8000-000000000003\", \"isShadowBefore\": true, \"normalRemainder\": 0,"
                + " \"shadowRemainder\": 1, \"measurementAdded\": true}");
        Measurement m = new Measurement(31, "B8:27:EB:D3:40:06",
                Instant.parse("2026-11-23T03:00:00Z"), Instant.parse("2026-11-23T03:00:03Z"),
                -53, null, null, null, 715, 2, 200);
        CollectionInput in = new CollectionInput(
                UUID.fromString("10000000-0000-4000-8000-000000000003"),
                UUID.fromString("10000000-0000-4000-8000-000000000001"),
                UUID.fromString("10000000-0000-4000-8000-000000000004"),
                Instant.parse("2026-11-23T03:00:00Z"), 31, m, PublicRoute.TEMP_UNAVAILABLE, null);

        TeamDbResult<CollectionResult> r = await(cb -> db(true).commitCollection(in, cb));

        JSONObject sent = new JSONObject(http.body);
        assertTrue(sent.getBoolean("p_test"));
        JSONObject input = sent.getJSONObject("p_input");
        assertEquals("TEMP_UNAVAILABLE", input.getString("publicRoute"));
        assertTrue(input.isNull("publicId"));
        assertTrue(input.getJSONObject("measurement").isNull("temperatureC"));
        assertEquals(715, input.getJSONObject("measurement").getInt("eco2Ppm"));
        assertEquals("2026-11-23T03:00:03Z", input.getJSONObject("measurement").getString("collectedAt"));

        assertTrue(r.ok);
        assertTrue(r.data.credited);
        assertTrue(r.data.isShadowBefore);
        assertEquals(GrantReason.FIRST_SENSOR_DAY, r.data.grants.get(0).reason);
        assertEquals(2, r.data.grants.get(0).food);
        assertEquals(1, r.data.shadowRemainder);
    }

    @Test
    public void 시각은_Z와_플러스0000_형식을_모두_읽는다() {
        assertEquals(Instant.parse("2026-11-25T03:00:00Z"), TeamDbJson.instant("2026-11-25T03:00:00+00:00"));
        assertEquals(Instant.parse("2026-11-25T02:14:06.374415Z"), TeamDbJson.instant("2026-11-25T02:14:06.374415+00:00"));
        assertEquals(Instant.parse("2026-11-25T03:00:00Z"), TeamDbJson.instant("2026-11-25T03:00:00Z"));
    }

    @Test
    public void siteEnvironment_없는값은_null() {
        http.response = new HttpTransport.Response(200, "{\"eco2Ppm\": 715, \"sensorNo\": 31, \"siteName\": \"sensor 31\","
                + " \"humidityRh\": null, \"temperatureC\": null, \"eco2MeasuredAt\": \"2026-11-25T03:00:20+00:00\","
                + " \"tempMeasuredAt\": null, \"humidityMeasuredAt\": null}");
        TeamDbResult<SiteEnvironment> r = await(cb -> db(false).getSiteEnvironment(31, cb));

        assertTrue(r.ok);
        assertNull(r.data.temperatureC);
        assertNull(r.data.tempMeasuredAt);
        assertEquals(Integer.valueOf(715), r.data.eco2Ppm);
        assertEquals(Instant.parse("2026-11-25T03:00:20Z"), r.data.eco2MeasuredAt);
    }

    @Test
    public void feedPet_진화결과() {
        http.response = new HttpTransport.Response(200, "{\"pet\": {\"level\": 16, \"stage\": \"ADULT\", \"progress\": 0,"
                + " \"drawCount\": 0, \"foodBalance\": 199, \"nextDrawCost\": 24, \"ownedCostumes\": [],"
                + " \"equippedCostume\": null}, \"evolved\": true, \"replayed\": false, \"leveledUp\": true}");
        TeamDbResult<FeedResult> r = await(cb -> db(false).feedPet(UUID.randomUUID(), UUID.randomUUID(), cb));

        assertTrue(r.ok);
        assertTrue(r.data.evolved);
        assertEquals(PetStage.ADULT, r.data.pet.stage);
        assertEquals(199, r.data.pet.foodBalance);
    }

    @Test
    public void equipCostume_null이면_벗기기로_보낸다() throws Exception {
        http.response = new HttpTransport.Response(200, "{\"pet\": {\"level\": 16, \"stage\": \"ADULT\", \"progress\": 0,"
                + " \"drawCount\": 1, \"foodBalance\": 1, \"nextDrawCost\": 25, \"ownedCostumes\": [\"STAR\"],"
                + " \"equippedCostume\": null}, \"replayed\": false}");
        TeamDbResult<com.opensrc.cet.data.EquipResult> r =
                await(cb -> db(false).equipCostume(UUID.randomUUID(), UUID.randomUUID(), null, cb));

        assertTrue(r.ok);

        assertTrue(new JSONObject(http.body).isNull("p_costume"));
    }
}
