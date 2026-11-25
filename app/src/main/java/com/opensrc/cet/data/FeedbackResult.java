package com.opensrc.cet.data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** submitFeedback 성공 결과 (계약서 §14). todayCount 는 원래 제출일(KST)의 성공 횟수. */
public final class FeedbackResult {
    public final UUID feedbackId;
    public final Instant submittedAt;
    public final int todayCount;
    public final List<Grant> grants;
    public final long foodBalance;
    public final boolean replayed;

    public FeedbackResult(UUID feedbackId, Instant submittedAt, int todayCount,
                          List<Grant> grants, long foodBalance, boolean replayed) {
        this.feedbackId = Objects.requireNonNull(feedbackId, "feedbackId");
        this.submittedAt = Objects.requireNonNull(submittedAt, "submittedAt");
        this.todayCount = todayCount;
        this.grants = Collections.unmodifiableList(new ArrayList<>(grants));
        this.foodBalance = foodBalance;
        this.replayed = replayed;
    }
}
