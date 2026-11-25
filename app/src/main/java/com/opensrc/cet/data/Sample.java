package com.opensrc.cet.data;

import java.time.Instant;
import java.util.Objects;

/** 집계용 환경 표본 (계약서 §12). 센서·측정 시각별 고유. */
public final class Sample {
    public final int sensorNo;
    public final Instant measuredAt;
    public final Double temperatureC;
    public final Double humidityRh;

    public Sample(int sensorNo, Instant measuredAt, Double temperatureC, Double humidityRh) {
        this.sensorNo = sensorNo;
        this.measuredAt = Objects.requireNonNull(measuredAt, "measuredAt");
        this.temperatureC = temperatureC;
        this.humidityRh = humidityRh;
    }
}
