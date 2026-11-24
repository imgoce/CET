package com.opensrc.cet.analysis;

import org.junit.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

public class CollectionRulesTest {
    private final Instant start = Instant.parse("2026-11-23T03:00:00Z");
    private final UUID window = UUID.randomUUID();
    private final ShadowSiteSelector initial = new ShadowSiteSelector(Collections.emptyMap());
    private final TestHistory history = new TestHistory();

    private CollectionRules.Decision evaluate(int sensor, int candidate, ShadowSiteSelector before) {
        return CollectionRules.evaluate(sensor, start, start.plusSeconds(1), window, start,
                candidate, history, before, 4, 4);
    }

    @Test
    public void ranksAllSitesIncludingZerosAndFreezesPreCommitClassification() {
        assertEquals(34, initial.counts().size());
        assertEquals(Arrays.asList(1, 2, 3, 4, 5), initial.shadowSites());
        CollectionRules.Decision result = evaluate(5, 5, initial);
        assertTrue(result.isShadowBefore);
        assertTrue(result.credited);
        assertTrue(result.quotaReached);
        assertEquals(0, result.shadowRemainder);
        assertEquals(4, result.normalRemainder);
        assertEquals(Long.valueOf(1), result.after.counts().get(5));
        assertEquals(Arrays.asList(1, 2, 3, 4, 6), result.after.shadowSites());
        assertEquals(Long.valueOf(0), initial.counts().get(5));
    }

    @Test
    public void validNonCandidateStillAddsGlobalSampleIncludingFaultyTemperatureSites() {
        for (int number : Arrays.asList(31, 32)) {
            CollectionRules.Decision result = evaluate(number, 7, initial);
            assertTrue(result.measurementAdded);
            assertEquals(Long.valueOf(1), result.after.counts().get(number));
            assertFalse(result.credited);
            assertFalse(result.quotaReached);
            assertEquals(4, result.normalRemainder);
        }
    }

    @Test
    public void globalDuplicateCanBeNewPersonalCredit() {
        ShadowSiteSelector before = new ShadowSiteSelector(Collections.singletonMap(31, 8L));
        history.global = true;
        CollectionRules.Decision result = evaluate(31, 31, before);
        assertFalse(result.measurementAdded);
        assertSame(before, result.after);
        assertTrue(result.credited);
        assertTrue(result.quotaReached);
        assertEquals(0, result.normalRemainder);
        assertEquals(4, result.shadowRemainder);
        history.personal = true;
        assertFalse(evaluate(31, 31, before).credited);
    }

    @Test
    public void creditedWindowBlocksSecondCreditEvenForDifferentMeasurement() {
        history.usedWindow = true;
        CollectionRules.Decision result = evaluate(8, 8, initial);
        assertFalse(result.credited);
        assertTrue(result.measurementAdded);
    }

    @Test
    public void overlappingWindowsBlockCreditButTouchingWindowsAreAllowed() {
        for (Instant prior : Arrays.asList(start.minusSeconds(5), start, start.plusSeconds(10).minusNanos(1))) {
            history.starts = Collections.singletonList(prior);
            CollectionRules.Decision result = evaluate(7, 7, initial);
            assertFalse(result.credited);
            assertTrue(result.measurementAdded);
        }
        history.starts = Arrays.asList(start.minusSeconds(10), start.plusSeconds(10));
        assertTrue(evaluate(7, 7, initial).credited);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnregisteredSensor() {
        evaluate(35, 7, initial);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsReceiptOnExclusiveEnd() {
        CollectionRules.evaluate(1, start, start.plusSeconds(10), window, start, 1,
                history, initial, 0, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidPersistedCounts() {
        new ShadowSiteSelector(Collections.singletonMap(2, -1L));
    }

    private static final class TestHistory implements CollectionRules.History {
        boolean global;
        boolean personal;
        boolean usedWindow;
        List<Instant> starts = Collections.emptyList();

        @Override public boolean measurementExists(int sensorNo, Instant measuredAt) { return global; }
        @Override public boolean creditedMeasurement(int sensorNo, Instant measuredAt) { return personal; }
        @Override public boolean creditedWindow(UUID windowId) { return usedWindow; }
        @Override public Iterable<Instant> nearbyCreditedWindowStarts(Instant start) { return starts; }
    }
}
