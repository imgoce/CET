package com.opensrc.cet.data;

/** 음영지역 1곳 (계약서 §07). */
public final class ShadowSite {
    public final int sensorNo;
    /** 프로젝트 시작 이후 고유 유효 측정 수 */
    public final long uniqueMeasurements;

    public ShadowSite(int sensorNo, long uniqueMeasurements) {
        this.sensorNo = sensorNo;
        this.uniqueMeasurements = uniqueMeasurements;
    }
}
