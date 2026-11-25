package com.opensrc.cet.publicapi;

import java.time.Instant;

/**
 * 공용 API 1 응답 한 줄 (계약서 §03). 서버가 준 값을 그대로 담는다.
 * 센서별 최신 행 선택(direct/mobile), 미사용 센서 제외, 31·32 온도 숨김은 ④ 전처리에서 한다.
 */
public final class PublicMapRow {
    /** 서버의 센서 이름 그대로 (예: "sensor 18", "pws01") */
    public final String sensorName;
    /** "sensor NN" 이면 1~34 번호, 그 밖(pws01·vs01·vs02 등)은 null */
    public final Integer sensorNo;
    public final double latitude;
    public final double longitude;
    /** ℃, 없으면 null */
    public final Double temperatureC;
    /** ppm, 없으면 null */
    public final Integer co2Ppm;
    /** 서버 문자열 그대로 (예: "Tue Nov 10 07:09:55 2026", KST) */
    public final String timeText;
    /** timeText 를 해석한 시각. 해석할 수 없으면 null */
    public final Instant measuredAt;
    public final boolean fresh;
    /** direct 또는 mobile */
    public final String mode;

    public PublicMapRow(String sensorName, Integer sensorNo, double latitude, double longitude,
                        Double temperatureC, Integer co2Ppm, String timeText, Instant measuredAt,
                        boolean fresh, String mode) {
        this.sensorName = sensorName;
        this.sensorNo = sensorNo;
        this.latitude = latitude;
        this.longitude = longitude;
        this.temperatureC = temperatureC;
        this.co2Ppm = co2Ppm;
        this.timeText = timeText;
        this.measuredAt = measuredAt;
        this.fresh = fresh;
        this.mode = mode;
    }
}
