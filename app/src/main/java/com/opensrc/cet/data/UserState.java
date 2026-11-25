package com.opensrc.cet.data;

import java.util.Objects;
import java.util.UUID;

/**
 * 사용자와 펫, 수집 진행도 (계약서 §08).
 * Total 은 승인된 기여 누계, Remainder 는 Total 을 5로 나눈 나머지(진행도 0/5~4/5 표시용).
 */
public final class UserState {
    public final UUID userId;
    public final String nickname;
    public final PetState pet;
    public final long normalTotal;
    public final long shadowTotal;
    public final int normalRemainder;
    public final int shadowRemainder;

    public UserState(UUID userId, String nickname, PetState pet, long normalTotal, long shadowTotal,
                     int normalRemainder, int shadowRemainder) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.nickname = Objects.requireNonNull(nickname, "nickname");
        this.pet = Objects.requireNonNull(pet, "pet");
        this.normalTotal = normalTotal;
        this.shadowTotal = shadowTotal;
        this.normalRemainder = normalRemainder;
        this.shadowRemainder = shadowRemainder;
    }
}
