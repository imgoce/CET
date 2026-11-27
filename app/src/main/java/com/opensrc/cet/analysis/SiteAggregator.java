package com.opensrc.cet.analysis;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/** Aggregates successful getAnalysisInputs results. Adapters read the team's input DTOs. */
public final class SiteAggregator<I extends Enum<I>> {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int SLOT_COUNT = 4;
    private final LocalDate firstDate;
    private final LocalDate lastDate;
    private final List<Bucket<I>> buckets = new ArrayList<>();
    private final Set<UUID> feedbackIds = new HashSet<>();

    private SiteAggregator(LocalDate asOfDate) {
        firstDate = asOfDate.minusDays(13);
        lastDate = asOfDate;
        for (int sensor = 1; SensorRegistry.isRegistered(sensor); sensor++) {
            for (int slot = 0; slot < SLOT_COUNT; slot++) {
                buckets.add(new Bucket<>());
            }
        }
    }

    public static <S, F, I extends Enum<I>> List<SiteSlot<I>> aggregateSites(LocalDate asOfDate,
            Iterable<S> samples, Iterable<F> feedbacks,
            BiConsumer<SiteAggregator<I>, S> readSample, BiConsumer<SiteAggregator<I>, F> readFeedback) {
        SiteAggregator<I> aggregation = new SiteAggregator<>(asOfDate);
        for (S row : samples) {
            readSample.accept(aggregation, row);
        }
        for (F row : feedbacks) {
            readFeedback.accept(aggregation, row);
        }
        List<SiteSlot<I>> results = new ArrayList<>();
        for (int i = 0; i < aggregation.buckets.size(); i++) {
            results.add(new SiteSlot<>(1 + i / SLOT_COUNT, 9 + 3 * (i % SLOT_COUNT),
                    aggregation.buckets.get(i)));
        }
        return Collections.unmodifiableList(results);
    }

    private Bucket<I> bucket(int sensor, ZonedDateTime local) {
        LocalDate day = local.toLocalDate();
        int hour = local.getHour();
        if (!SensorRegistry.isRegistered(sensor) || day.isBefore(firstDate) || day.isAfter(lastDate)
                || hour < 9 || hour >= 21) {
            return null;
        }
        return buckets.get((sensor - 1) * SLOT_COUNT + (hour - 9) / 3);
    }

    public void addSample(int sensor, Instant measuredAt, Double temperatureC, Double humidityRh) {
        if (measuredAt == null || SensorRegistry.hasUnavailableTemperatureAndHumidity(sensor)) {
            return;
        }
        Bucket<I> bucket = bucket(sensor, measuredAt.atZone(KST));
        if (bucket == null || !bucket.measuredTimes.add(measuredAt)) {
            return;
        }
        if (temperatureC != null && temperatureC >= -30 && temperatureC <= 50) {
            bucket.temperatures.add(temperatureC);
        }
        if (SensorRegistry.isCourseSensor(sensor) && humidityRh != null && humidityRh >= 0 && humidityRh <= 100) {
            bucket.humidities.add(humidityRh);
        }
    }

    /** Inputs are accepted operational feedback, already validated at submission. */
    public void addFeedback(UUID feedbackId, int sensor, Instant collectedAt,
            String feltTemp, String feltHumidity, List<I> issues) {
        if (collectedAt == null || feedbackId == null || issues == null) {
            return;
        }
        ZonedDateTime local = collectedAt.atZone(KST);
        Bucket<I> bucket = bucket(sensor, local);
        if (bucket == null) {
            return;
        }
        List<String> names = new ArrayList<>(issues.size());
        for (I issue : issues) {
            names.add(issue == null ? null : issue.name());
        }
        if (!FeedbackRules.validAnswers(feltTemp, feltHumidity, names) || !feedbackIds.add(feedbackId)) {
            return;
        }
        bucket.responses++;
        boolean uncomfortable = !"OK".equals(feltTemp) || !"OK".equals(feltHumidity);
        for (I issue : issues) {
            if (!"NONE".equals(issue.name())) {
                bucket.issueCounts.merge(issue, 1, Integer::sum);
                uncomfortable = true;
            }
        }
        if (uncomfortable) {
            bucket.uncomfortable++;
            bucket.days.add(local.toLocalDate());
        }
    }

    private static final class Bucket<I> {
        final Set<Instant> measuredTimes = new HashSet<>();
        final List<Double> temperatures = new ArrayList<>();
        final List<Double> humidities = new ArrayList<>();
        final Set<LocalDate> days = new HashSet<>();
        final Map<I, Integer> issueCounts = new HashMap<>();
        int responses;
        int uncomfortable;
    }

    private static Double median(List<Double> values) {
        if (values.isEmpty()) {
            return null;
        }
        Collections.sort(values);
        int middle = values.size() / 2;
        return values.size() % 2 == 1 ? values.get(middle)
                : (values.get(middle - 1) + values.get(middle)) / 2;
    }

    public enum Status { INSUFFICIENT, NORMAL, CANDIDATE }

    public static final class IssueCount<I> {
        public final I issue;
        public final int count;

        private IssueCount(I issue, int count) {
            this.issue = issue;
            this.count = count;
        }
    }

    public static final class SiteSlot<I extends Enum<I>> {
        public final int sensorNo, slotStartHour, tempN, humidityN, responseN, uncomfortableN, distinctDiscomfortDays;
        public final Double tempMedian, humidityMedian, discomfortRate;
        public final Status status;
        public final List<IssueCount<I>> topIssues;

        private SiteSlot(int sensor, int hour, Bucket<I> bucket) {
            sensorNo = sensor;
            slotStartHour = hour;
            tempN = bucket.temperatures.size();
            humidityN = bucket.humidities.size();
            tempMedian = median(bucket.temperatures);
            humidityMedian = median(bucket.humidities);
            responseN = bucket.responses;
            uncomfortableN = bucket.uncomfortable;
            distinctDiscomfortDays = bucket.days.size();
            discomfortRate = responseN == 0 ? null : (double) uncomfortableN / responseN;
            if (responseN < 10) {
                status = Status.INSUFFICIENT;
            } else if (discomfortRate >= 0.5 && distinctDiscomfortDays >= 3) {
                status = Status.CANDIDATE;
            } else {
                status = Status.NORMAL;
            }

            List<IssueCount<I>> ranked = new ArrayList<>();
            bucket.issueCounts.forEach((issue, count) -> ranked.add(new IssueCount<>(issue, count)));
            ranked.sort((a, b) -> {
                int byCount = Integer.compare(b.count, a.count);
                return byCount != 0 ? byCount : Integer.compare(
                        FeedbackRules.issueOrder(a.issue.name()), FeedbackRules.issueOrder(b.issue.name()));
            });
            topIssues = Collections.unmodifiableList(ranked.subList(0, Math.min(3, ranked.size())));
        }
    }
}
