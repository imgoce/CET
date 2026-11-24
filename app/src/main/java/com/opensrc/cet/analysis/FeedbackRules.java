package com.opensrc.cet.analysis;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class FeedbackRules {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Duration MAX_DELAY = Duration.ofSeconds(600);
    private static final int DAILY_LIMIT = 3;
    private static final int MAX_MEMO_CODE_POINTS = 100;
    private static final List<String> TEMPERATURES = Arrays.asList("COLD", "OK", "HOT");
    private static final List<String> HUMIDITIES = Arrays.asList("DRY", "OK", "HUMID");
    private static final List<String> ISSUES = Arrays.asList(
            "NONE", "NO_SHADE", "STRONG_WIND", "ODOR", "NO_SEAT", "OTHER");

    private FeedbackRules() {
    }

    /** Read-only view of the linked collection, backed by the storage adapter. */
    public interface CollectionRecord {
        UUID ownerId();
        boolean credited();
        boolean production();
        Instant collectedAt();
    }

    /** Reads must use the same transaction snapshot as the eventual write. */
    public interface History {
        CollectionRecord findCollection(UUID collectionId);
        boolean feedbackExists(UUID collectionId);
        int acceptedFeedbackCount(UUID userId, LocalDate kstSubmissionDate);
    }

    public enum ErrorCode {
        INVALID_INPUT, NOT_ELIGIBLE, FEEDBACK_EXPIRED, FEEDBACK_EXISTS, DAILY_LIMIT
    }

    /**
     * Evaluates a new request after the storage adapter has checked UUID replay.
     * submittedAt is the original DB receipt time.
     */
    public static Decision evaluate(UUID feedbackId, UUID collectionId, UUID userId,
            String feltTemp, String feltHumidity, List<String> issues, String memo,
            Instant submittedAt, History history) {
        if (feedbackId == null || collectionId == null || userId == null || submittedAt == null
                || !TEMPERATURES.contains(feltTemp) || !HUMIDITIES.contains(feltHumidity)
                || !validIssues(issues)
                || (memo != null && memo.codePointCount(0, memo.length()) > MAX_MEMO_CODE_POINTS)) {
            return new Decision(ErrorCode.INVALID_INPUT, null);
        }

        CollectionRecord collection = history.findCollection(collectionId);
        if (collection == null || !userId.equals(collection.ownerId())
                || !collection.credited() || !collection.production()) {
            return new Decision(ErrorCode.NOT_ELIGIBLE, null);
        }

        Instant collectedAt = collection.collectedAt();
        if (collectedAt == null) {
            return new Decision(ErrorCode.NOT_ELIGIBLE, null);
        }
        Duration elapsed = Duration.between(collectedAt, submittedAt);
        if (elapsed.isNegative() || elapsed.compareTo(MAX_DELAY) > 0) {
            return new Decision(ErrorCode.FEEDBACK_EXPIRED, null);
        }
        if (history.feedbackExists(collectionId)) {
            return new Decision(ErrorCode.FEEDBACK_EXISTS, null);
        }
        LocalDate submissionDate = submittedAt.atZone(KST).toLocalDate();
        if (history.acceptedFeedbackCount(userId, submissionDate) >= DAILY_LIMIT) {
            return new Decision(ErrorCode.DAILY_LIMIT, null);
        }

        String normalizedMemo = memo;
        if (memo != null && memo.codePoints().allMatch(
                cp -> Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0x85)) {
            normalizedMemo = null;
        }
        return new Decision(null, normalizedMemo);
    }

    private static boolean validIssues(List<String> issues) {
        if (issues == null || issues.isEmpty()) {
            return false;
        }
        Set<String> seen = new HashSet<>();
        for (String issue : issues) {
            if (!ISSUES.contains(issue) || !seen.add(issue)) {
                return false;
            }
        }
        return !seen.contains("NONE") || seen.size() == 1;
    }

    public static final class Decision {
        public final ErrorCode errorCode;
        public final String normalizedMemo;

        private Decision(ErrorCode errorCode, String normalizedMemo) {
            this.errorCode = errorCode;
            this.normalizedMemo = normalizedMemo;
        }

        public boolean accepted() {
            return errorCode == null;
        }
    }
}
