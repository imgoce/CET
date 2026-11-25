package com.opensrc.cet.db;

import com.opensrc.cet.data.AnalysisInputs;
import com.opensrc.cet.data.CollectionInput;
import com.opensrc.cet.data.CollectionResult;
import com.opensrc.cet.data.DrawResult;
import com.opensrc.cet.data.EquipResult;
import com.opensrc.cet.data.FeedResult;
import com.opensrc.cet.data.FeedbackInput;
import com.opensrc.cet.data.FeedbackResult;
import com.opensrc.cet.data.FeedbackRow;
import com.opensrc.cet.data.FeltHumidity;
import com.opensrc.cet.data.FeltTemp;
import com.opensrc.cet.data.Grant;
import com.opensrc.cet.data.GrantReason;
import com.opensrc.cet.data.Issue;
import com.opensrc.cet.data.Measurement;
import com.opensrc.cet.data.PetStage;
import com.opensrc.cet.data.PetState;
import com.opensrc.cet.data.RankRow;
import com.opensrc.cet.data.Ranking;
import com.opensrc.cet.data.Sample;
import com.opensrc.cet.data.ShadowSite;
import com.opensrc.cet.data.ShadowSites;
import com.opensrc.cet.data.SiteEnvironment;
import com.opensrc.cet.data.UserState;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 계약서 JSON ↔ Java 데이터 클래스 변환. Supabase 함수의 응답 필드 이름은 계약서와 같다.
 * 시각은 "2026-11-25T03:00:00Z" 와 "2026-11-25T03:00:00+00:00" 형식을 모두 읽는다.
 */
final class TeamDbJson {
    private TeamDbJson() {}

    // ── 요청 만들기 ─────────────────────────────────────────────

    static JSONObject collectionInput(CollectionInput in) throws JSONException {
        Measurement m = in.measurement;
        JSONObject measurement = new JSONObject()
                .put("sensorNo", m.sensorNo)
                .put("sensorMac", m.sensorMac)
                .put("measuredAt", m.measuredAt.toString())
                .put("collectedAt", m.collectedAt.toString())
                .put("rssiDbm", m.rssiDbm)
                .put("temperatureC", nullable(m.temperatureC))
                .put("humidityRh", nullable(m.humidityRh))
                .put("co2Ppm", nullable(m.co2Ppm))
                .put("eco2Ppm", nullable(m.eco2Ppm))
                .put("aqi", nullable(m.aqi))
                .put("tvocPpb", nullable(m.tvocPpb));
        return new JSONObject()
                .put("collectionId", in.collectionId.toString())
                .put("userId", in.userId.toString())
                .put("windowId", in.windowId.toString())
                .put("windowStartAt", in.windowStartAt.toString())
                .put("candidateSensorNo", in.candidateSensorNo)
                .put("measurement", measurement)
                .put("publicRoute", in.publicRoute.name())
                .put("publicId", nullable(in.publicId));
    }

    static JSONObject feedbackInput(FeedbackInput in) throws JSONException {
        JSONArray issues = new JSONArray();
        for (Issue issue : in.issues) {
            issues.put(issue.name());
        }
        return new JSONObject()
                .put("feedbackId", in.feedbackId.toString())
                .put("collectionId", in.collectionId.toString())
                .put("userId", in.userId.toString())
                .put("feltTemp", in.feltTemp.name())
                .put("feltHumidity", in.feltHumidity.name())
                .put("issues", issues)
                .put("memo", nullable(in.memo));
    }

    private static Object nullable(Object value) {
        return value == null ? JSONObject.NULL : value;
    }

    // ── 응답 읽기 ───────────────────────────────────────────────

    static UserState userState(JSONObject o) throws JSONException {
        return new UserState(
                UUID.fromString(o.getString("userId")),
                o.getString("nickname"),
                petState(o.getJSONObject("pet")),
                o.getLong("normalTotal"),
                o.getLong("shadowTotal"),
                o.getInt("normalRemainder"),
                o.getInt("shadowRemainder"));
    }

    static PetState petState(JSONObject o) throws JSONException {
        List<String> owned = new ArrayList<>();
        JSONArray arr = o.getJSONArray("ownedCostumes");
        for (int i = 0; i < arr.length(); i++) {
            owned.add(arr.getString(i));
        }
        return new PetState(
                o.getLong("level"),
                o.getLong("progress"),
                o.getLong("foodBalance"),
                PetStage.valueOf(o.getString("stage")),
                o.getLong("drawCount"),
                o.getLong("nextDrawCost"),
                owned,
                optString(o, "equippedCostume"));
    }

