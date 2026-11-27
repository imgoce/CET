package com.opensrc.cet.analysis;

import java.time.Instant;

/** Keeps the latest valid value and timestamp of each metric for one selected site. */
public final class SiteEnvironmentRules {
    public enum TemperatureState { COLD, NORMAL, HOT, MISSING }
    public enum HumidityState { DRY, NORMAL, HUMID, MISSING }

    @FunctionalInterface
    public interface EnvironmentFactory<T> {
        T create(int sensorNo, String siteName, Double temperatureC, Instant tempMeasuredAt,
                Double humidityRh, Instant humidityMeasuredAt, Integer eco2Ppm, Instant eco2MeasuredAt);
    }

    private final int selectedSensorNo;
    private final Latest<Double> temperature = new Latest<>();
    private final Latest<Double> humidity = new Latest<>();
    private final Latest<Integer> eco2 = new Latest<>();

    public SiteEnvironmentRules(int selectedSensorNo) {
        if (!SensorRegistry.isRegistered(selectedSensorNo)) {
            throw new IllegalArgumentException("Unregistered sensor number: " + selectedSensorNo);
        }
        this.selectedSensorNo = selectedSensorNo;
    }

    /** Accepts individual metric timestamps from the DB, or a common timestamp for a raw row. */
    public void include(int sensorNo, Double temperatureC, Instant tempMeasuredAt,
            Double humidityRh, Instant humidityMeasuredAt, Integer eco2Ppm, Instant eco2MeasuredAt) {
        if (sensorNo != selectedSensorNo) {
            return;
        }
        if (!SensorRegistry.hasUnavailableTemperatureAndHumidity(sensorNo)) {
            if (temperatureC != null && temperatureC >= -30 && temperatureC <= 50) {
                temperature.include(temperatureC, tempMeasuredAt);
            }
            if (SensorRegistry.isCourseSensor(sensorNo)
                    && humidityRh != null && humidityRh >= 0 && humidityRh <= 100) {
                humidity.include(humidityRh, humidityMeasuredAt);
            }
        }
        if (SensorRegistry.isCourseSensor(sensorNo) && eco2Ppm != null
                && eco2Ppm >= 400 && eco2Ppm <= 5000) {
            eco2.include(eco2Ppm, eco2MeasuredAt);
        }
    }

    public <T> T toEnvironment(String siteName, EnvironmentFactory<T> factory) {
        String name = siteName == null || siteName.trim().isEmpty()
                ? SensorRegistry.toPublicName(selectedSensorNo) : siteName;
        return factory.create(selectedSensorNo, name, temperature.value, temperature.measuredAt,
                humidity.value, humidity.measuredAt, eco2.value, eco2.measuredAt);
    }

    public TemperatureState temperatureState() {
        Double value = temperature.value;
        if (value == null) {
            return TemperatureState.MISSING;
        }
        if (value <= 10) {
            return TemperatureState.COLD;
        }
        return value >= 28 ? TemperatureState.HOT : TemperatureState.NORMAL;
    }

    public HumidityState humidityState() {
        Double value = humidity.value;
        if (value == null) {
            return HumidityState.MISSING;
        }
        if (value <= 30) {
            return HumidityState.DRY;
        }
        return value >= 65 ? HumidityState.HUMID : HumidityState.NORMAL;
    }

    private static final class Latest<T> {
        private T value;
        private Instant measuredAt;

        private void include(T incoming, Instant time) {
            if (time != null && (measuredAt == null || time.isAfter(measuredAt))) {
                value = incoming;
                measuredAt = time;
            }
        }
    }
}
