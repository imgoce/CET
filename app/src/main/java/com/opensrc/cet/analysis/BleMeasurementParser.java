package com.opensrc.cet.analysis;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

public final class BleMeasurementParser<T> {
    private static final int MIN_RSSI_DBM = -85;
    private static final int LAB_LENGTH = 8;
    private static final int COURSE_LENGTH = 13;

    // The integration layer supplies the shared Measurement constructor or adapter.
    @FunctionalInterface
    public interface MeasurementFactory<T> {
        T create(int sensorNo, String sensorMac, Instant measuredAt, Instant collectedAt,
                int rssiDbm, Double temperatureC, Double humidityRh, Integer co2Ppm,
                Integer eco2Ppm, Integer aqi, Integer tvocPpb);
    }

    private final MeasurementFactory<T> factory;

    public BleMeasurementParser(MeasurementFactory<T> factory) {
        this.factory = Objects.requireNonNull(factory);
    }

    /** Parses service data already extracted for BLE service 0x181A. */
    public Optional<T> parse(String localName, byte[] serviceData, String sensorMac,
            int rssiDbm, Instant collectedAt) {
        OptionalInt registered = SensorRegistry.fromBleName(localName);
        if (!registered.isPresent() || serviceData == null || sensorMac == null
                || sensorMac.trim().isEmpty() || collectedAt == null || rssiDbm < MIN_RSSI_DBM) {
            return Optional.empty();
        }

        int sensorNo = registered.getAsInt();
        ByteBuffer data = ByteBuffer.wrap(serviceData).order(ByteOrder.LITTLE_ENDIAN);
        if (SensorRegistry.isCourseSensor(sensorNo)) {
            return serviceData.length >= COURSE_LENGTH
                    ? parseCourse(sensorNo, data, sensorMac, rssiDbm, collectedAt)
                    : Optional.empty();
        }
        return serviceData.length == LAB_LENGTH
                ? parseLab(sensorNo, data, sensorMac, rssiDbm, collectedAt)
                : Optional.empty();
    }

    private Optional<T> parseLab(int sensorNo, ByteBuffer data, String mac, int rssi,
            Instant collectedAt) {
        int co2 = Short.toUnsignedInt(data.getShort());
        double temperature = data.getShort() / 100.0;
        Instant measuredAt = Instant.ofEpochSecond(Integer.toUnsignedLong(data.getInt()));
        if (!validCo2(co2) || !validTemperature(temperature)) {
            return Optional.empty();
        }
        return Optional.of(factory.create(sensorNo, mac, measuredAt, collectedAt, rssi,
                temperature, null, co2, null, null, null));
    }

    private Optional<T> parseCourse(int sensorNo, ByteBuffer data, String mac, int rssi,
            Instant collectedAt) {
        double temperature = data.getShort() / 100.0;
        double humidity = Short.toUnsignedInt(data.getShort()) / 100.0;
        int aqi = Byte.toUnsignedInt(data.get());
        int tvoc = Short.toUnsignedInt(data.getShort());
        int eco2 = Short.toUnsignedInt(data.getShort());
        Instant measuredAt = Instant.ofEpochSecond(Integer.toUnsignedLong(data.getInt()));
        boolean unavailable = SensorRegistry.hasUnavailableTemperatureAndHumidity(sensorNo);
        if (!validCo2(eco2) || (!unavailable && (!validTemperature(temperature)
                || humidity > 100))) {
            return Optional.empty();
        }
        return Optional.of(factory.create(sensorNo, mac, measuredAt, collectedAt, rssi,
                unavailable ? null : temperature, unavailable ? null : humidity,
                null, eco2, aqi, tvoc));
    }

    private static boolean validCo2(int ppm) {
        return ppm >= 400 && ppm <= 5000;
    }

    private static boolean validTemperature(double temperature) {
        return temperature >= -30 && temperature <= 50;
    }
}
