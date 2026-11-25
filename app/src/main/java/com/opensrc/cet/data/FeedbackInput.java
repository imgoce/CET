package com.opensrc.cet.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** submitFeedback 요청 (계약서 §09). 재시도할 때는 같은 feedbackId 를 쓴다. */
public final class FeedbackInput {
    public final UUID feedbackId;
    public final UUID collectionId;
    public final UUID userId;
    public final FeltTemp feltTemp;
    public final FeltHumidity feltHumidity;
    /** 1개 이상, 중복 없음. NONE 은 단독 선택 */
    public final List<Issue> issues;
    /** 최대 100자(코드포인트). 공백만 있으면 null 로 저장된다 */
    public final String memo;

    public FeedbackInput(UUID feedbackId, UUID collectionId, UUID userId,
                         FeltTemp feltTemp, FeltHumidity feltHumidity,
                         List<Issue> issues, String memo) {
        this.feedbackId = Objects.requireNonNull(feedbackId, "feedbackId");
        this.collectionId = Objects.requireNonNull(collectionId, "collectionId");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.feltTemp = Objects.requireNonNull(feltTemp, "feltTemp");
        this.feltHumidity = Objects.requireNonNull(feltHumidity, "feltHumidity");
        this.issues = Collections.unmodifiableList(new ArrayList<>(issues));
        this.memo = memo;
    }
}
