package com.opensrc.cet.data;

import java.time.Instant;
import java.util.Objects;

/**
 * 지도 마커 1개 (계약서 §03). 공용 API 1 응답을 센서별 최신 행으로 고른 결과에
 * getShadowSites 의 음영 여부를 겹친다. 음영 정보를 아직 모르면 isShadow = null.
 */
public final class MapMarker {
    public final int sensorNo;
    public final double latitude;
    public final double longitude;
    /** 31·32 는 원시값과 관계없이 null (화면 --) */
    public final Double temperatureC;
    public final int co2Ppm;
    public final Instant lastSeenAt;
    public final boolean fresh;
    /** direct 또는 mobile */
    public final String mode;
    public final Boolean isShadow;

    public MapMarker(int sensorNo, double latitude, double longitude, Double temperatureC,
                     int co2Ppm, Instant lastSeenAt, boolean fresh, String mode, Boolean isShadow) {
        this.sensorNo = sensorNo;
        this.latitude = latitude;
        this.longitude = longitude;
        this.temperatureC = temperatureC;
        this.co2Ppm = co2Ppm;
        this.lastSeenAt = Objects.requireNonNull(lastSeenAt, "lastSeenAt");
        this.fresh = fresh;
        this.mode = Objects.requireNonNull(mode, "mode");
        this.isShadow = isShadow;
    }
}
