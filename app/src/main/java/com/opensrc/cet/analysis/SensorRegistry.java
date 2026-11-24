package com.opensrc.cet.analysis;

import java.util.Locale;
import java.util.OptionalInt;

public final class SensorRegistry {
    private static final int[] COURSE_SENSOR_NUMBERS = {7, 15, 31, 32, 33};

    private SensorRegistry() {
    }

    public static OptionalInt fromBleName(String name) {
        for (int i = 0; i < COURSE_SENSOR_NUMBERS.length; i++) {
            if (("opensrc" + (i + 1)).equals(name)) {
                return OptionalInt.of(COURSE_SENSOR_NUMBERS[i]);
            }
        }
        if (name == null || name.length() != 9 || !name.startsWith("sensor ")) {
            return OptionalInt.empty();
        }
        char tens = name.charAt(7);
        char ones = name.charAt(8);
        if (tens < '0' || tens > '9' || ones < '0' || ones > '9') {
            return OptionalInt.empty();
        }
        int sensorNo = (tens - '0') * 10 + (ones - '0');
        return isRegistered(sensorNo) && !isCourseSensor(sensorNo)
                ? OptionalInt.of(sensorNo) : OptionalInt.empty();
    }

    public static boolean isRegistered(int sensorNo) {
        return sensorNo >= 1 && sensorNo <= 34;
    }

    public static boolean isCourseSensor(int sensorNo) {
        for (int number : COURSE_SENSOR_NUMBERS) {
            if (number == sensorNo) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasUnavailableTemperatureAndHumidity(int sensorNo) {
        return sensorNo == 31 || sensorNo == 32;
    }

    public static String toPublicName(int sensorNo) {
        if (!isRegistered(sensorNo)) {
            throw new IllegalArgumentException("Unregistered sensor number: " + sensorNo);
        }
        return String.format(Locale.ROOT, "sensor %02d", sensorNo);
    }
}
