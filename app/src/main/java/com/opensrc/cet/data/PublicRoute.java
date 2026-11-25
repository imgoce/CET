package com.opensrc.cet.data;

/** 수집 기록의 공용 API 2 경로 (계약서 §05). */
public enum PublicRoute {
    /** 공용 API 2 성공, publicId 있음 */
    UPLOADED,
    /** 31·32 수리 전. 공용 호출 생략, publicId = null */
    TEMP_UNAVAILABLE
}
