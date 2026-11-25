package com.opensrc.cet.publicapi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 공용 API 1 조회 결과. 실패하면 화면은 캐시와 마지막 갱신 시각을 보여준다 (EX-5). */
public final class MapResult {
    public final boolean ok;
    /** ok 일 때 응답 행 전체 (미사용 센서 포함) */
    public final List<PublicMapRow> rows;
    /** 실패 사유 (ok 이면 null) */
    public final String error;

    private MapResult(boolean ok, List<PublicMapRow> rows, String error) {
        this.ok = ok;
        this.rows = Collections.unmodifiableList(new ArrayList<>(rows));
        this.error = error;
    }

    public static MapResult success(List<PublicMapRow> rows) {
        return new MapResult(true, rows, null);
    }

    public static MapResult failure(String error) {
        return new MapResult(false, Collections.<PublicMapRow>emptyList(), error);
    }
}
