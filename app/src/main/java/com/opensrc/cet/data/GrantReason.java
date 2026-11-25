package com.opensrc.cet.data;

/** 먹이 지급 사유 (계약서 §14). */
public enum GrantReason {
    /** 사용자·센서·KST 수집일별 첫 기여, 먹이 2개 */
    FIRST_SENSOR_DAY,
    /** 일반지역 기여 5회마다, 먹이 1개 */
    NORMAL_FIVE,
    /** 음영지역 기여 5회마다, 먹이 2개 */
    SHADOW_FIVE,
    /** 유효 피드백 1건, 먹이 5개 */
    FEEDBACK
}
