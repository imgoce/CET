package com.opensrc.cet.analysis;

import org.junit.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static com.opensrc.cet.analysis.FeedbackRules.ErrorCode.*;
import static org.junit.Assert.*;

public class FeedbackRulesTest {
    private static final Instant MIDNIGHT_KST = Instant.parse("2026-11-23T15:00:00Z");
    private static final LocalDate PREVIOUS_DAY = LocalDate.of(2026, 11, 23);
    private static final LocalDate NEW_DAY = PREVIOUS_DAY.plusDays(1);
    private static final UUID USER = UUID.randomUUID();
    private static final UUID COLLECTION = UUID.randomUUID();
    private static final UUID FEEDBACK = UUID.randomUUID();
    private static final List<String> NONE = Collections.singletonList("NONE");
    private final TestHistory history = new TestHistory();

    @Test
    public void acceptsValidFeedbackAndPreservesNonBlankMemo() {
        FeedbackRules.Decision result = evaluate(history.collectedAt, "HOT", "HUMID",
                Arrays.asList("NO_SHADE", "STRONG_WIND", "ODOR", "NO_SEAT", "OTHER"), " 메모 ");
        assertTrue(result.accepted());
        assertNull(result.errorCode);
        assertEquals(" 메모 ", result.normalizedMemo);
    }

    @Test
    public void rejectsForeignUncreditedTestAndMissingRecords() {
        history.owner = UUID.randomUUID();
        assertEquals(NOT_ELIGIBLE, submit(MIDNIGHT_KST).errorCode);
        history.owner = USER;
        history.credited = false;
        assertEquals(NOT_ELIGIBLE, submit(MIDNIGHT_KST).errorCode);
        history.credited = true;
        history.production = false;
        assertEquals(NOT_ELIGIBLE, submit(MIDNIGHT_KST).errorCode);
        history.production = true;
        assertEquals(NOT_ELIGIBLE, FeedbackRules.evaluate(FEEDBACK, UUID.randomUUID(), USER,
                "OK", "OK", NONE, null, MIDNIGHT_KST, history).errorCode);
    }

    @Test
    public void rejectsRecordWithoutOnSiteCollectionTime() {
        history.collectedAt = null;
        assertEquals(NOT_ELIGIBLE, submit(MIDNIGHT_KST).errorCode);
    }

    @Test
    public void accepts600SecondsExactlyAndRejectsOutsideWindow() {
        Instant boundary = history.collectedAt.plusSeconds(600);
        assertTrue(submit(boundary).accepted());
        assertEquals(FEEDBACK_EXPIRED, submit(boundary.plusNanos(1)).errorCode);
        assertEquals(FEEDBACK_EXPIRED, submit(boundary.plusSeconds(1)).errorCode);
        assertEquals(FEEDBACK_EXPIRED, submit(history.collectedAt.minusNanos(1)).errorCode);
    }

    @Test
    public void rejectsExistingFeedbackBeforeCheckingDailyLimit() {
        history.feedbackExists = true;
        history.previousDayCount = 3;
        assertEquals(FEEDBACK_EXISTS, submit(MIDNIGHT_KST.minusSeconds(1)).errorCode);
    }

    @Test
    public void capsSubmissionsAtThreeAndChangesBucketAtKstMidnight() {
        history.previousDayCount = 2;
        assertTrue(submit(MIDNIGHT_KST.minusNanos(1)).accepted());
        history.previousDayCount = 3;
        assertEquals(DAILY_LIMIT, submit(MIDNIGHT_KST.minusNanos(1)).errorCode);
        assertTrue(submit(MIDNIGHT_KST).accepted());
        history.newDayCount = 3;
        assertEquals(DAILY_LIMIT, submit(MIDNIGHT_KST).errorCode);
    }

    @Test
    public void rejectsInvalidFeelingAndIssueInputs() {
        assertInvalid("DRY", "OK", NONE);
        assertInvalid("OK", "HOT", NONE);
        assertInvalid(null, "OK", NONE);
        assertInvalid("OK", null, NONE);
        assertInvalid("OK", "OK", null);
        assertInvalid("OK", "OK", Collections.emptyList());
        assertInvalid("OK", "OK", Collections.singletonList(null));
        assertInvalid("OK", "OK", Collections.singletonList("UNKNOWN"));
        assertInvalid("OK", "OK", Arrays.asList("ODOR", "ODOR"));
    }

    @Test
    public void acceptsNoneOnlyAsSingleIssue() {
        assertTrue(submit(MIDNIGHT_KST).accepted());
        assertInvalid("OK", "OK", Arrays.asList("NONE", "ODOR"));
        assertInvalid("OK", "OK", Arrays.asList("NONE", "NONE"));
    }

    @Test
    public void countsUnicodeCodePointsAndNormalizesBlankMemo() {
        StringBuilder memo = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            memo.appendCodePoint(0x1F600);
        }
        String atLimit = memo.toString();
        assertEquals(atLimit, evaluate(MIDNIGHT_KST, "OK", "OK", NONE, atLimit).normalizedMemo);
        assertEquals(INVALID_INPUT,
                evaluate(MIDNIGHT_KST, "OK", "OK", NONE, atLimit + "가").errorCode);
        for (String blank : Arrays.asList(null, "", " \t\r\n\u00a0\u3000")) {
            FeedbackRules.Decision result = evaluate(MIDNIGHT_KST, "OK", "OK", NONE, blank);
            assertTrue(result.accepted());
            assertNull(result.normalizedMemo);
        }
    }

    private void assertInvalid(String temperature, String humidity, List<String> issues) {
        assertEquals(INVALID_INPUT,
                evaluate(MIDNIGHT_KST, temperature, humidity, issues, null).errorCode);
    }

    private FeedbackRules.Decision submit(Instant submittedAt) {
        return evaluate(submittedAt, "OK", "OK", NONE, null);
    }

    private FeedbackRules.Decision evaluate(Instant submittedAt, String temperature,
            String humidity, List<String> issues, String memo) {
        return FeedbackRules.evaluate(FEEDBACK, COLLECTION, USER, temperature, humidity,
                issues, memo, submittedAt, history);
    }

    private static final class TestHistory implements FeedbackRules.History, FeedbackRules.CollectionRecord {
        private Instant collectedAt = MIDNIGHT_KST.minusSeconds(30);
        private UUID owner = USER;
        private boolean credited = true;
        private boolean production = true;
        private boolean feedbackExists;
        private int previousDayCount;
        private int newDayCount;

        @Override
        public FeedbackRules.CollectionRecord findCollection(UUID collectionId) {
            return COLLECTION.equals(collectionId) ? this : null;
        }

        @Override
        public boolean feedbackExists(UUID collectionId) {
            assertEquals(COLLECTION, collectionId);
            return feedbackExists;
        }

        @Override
        public int acceptedFeedbackCount(UUID userId, LocalDate kstSubmissionDate) {
            assertEquals(USER, userId);
            if (PREVIOUS_DAY.equals(kstSubmissionDate)) {
                return previousDayCount;
            }
            assertEquals(NEW_DAY, kstSubmissionDate);
            return newDayCount;
        }

        @Override
        public UUID ownerId() {
            return owner;
        }

        @Override
        public boolean credited() {
            return credited;
        }

        @Override
        public boolean production() {
            return production;
        }

        @Override
        public Instant collectedAt() {
            return collectedAt;
        }
    }
}
