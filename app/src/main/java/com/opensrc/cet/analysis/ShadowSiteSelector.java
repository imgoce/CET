package com.opensrc.cet.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable project-wide counts of unique, valid production measurements. */
public final class ShadowSiteSelector {
    private final Map<Integer, Long> counts;
    private final List<Integer> shadowSites;

    /** Missing registered sites start at zero. Supply counts from before the current commit. */
    public ShadowSiteSelector(Map<Integer, Long> storedCounts) {
        Map<Integer, Long> all = new HashMap<>();
        for (int number = 1; SensorRegistry.isRegistered(number); number++) {
            all.put(number, 0L);
        }
        for (Map.Entry<Integer, Long> entry : storedCounts.entrySet()) {
            long count = Objects.requireNonNull(entry.getValue());
            if (!all.containsKey(entry.getKey()) || count < 0) {
                throw new IllegalArgumentException("Invalid site count");
            }
            all.put(entry.getKey(), count);
        }
        counts = Collections.unmodifiableMap(all);
        List<Integer> ranked = new ArrayList<>(all.keySet());
        ranked.sort(Comparator.<Integer>comparingLong(all::get).thenComparingInt(n -> n));
        shadowSites = Collections.unmodifiableList(new ArrayList<>(ranked.subList(0, 5)));
    }

    public Map<Integer, Long> counts() {
        return counts;
    }

    public List<Integer> shadowSites() {
        return shadowSites;
    }

    ShadowSiteSelector withNewMeasurement(int sensorNo) {
        Map<Integer, Long> updated = new HashMap<>(counts);
        updated.put(sensorNo, Math.addExact(counts.get(sensorNo), 1L));
        return new ShadowSiteSelector(updated);
    }
}
