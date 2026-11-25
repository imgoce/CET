package com.opensrc.cet.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * commitCollection 성공 결과 (계약서 §06). credited=false 도 저장 성공이다.
 * replayed=true 면 같은 요청의 재전송이므로 보상 알림을 다시 띄우지 않는다.
 */
public final class CollectionResult {
    public final UUID collectionId;
    public final boolean credited;
    public final boolean isShadowBefore;
    public final boolean measurementAdded;
    public final List<Grant> grants;
    public final long foodBalance;
    public final int normalRemainder;
    public final int shadowRemainder;
    public final boolean replayed;

    public CollectionResult(UUID collectionId, boolean credited, boolean isShadowBefore,
                            boolean measurementAdded, List<Grant> grants, long foodBalance,
                            int normalRemainder, int shadowRemainder, boolean replayed) {
        this.collectionId = Objects.requireNonNull(collectionId, "collectionId");
        this.credited = credited;
        this.isShadowBefore = isShadowBefore;
        this.measurementAdded = measurementAdded;
        this.grants = Collections.unmodifiableList(new ArrayList<>(grants));
        this.foodBalance = foodBalance;
        this.normalRemainder = normalRemainder;
        this.shadowRemainder = shadowRemainder;
        this.replayed = replayed;
    }
}
