package com.opensrc.cet.analysis;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Function;

public final class MapReadingSelector {
    private MapReadingSelector() {
    }

    /** Selects original rows by registered sensor number using the API client's parsed time. */
    public static <T> List<T> selectLatest(Iterable<T> rows, Function<T, Integer> sensorNo,
            Function<T, Instant> measuredAt, Function<T, String> mode) {
        TreeMap<Integer, T> latest = new TreeMap<>();
        for (T row : rows) {
            Integer number = sensorNo.apply(row);
            Instant time = measuredAt.apply(row);
            if (number == null || !SensorRegistry.isRegistered(number) || time == null) {
                continue;
            }
            T previous = latest.get(number);
            if (previous == null) {
                latest.put(number, row);
                continue;
            }
            int order = time.compareTo(measuredAt.apply(previous));
            if (order > 0 || (order == 0 && "mobile".equals(mode.apply(row))
                    && !"mobile".equals(mode.apply(previous)))) {
                latest.put(number, row);
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(latest.values()));
    }

    /** Applies the map display exception when converting the chosen row to a marker. */
    public static Double displayTemperature(int sensorNo, Double rawTemperatureC) {
        return SensorRegistry.hasUnavailableTemperatureAndHumidity(sensorNo) ? null : rawTemperatureC;
    }
}
