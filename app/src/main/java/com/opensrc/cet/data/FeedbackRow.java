package com.opensrc.cet.data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 집계용 피드백 1건 (계약서 §12). 메모·사용자 식별값은 없다. */
public final class FeedbackRow {
    public final UUID feedbackId;
    public final int sensorNo;
    /** 연결된 수집의 현장 수신 시각. 날짜·시간대는 이 값으로 정한다 */
    public final Instant collectedAt;
    public final FeltTemp feltTemp;
    public final FeltHumidity feltHumidity;
    public final List<Issue> issues;

    public FeedbackRow(UUID feedbackId, int sensorNo, Instant collectedAt,
                       FeltTemp feltTemp, FeltHumidity feltHumidity, List<Issue> issues) {
        this.feedbackId = Objects.requireNonNull(feedbackId, "feedbackId");
        this.sensorNo = sensorNo;
        this.collectedAt = Objects.requireNonNull(collectedAt, "collectedAt");
        this.feltTemp = Objects.requireNonNull(feltTemp, "feltTemp");
        this.feltHumidity = Objects.requireNonNull(feltHumidity, "feltHumidity");
        this.issues = Collections.unmodifiableList(new ArrayList<>(issues));
    }
}
