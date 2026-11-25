package com.opensrc.cet.db;

/** 팀 DB 메서드의 실패 코드 (계약서 §08·09·11·13). */
public enum TeamDbErrorCode {
    // 공통 (§13)
    /** 입력 타입·범위·선택지 오류. 같은 값으로 재시도하지 않는다 */
    INVALID_INPUT,
    /** 이미 쓴 ID에 다른 입력을 보냄 */
    ID_CONFLICT,
    /** DB 연결 실패·응답 유실. 같은 ID로 재시도한다 */
    DB_UNAVAILABLE,

    // 닉네임·사용자 (§08)
    NICKNAME_TAKEN,
    NICKNAME_NOT_FOUND,
    USER_NOT_FOUND,

    // 피드백 (§09)
    /** 수집 후 600초 초과 */
    FEEDBACK_EXPIRED,
    /** 다른 사용자의 기록, 비후보·미인정 기록, 테스트 기록 */
    NOT_ELIGIBLE,
    /** 같은 수집에 이미 피드백이 있음 */
    FEEDBACK_EXISTS,
    /** 오늘(KST) 4번째 제출 */
    DAILY_LIMIT,

    // 먹이·장식 (§11)
    INSUFFICIENT_FOOD,
    /** Lv 16 미만이라 뽑기 불가 */
    LEVEL_LOCKED,
    /** 4종 모두 보유 */
    ALL_OWNED,
    /** 보유하지 않은 장식을 착용하려 함 */
    NOT_OWNED
}
