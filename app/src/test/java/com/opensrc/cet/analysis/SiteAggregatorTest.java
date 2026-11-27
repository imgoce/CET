package com.opensrc.cet.analysis;

import org.junit.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static com.opensrc.cet.analysis.SiteAggregator.Status.*;
import static org.junit.Assert.*;

public class SiteAggregatorTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 27);
    private enum Issue { OTHER, ODOR, STRONG_WIND, NO_SHADE, NONE }

    private final List<Sample> samples = new ArrayList<>();
    private final List<Response> feedbacks = new ArrayList<>();

    private List<SiteAggregator.SiteSlot<Issue>> aggregate() {
        return SiteAggregator.aggregateSites(TODAY, samples, feedbacks,
                (a, s) -> a.addSample(s.sensor, s.time, s.temp, s.humidity),
                (a, f) -> a.addFeedback(f.id, f.sensor, f.collectedAt, f.temp, f.humidity, f.issues));
    }

    private static Instant at(int daysAgo, int hour) {
        return TODAY.minusDays(daysAgo).atTime(hour, 0).toInstant(ZoneOffset.ofHours(9));
    }

    @Test
    public void returnsEveryRegisteredSlotInOrderIncludingEmptySlots() {
        List<SiteAggregator.SiteSlot<Issue>> slots = aggregate();
        assertEquals(136, slots.size());
        for (int i = 0; i < slots.size(); i++) {
            SiteAggregator.SiteSlot<Issue> slot = slots.get(i);
            assertEquals(1 + i / 4, slot.sensorNo);
            assertEquals(9 + 3 * (i % 4), slot.slotStartHour);
            assertEquals(INSUFFICIENT, slot.status);
            assertEquals(0, slot.responseN);
            assertNull(slot.discomfortRate);
            assertNull(slot.tempMedian);
            assertNull(slot.humidityMedian);
            assertTrue(slot.topIssues.isEmpty());
        }
    }

    @Test
    public void usesInclusiveFourteenKstDatesAndHalfOpenTimeSlots() {
        for (Instant time : Arrays.asList(at(14, 9), at(13, 9), at(0, 9).minusNanos(1),
                at(0, 12).minusNanos(1), at(0, 12), at(0, 15), at(0, 18),
                at(0, 21).minusNanos(1), at(0, 21), at(-1, 9))) {
            samples.add(new Sample(1, time, 20.0, null));
            feedbacks.add(new Response(1, time));
        }
        List<SiteAggregator.SiteSlot<Issue>> slots = aggregate();
        int[] counts = {2, 1, 1, 2};
        for (int i = 0; i < 4; i++) {
            assertEquals(counts[i], slots.get(i).tempN);
            assertEquals(counts[i], slots.get(i).responseN);
        }
    }

    @Test
    public void deduplicatesPerSensorAndCalculatesIndependentMedians() {
        Instant time = at(0, 9);
        Sample sample = new Sample(7, time, 20.0, null);
        samples.addAll(Arrays.asList(sample, sample, new Sample(7, time.plusSeconds(1), 24.0, 60.0),
                new Sample(7, time.plusSeconds(2), null, 0.0),
                new Sample(7, time.plusSeconds(3), Double.NaN, 100.0),
                new Sample(7, time.plusSeconds(4), 51.0, -1.0),
                new Sample(15, time, 0.0, null), new Sample(31, time, 0.0, 50.0),
                new Sample(32, time, 20.0, 50.0)));
        List<SiteAggregator.SiteSlot<Issue>> slots = aggregate();
        SiteAggregator.SiteSlot<Issue> slot = slots.get(24);
        assertEquals(2, slot.tempN);
        assertEquals(22.0, slot.tempMedian, 0.0);
        assertEquals(3, slot.humidityN);
        assertEquals(60.0, slot.humidityMedian, 0.0);
        assertEquals(0.0, slots.get(56).tempMedian, 0.0);
        for (int index : Arrays.asList(120, 124)) {
            assertEquals(0, slots.get(index).tempN);
            assertEquals(0, slots.get(index).humidityN);
            assertNull(slots.get(index).tempMedian);
            assertNull(slots.get(index).humidityMedian);
        }
    }

    @Test
    public void classifiesUsingResponseCountFractionAndDistinctDiscomfortDays() {
        for (int i = 0; i < 9; i++) {
            Response row = new Response(31, at(i % 2, 9));
            row.temp = "HOT";
            feedbacks.add(row);
        }
        assertEquals(INSUFFICIENT, aggregate().get(120).status);
        for (int i = 5; i < 9; i++) {
            feedbacks.get(i).temp = "OK";
        }
        feedbacks.add(new Response(31, at(3, 9)));
        SiteAggregator.SiteSlot<Issue> twoDays = aggregate().get(120);
        assertEquals(10, twoDays.responseN);
        assertEquals(5, twoDays.uncomfortableN);
        assertEquals(0.5, twoDays.discomfortRate, 0.0);
        assertEquals(2, twoDays.distinctDiscomfortDays);
        assertEquals(NORMAL, twoDays.status);
        feedbacks.get(4).collectedAt = at(2, 9);
        assertEquals(CANDIDATE, aggregate().get(120).status);
        feedbacks.get(0).temp = "OK";
        assertEquals(NORMAL, aggregate().get(120).status);
    }

    @Test
    public void groupsByLinkedCollectionEvenIfSubmittedInAnotherSlotOrDate() {
        Response noon = new Response(1, at(0, 12).minusSeconds(1));
        noon.submittedAt = at(0, 12);
        Response midnight = new Response(1, at(0, 0).minusSeconds(1));
        midnight.submittedAt = at(0, 0);
        feedbacks.addAll(Arrays.asList(noon, midnight));
        List<SiteAggregator.SiteSlot<Issue>> slots = aggregate();
        assertTrue(noon.submittedAt.isAfter(noon.collectedAt));
        assertTrue(midnight.submittedAt.isAfter(midnight.collectedAt));
        assertEquals(1, slots.get(0).responseN);
        assertEquals(1, slots.stream().mapToInt(slot -> slot.responseN).sum());
        assertEquals(0.0, slots.get(0).discomfortRate, 0.0);
    }

    @Test
    public void ranksTopThreeAndCountsEachValidResponseOnce() {
        Response multiple = new Response(7, at(0, 9));
        multiple.issues = Arrays.asList(Issue.OTHER, Issue.ODOR, Issue.STRONG_WIND, Issue.NO_SHADE);
        Response odor = new Response(7, at(0, 9));
        odor.issues = Collections.singletonList(Issue.ODOR);
        Response humid = new Response(7, at(0, 9));
        humid.humidity = "HUMID";
        Response invalid = new Response(7, at(0, 9));
        invalid.issues = Arrays.asList(Issue.NONE, Issue.OTHER);
        feedbacks.addAll(Arrays.asList(multiple, multiple, odor, humid, invalid));
        SiteAggregator.SiteSlot<Issue> slot = aggregate().get(24);
        assertEquals(3, slot.responseN);
        assertEquals(3, slot.uncomfortableN);
        assertEquals(3, slot.topIssues.size());
        assertEquals(Issue.ODOR, slot.topIssues.get(0).issue);
        assertEquals(2, slot.topIssues.get(0).count);
        assertEquals(Issue.NO_SHADE, slot.topIssues.get(1).issue);
        assertEquals(Issue.STRONG_WIND, slot.topIssues.get(2).issue);
    }

    private static final class Sample {
        final int sensor;
        final Instant time;
        final Double temp, humidity;

        Sample(int sensor, Instant time, Double temp, Double humidity) {
            this.sensor = sensor;
            this.time = time;
            this.temp = temp;
            this.humidity = humidity;
        }
    }

    private static final class Response {
        final UUID id = UUID.randomUUID();
        final int sensor;
        Instant collectedAt, submittedAt;
        String temp = "OK";
        String humidity = "OK";
        List<Issue> issues = Collections.singletonList(Issue.NONE);

        Response(int sensor, Instant collectedAt) {
            this.sensor = sensor;
            this.collectedAt = collectedAt;
        }
    }
}
