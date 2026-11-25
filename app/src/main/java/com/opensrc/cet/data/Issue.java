package com.opensrc.cet.data;

/**
 * 불편 사항 (계약서 §09). NONE(개선 필요 없음)은 단독으로만 선택한다.
 * 선언 순서가 집계의 동률 정렬 순서다 (그늘 없음 → 바람 강함 → 냄새 → 앉을 곳 없음 → 기타).
 */
public enum Issue { NONE, NO_SHADE, STRONG_WIND, ODOR, NO_SEAT, OTHER }
