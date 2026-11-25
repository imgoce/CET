package com.opensrc.cet.data;

import java.time.Instant;
import java.util.Objects;

/**
 * BLE 광고를 정규화한 측정 1건 (계약서 §04).
 * ① 수신 → ④ 정규화 → ③ 저장·공용 전송, 화면이 공유한다. 값이 없으면 null.
 */
public final class Measurement {
    public final int sensorNo;
    public final String sensorMac;
    public final Instant measuredAt;
    public final Instant collectedAt;
    public final int rssiDbm;
    public final Double temperatureC;
    public final Double humidityRh;
    public final Integer co2Ppm;
    public final Integer eco2Ppm;
    public final Integer aqi;
    public final Integer tvocPpb;

    public Measurement(int sensorNo, String sensorMac, Instant measuredAt, Instant collectedAt,
                       int rssiDbm, Double temperatureC, Double humidityRh,
                       Integer co2Ppm, Integer eco2Ppm, Integer aqi, Integer tvocPpb) {
        this.sensorNo = sensorNo;
        this.sensorMac = Objects.requireNonNull(sensorMac, "sensorMac");
        this.measuredAt = Objects.requireNonNull(measuredAt, "measuredAt");
        this.collectedAt = Objects.requireNonNull(collectedAt, "collectedAt");
        this.rssiDbm = rssiDbm;
        this.temperatureC = temperatureC;
        this.humidityRh = humidityRh;
        this.co2Ppm = co2Ppm;
        this.eco2Ppm = eco2Ppm;
        this.aqi = aqi;
        this.tvocPpb = tvocPpb;
    }
}
