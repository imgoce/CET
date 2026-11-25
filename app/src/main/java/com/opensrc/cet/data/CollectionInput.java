package com.opensrc.cet.data;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** commitCollection 요청 (계약서 §06). 재시도할 때는 같은 객체(같은 ID)를 다시 보낸다. */
public final class CollectionInput {
    public final UUID collectionId;
    public final UUID userId;
    public final UUID windowId;
    public final Instant windowStartAt;
    public final int candidateSensorNo;
    public final Measurement measurement;
    public final PublicRoute publicRoute;
    /** 공용 API 2가 돌려준 id. TEMP_UNAVAILABLE 이면 null */
    public final Long publicId;

    public CollectionInput(UUID collectionId, UUID userId, UUID windowId, Instant windowStartAt,
                           int candidateSensorNo, Measurement measurement,
                           PublicRoute publicRoute, Long publicId) {
        this.collectionId = Objects.requireNonNull(collectionId, "collectionId");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.windowId = Objects.requireNonNull(windowId, "windowId");
        this.windowStartAt = Objects.requireNonNull(windowStartAt, "windowStartAt");
        this.candidateSensorNo = candidateSensorNo;
        this.measurement = Objects.requireNonNull(measurement, "measurement");
        this.publicRoute = Objects.requireNonNull(publicRoute, "publicRoute");
        this.publicId = publicId;
    }
}
