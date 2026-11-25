package com.opensrc.cet.data;

import java.time.Instant;
import java.util.Objects;

/**
 * 선택 지점의 항목별 마지막 유효값과 그 측정 시각 (계약서 §10).
 * 측정이 없으면 값·시각 모두 null. 31·32 의 온습도는 수리 전 항상 null.
 */
public final class SiteEnvironment {
    public final int sensorNo;
    public final String siteName;
    public final Double temperatureC;
    public final Instant tempMeasuredAt;
    public final Double humidityRh;
    public final Instant humidityMeasuredAt;
    public final Integer eco2Ppm;
    public final Instant eco2MeasuredAt;

    public SiteEnvironment(int sensorNo, String siteName,
                           Double temperatureC, Instant tempMeasuredAt,
                           Double humidityRh, Instant humidityMeasuredAt,
                           Integer eco2Ppm, Instant eco2MeasuredAt) {
        this.sensorNo = sensorNo;
        this.siteName = Objects.requireNonNull(siteName, "siteName");
        this.temperatureC = temperatureC;
        this.tempMeasuredAt = tempMeasuredAt;
        this.humidityRh = humidityRh;
        this.humidityMeasuredAt = humidityMeasuredAt;
        this.eco2Ppm = eco2Ppm;
        this.eco2MeasuredAt = eco2MeasuredAt;
    }
}
