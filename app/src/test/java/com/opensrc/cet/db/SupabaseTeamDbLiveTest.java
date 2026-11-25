package com.opensrc.cet.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.opensrc.cet.data.CollectionInput;
import com.opensrc.cet.data.CollectionResult;
import com.opensrc.cet.data.FeedResult;
import com.opensrc.cet.data.FeedbackInput;
import com.opensrc.cet.data.FeedbackResult;
import com.opensrc.cet.data.FeltHumidity;
import com.opensrc.cet.data.FeltTemp;
import com.opensrc.cet.data.Issue;
import com.opensrc.cet.data.Measurement;
import com.opensrc.cet.data.PublicRoute;
import com.opensrc.cet.data.Ranking;
import com.opensrc.cet.data.ShadowSites;
import com.opensrc.cet.data.SiteEnvironment;
import com.opensrc.cet.data.UserState;
import com.opensrc.cet.net.HttpUrlConnectionTransport;

import org.junit.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.UUID;

/**
 * 실제 Supabase 에 연결해 계약서 §13 "첫 공동 시연" 흐름을 확인한다.
 * 환경 변수 SUPABASE_URL, SUPABASE_PUBLISHABLE_KEY 가 있을 때만 실행된다 (없으면 건너뜀).
 * 테스트 사용자(닉네임 j로 시작)가 운영 DB 에 생기므로 dev_reset.sql 로 정리한다.
 */
public class SupabaseTeamDbLiveTest {

    private static <T> T ok(TeamDbResult<T> r) {
        assertTrue("실패: " + r.error, r.ok);
        return r.data;
    }

    @Test
    public void 첫_공동_시연_흐름() {
        String url = System.getenv("SUPABASE_URL");
        String key = System.getenv("SUPABASE_PUBLISHABLE_KEY");
        assumeTrue("SUPABASE_URL/SUPABASE_PUBLISHABLE_KEY 없음 → 건너뜀", url != null && key != null);

        SupabaseTeamDb db = new SupabaseTeamDb(url, key, false, new HttpUrlConnectionTransport(),
                Runnable::run, Runnable::run);

        // 1. 신규 닉네임
        String nickname = "j" + (100000 + new java.util.Random().nextInt(900000));
        UserState user = ok(db.invoke("register_user", p -> p
                .put("p_request_id", UUID.randomUUID().toString()).put("p_nickname", nickname), TeamDbJson::userState));
        assertEquals(0, user.pet.foodBalance);

        // 2. 정상 센서 첫 유효 기여 → 잔액 2
        Instant start = Instant.now().minus(20, ChronoUnit.SECONDS).truncatedTo(ChronoUnit.SECONDS);
        Measurement m = new Measurement(20, "B8:27:EB:00:00:20", start, start.plusSeconds(2),
                -60, 21.5, null, 700, null, null, null);
        CollectionInput in = new CollectionInput(UUID.randomUUID(), user.userId, UUID.randomUUID(), start,
                20, m, PublicRoute.UPLOADED, 999L);
        CollectionResult first = ok(db.invoke("commit_collection", p -> p
                .put("p_input", TeamDbJson.collectionInput(in)).put("p_test", false), TeamDbJson::collectionResult));
        assertTrue(first.credited);
        assertEquals(2, first.foodBalance);

        // 3. 같은 수집 재전송 → 그대로 2
        CollectionResult replay = ok(db.invoke("commit_collection", p -> p
                .put("p_input", TeamDbJson.collectionInput(in)).put("p_test", false), TeamDbJson::collectionResult));
        assertTrue(replay.replayed);
        assertEquals(2, replay.foodBalance);

        // 4. 그 수집의 피드백 → 7
        FeedbackInput fb = new FeedbackInput(UUID.randomUUID(), in.collectionId, user.userId,
                FeltTemp.HOT, FeltHumidity.OK, Collections.singletonList(Issue.NO_SHADE), null);
        FeedbackResult feedback = ok(db.invoke("submit_feedback", p -> p
                .put("p_input", TeamDbJson.feedbackInput(fb)), TeamDbJson::feedbackResult));
        assertEquals(7, feedback.foodBalance);

        // 5. 다른 폰에서 같은 닉네임으로 접속 → 7
        UserState other = ok(db.invoke("login_by_nickname", p -> p.put("p_nickname", nickname), TeamDbJson::userState));
        assertEquals(user.userId, other.userId);
        assertEquals(7, other.pet.foodBalance);

        // 6. 먹이 주기, 랭킹, 조회
        FeedResult feed = ok(db.invoke("feed_pet", p -> p.put("p_user_id", user.userId.toString())
                .put("p_request_id", UUID.randomUUID().toString()), TeamDbJson::feedResult));
        assertEquals(6, feed.pet.foodBalance);

        Ranking ranking = ok(db.invoke("get_ranking", p -> p.put("p_user_id", user.userId.toString()), TeamDbJson::ranking));
        assertEquals(nickname, ranking.me.nickname);

        SiteEnvironment env = ok(db.invoke("get_site_environment", p -> p.put("p_sensor_no", 20), TeamDbJson::siteEnvironment));
        assertNotNull(env.temperatureC);

        ShadowSites shadow = ok(db.invoke("get_shadow_sites", p -> p, TeamDbJson::shadowSites));
        assertEquals(5, shadow.sites.size());

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        assertFalse(ok(db.invoke("get_analysis_inputs", p -> p.put("p_as_of_date", today.toString()),
                TeamDbJson::analysisInputs)).feedbacks.isEmpty());

        // 7. 오류 코드
        TeamDbResult<UserState> missing = db.invoke("login_by_nickname", p -> p.put("p_nickname", "없는_" + nickname),
                TeamDbJson::userState);
        assertEquals(TeamDbErrorCode.NICKNAME_NOT_FOUND, missing.error.code);
    }
}
