package com.opensrc.cet.data;

import java.util.Objects;

/** feedPet 성공 결과 (계약서 §11). */
public final class FeedResult {
    public final PetState pet;
    public final boolean leveledUp;
    public final boolean evolved;
    public final boolean replayed;

    public FeedResult(PetState pet, boolean leveledUp, boolean evolved, boolean replayed) {
        this.pet = Objects.requireNonNull(pet, "pet");
        this.leveledUp = leveledUp;
        this.evolved = evolved;
        this.replayed = replayed;
    }
}
