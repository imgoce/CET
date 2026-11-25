package com.opensrc.cet.data;

import java.util.Objects;

/** drawCostume 성공 결과 (계약서 §11). 재전송해도 다시 추첨하지 않는다. */
public final class DrawResult {
    public final PetState pet;
    public final String drawnCostume;
    public final long spentFood;
    public final boolean replayed;

    public DrawResult(PetState pet, String drawnCostume, long spentFood, boolean replayed) {
        this.pet = Objects.requireNonNull(pet, "pet");
        this.drawnCostume = Objects.requireNonNull(drawnCostume, "drawnCostume");
        this.spentFood = spentFood;
        this.replayed = replayed;
    }
}