    static List<Grant> grants(JSONArray arr) throws JSONException {
        List<Grant> grants = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject g = arr.getJSONObject(i);
            grants.add(new Grant(GrantReason.valueOf(g.getString("reason")), g.getInt("food")));
        }
        return grants;
    }

    static CollectionResult collectionResult(JSONObject o) throws JSONException {
        return new CollectionResult(
                UUID.fromString(o.getString("collectionId")),
                o.getBoolean("credited"),
                o.getBoolean("isShadowBefore"),
                o.getBoolean("measurementAdded"),
                grants(o.getJSONArray("grants")),
                o.getLong("foodBalance"),
                o.getInt("normalRemainder"),
                o.getInt("shadowRemainder"),
                o.getBoolean("replayed"));
    }

    static FeedbackResult feedbackResult(JSONObject o) throws JSONException {
        return new FeedbackResult(
                UUID.fromString(o.getString("feedbackId")),
                instant(o.getString("submittedAt")),
                o.getInt("todayCount"),
                grants(o.getJSONArray("grants")),
                o.getLong("foodBalance"),
                o.getBoolean("replayed"));
    }

    static SiteEnvironment siteEnvironment(JSONObject o) throws JSONException {
        return new SiteEnvironment(
                o.getInt("sensorNo"),
                o.getString("siteName"),
                optDouble(o, "temperatureC"),
                optInstant(o, "tempMeasuredAt"),
                optDouble(o, "humidityRh"),
                optInstant(o, "humidityMeasuredAt"),
                optInt(o, "eco2Ppm"),
                optInstant(o, "eco2MeasuredAt"));
    }

    static ShadowSites shadowSites(JSONObject o) throws JSONException {
        List<ShadowSite> sites = new ArrayList<>();
        JSONArray arr = o.optJSONArray("sites");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.getJSONObject(i);
                sites.add(new ShadowSite(s.getInt("sensorNo"), s.getLong("uniqueMeasurements")));
            }
        }
        return new ShadowSites(sites, optInstant(o, "updatedAt"));
    }

    static AnalysisInputs analysisInputs(JSONObject o) throws JSONException {
        List<Sample> samples = new ArrayList<>();
        JSONArray s = o.getJSONArray("samples");
        for (int i = 0; i < s.length(); i++) {
            JSONObject row = s.getJSONObject(i);
            samples.add(new Sample(
                    row.getInt("sensorNo"),
                    instant(row.getString("measuredAt")),
                    optDouble(row, "temperatureC"),
                    optDouble(row, "humidityRh")));
        }
        List<FeedbackRow> feedbacks = new ArrayList<>();
        JSONArray f = o.getJSONArray("feedbacks");
        for (int i = 0; i < f.length(); i++) {
            JSONObject row = f.getJSONObject(i);
            List<Issue> issues = new ArrayList<>();
            JSONArray arr = row.getJSONArray("issues");
            for (int j = 0; j < arr.length(); j++) {
                issues.add(Issue.valueOf(arr.getString(j)));
            }
            feedbacks.add(new FeedbackRow(
                    UUID.fromString(row.getString("feedbackId")),
                    row.getInt("sensorNo"),
                    instant(row.getString("collectedAt")),
                    FeltTemp.valueOf(row.getString("feltTemp")),
                    FeltHumidity.valueOf(row.getString("feltHumidity")),
                    issues));
        }
        return new AnalysisInputs(samples, feedbacks);
    }

    static FeedResult feedResult(JSONObject o) throws JSONException {
        return new FeedResult(petState(o.getJSONObject("pet")),
                o.getBoolean("leveledUp"), o.getBoolean("evolved"), o.getBoolean("replayed"));
    }

    static DrawResult drawResult(JSONObject o) throws JSONException {
        return new DrawResult(petState(o.getJSONObject("pet")),
                o.getString("drawnCostume"), o.getLong("spentFood"), o.getBoolean("replayed"));
    }

    static EquipResult equipResult(JSONObject o) throws JSONException {
        return new EquipResult(petState(o.getJSONObject("pet")), o.getBoolean("replayed"));
    }

    static Ranking ranking(JSONObject o) throws JSONException {
        List<RankRow> top = new ArrayList<>();
        JSONArray arr = o.getJSONArray("top");
        for (int i = 0; i < arr.length(); i++) {
            top.add(rankRow(arr.getJSONObject(i)));
        }
        return new Ranking(top, rankRow(o.getJSONObject("me")));
    }

    private static RankRow rankRow(JSONObject o) throws JSONException {
        return new RankRow(o.getInt("rank"), o.getString("nickname"),
                o.getLong("level"), o.getLong("progress"), PetStage.valueOf(o.getString("stage")));
    }

    // ── null 을 허용하는 값 읽기 ─────────────────────────────────

    static Instant instant(String text) {
        return OffsetDateTime.parse(text).toInstant();
    }

    private static Instant optInstant(JSONObject o, String key) throws JSONException {
        return o.isNull(key) ? null : instant(o.getString(key));
    }

    private static Double optDouble(JSONObject o, String key) throws JSONException {
        return o.isNull(key) ? null : o.getDouble(key);
    }

    private static Integer optInt(JSONObject o, String key) throws JSONException {
        return o.isNull(key) ? null : o.getInt(key);
    }

    private static String optString(JSONObject o, String key) throws JSONException {
        return o.isNull(key) ? null : o.getString(key);
    }
}
