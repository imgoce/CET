package com.opensrc.cet.analysis;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.ToIntFunction;

public final class CollectionWindowSelector<T> {
    static final int WINDOW_SECONDS = 10;

    private final ToIntFunction<T> sensorNo;
    private final Function<T, Instant> measuredAt;
    private final Function<T, Instant> collectedAt;
    private final ToIntFunction<T> rssiDbm;

    public CollectionWindowSelector(ToIntFunction<T> sensorNo, Function<T, Instant> measuredAt,
            Function<T, Instant> collectedAt, ToIntFunction<T> rssiDbm) {
        this.sensorNo = sensorNo;
        this.measuredAt = measuredAt;
        this.collectedAt = collectedAt;
        this.rssiDbm = rssiDbm;
    }

    /**
     * Selects from validated measurements. The caller supplies the session-aligned window start
     * and identifies measurements already handled for the current user by sensor and measuredAt.
     * A globally stored sample can still be new to this user. Keep the returned candidate
     * together with the window ID when dispatching or retrying the selected measurements.
     * An incomplete window is absent. A completed window can have no eligible measurements.
     */
    public Optional<Selection<T>> select(Instant windowStartAt, Instant closedAt,
            Iterable<T> measurements, BiPredicate<Integer, Instant> alreadyHandled) {
        Instant windowEndAt = windowStartAt.plusSeconds(WINDOW_SECONDS);
        if (closedAt.isBefore(windowEndAt)) {
            return Optional.empty();
        }

        TreeMap<Integer, T> latest = new TreeMap<>();
        for (T measurement : measurements) {
            Instant received = collectedAt.apply(measurement);
            if (received.isBefore(windowStartAt) || !received.isBefore(windowEndAt)) {
                continue;
            }
            int number = sensorNo.applyAsInt(measurement);
            Instant timestamp = measuredAt.apply(measurement);
            if (!alreadyHandled.test(number, timestamp)) {
                latest.merge(number, measurement, this::preferLatest);
            }
        }

        OptionalInt candidate = OptionalInt.empty();
        int strongestRssi = Integer.MIN_VALUE;
        for (T measurement : latest.values()) {
            int rssi = rssiDbm.applyAsInt(measurement);
            // Entries are in sensor-number order, so a tie keeps the smaller number.
            if (!candidate.isPresent() || rssi > strongestRssi) {
                candidate = OptionalInt.of(sensorNo.applyAsInt(measurement));
                strongestRssi = rssi;
            }
        }
        return Optional.of(new Selection<>(
                Collections.unmodifiableList(new ArrayList<>(latest.values())), candidate));
    }

    private T preferLatest(T previous, T incoming) {
        int order = measuredAt.apply(incoming).compareTo(measuredAt.apply(previous));
        if (order == 0) {
            return rssiDbm.applyAsInt(incoming) > rssiDbm.applyAsInt(previous)
                    ? incoming : previous;
        }
        return order > 0 ? incoming : previous;
    }

    public static final class Selection<T> {
        private final List<T> measurements;
        private final OptionalInt candidateSensorNo;

        private Selection(List<T> measurements, OptionalInt candidateSensorNo) {
            this.measurements = measurements;
            this.candidateSensorNo = candidateSensorNo;
        }

        public List<T> measurements() {
            return measurements;
        }

        public OptionalInt candidateSensorNo() {
            return candidateSensorNo;
        }
    }
}
