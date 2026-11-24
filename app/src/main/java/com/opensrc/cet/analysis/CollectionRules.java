package com.opensrc.cet.analysis;

import java.time.Instant;
import java.util.UUID;

/** Pure rules for a new production request after BLE validation and successful routing.
 * The DB adapter supplies one consistent pre-commit snapshot and handles request replay first.
 */
public final class CollectionRules {
    private CollectionRules() {
    }

    /** Except for the global measurement lookup, queries are scoped to the submitting user. */
    public interface History {
        boolean measurementExists(int sensorNo, Instant measuredAt);
        boolean creditedMeasurement(int sensorNo, Instant measuredAt);
        boolean creditedWindow(UUID windowId);
        Iterable<Instant> nearbyCreditedWindowStarts(Instant windowStartAt);
    }

    public static Decision evaluate(int sensorNo, Instant measuredAt, Instant collectedAt,
            UUID windowId, Instant windowStartAt, int candidateSensorNo, History history,
            ShadowSiteSelector before, int normalRemainder, int shadowRemainder) {
        if (!SensorRegistry.isRegistered(sensorNo) || !SensorRegistry.isRegistered(candidateSensorNo)
                || windowId == null || measuredAt == null || collectedAt == null || windowStartAt == null
                || collectedAt.isBefore(windowStartAt)
                || !collectedAt.isBefore(windowStartAt.plusSeconds(CollectionWindowSelector.WINDOW_SECONDS))
                || normalRemainder < 0 || normalRemainder >= 5 || shadowRemainder < 0 || shadowRemainder >= 5) {
            throw new IllegalArgumentException("Invalid collection or remainder");
        }
        boolean added = !history.measurementExists(sensorNo, measuredAt);
        boolean shadow = before.shadowSites().contains(sensorNo);
        boolean credited = sensorNo == candidateSensorNo && !history.creditedWindow(windowId)
                && !history.creditedMeasurement(sensorNo, measuredAt)
                && !overlaps(windowStartAt, history.nearbyCreditedWindowStarts(windowStartAt));
        if (credited) {
            if (shadow) {
                shadowRemainder++;
            } else {
                normalRemainder++;
            }
        }
        return new Decision(added, credited, shadow, normalRemainder, shadowRemainder,
                added ? before.withNewMeasurement(sensorNo) : before);
    }

    private static boolean overlaps(Instant start, Iterable<Instant> priorStarts) {
        Instant lower = start.minusSeconds(CollectionWindowSelector.WINDOW_SECONDS);
        Instant upper = start.plusSeconds(CollectionWindowSelector.WINDOW_SECONDS);
        for (Instant prior : priorStarts) {
            if (prior.isAfter(lower) && prior.isBefore(upper)) {
                return true;
            }
        }
        return false;
    }

    /** Local calculation result used by the storage and reward adapters. */
    public static final class Decision {
        public final boolean measurementAdded;
        public final boolean credited;
        public final boolean isShadowBefore;
        public final boolean quotaReached;
        public final int normalRemainder;
        public final int shadowRemainder;
        public final ShadowSiteSelector after;

        private Decision(boolean added, boolean credited, boolean shadow, int normalCount,
                int shadowCount, ShadowSiteSelector after) {
            this.measurementAdded = added;
            this.credited = credited;
            this.isShadowBefore = shadow;
            this.quotaReached = normalCount == 5 || shadowCount == 5;
            this.normalRemainder = normalCount % 5;
            this.shadowRemainder = shadowCount % 5;
            this.after = after;
        }
    }
}
