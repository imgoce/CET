package com.opensrc.cet.db;

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

import java.time.LocalDate;
import java.util.UUID;

/**
 * 앱이 호출하는 팀 DB 메서드 12개 (계약서 §06~§12). 구현: ③ 곽예찬.
 *
 * <p>모든 메서드는 비동기다. 결과는 콜백으로 메인 스레드에서 받는다.
 * 쓰기 메서드는 같은 요청 ID로 다시 보내면 처음 결과를 돌려준다(replayed=true).
 * 테스트 모드는 테스트 저장소에 연결된 별도 TeamDb 인스턴스를 쓴다(운영 데이터 변경 0).
 *
 * <p>구현이 준비되기 전에는 이 인터페이스를 직접 구현한 가짜 객체로 화면·로직을 시험한다.
 */
public interface TeamDb {

    /** 결과를 받는 콜백. 메인 스레드에서 호출된다. */
    interface Callback<T> {
        void onResult(TeamDbResult<T> result);
    }

    // ── §08 닉네임 접속과 사용자 상태 ───────────────────────────────

    /** 신규 닉네임 등록 + 기본 펫 생성. 실패: NICKNAME_TAKEN, INVALID_INPUT */
    void registerUser(UUID requestId, String nickname, Callback<UserState> callback);

    /** 기존 닉네임으로 접속. 새 사용자를 만들지 않는다. 실패: NICKNAME_NOT_FOUND */
    void loginByNickname(String nickname, Callback<UserState> callback);

    /** 기기에 저장된 userId 로 자동 접속. 실패: USER_NOT_FOUND */
    void getUserState(UUID userId, Callback<UserState> callback);

    // ── §06·07 수집 저장 ──────────────────────────────────────────

    /** 수집 기록 저장 + 기여·음영·보상 판정. 실패: INVALID_INPUT, ID_CONFLICT, USER_NOT_FOUND */
    void commitCollection(CollectionInput input, Callback<CollectionResult> callback);

    // ── §09 피드백 ────────────────────────────────────────────────

    /** 피드백 저장 + 보상. 실패: FEEDBACK_EXPIRED, NOT_ELIGIBLE, FEEDBACK_EXISTS, DAILY_LIMIT, INVALID_INPUT */
    void submitFeedback(FeedbackInput input, Callback<FeedbackResult> callback);

    // ── §10·07·12 조회 ────────────────────────────────────────────

    /** 선택 지점의 항목별 마지막 유효값 */
    void getSiteEnvironment(int sensorNo, Callback<SiteEnvironment> callback);

    /** 현재 음영지역 5곳 */
    void getShadowSites(Callback<ShadowSites> callback);

    /** 집계 원자료: asOfDate(KST) 포함 최근 14일 */
    void getAnalysisInputs(LocalDate asOfDate, Callback<AnalysisInputs> callback);

    // ── §11 먹이·장식·랭킹 ────────────────────────────────────────

    /** 먹이 1개 주기. 실패: INSUFFICIENT_FOOD */
    void feedPet(UUID userId, UUID requestId, Callback<FeedResult> callback);

    /** 장식 뽑기. 실패: LEVEL_LOCKED, ALL_OWNED, INSUFFICIENT_FOOD */
    void drawCostume(UUID userId, UUID requestId, Callback<DrawResult> callback);

    /** 장식 착용. costume == null 이면 벗기기. 실패: NOT_OWNED */
    void equipCostume(UUID userId, UUID requestId, String costume, Callback<EquipResult> callback);

    /** 상위 50명 + 내 순위 */
    void getRanking(UUID userId, Callback<Ranking> callback);
}
