package com.opensrc.cet.data;

import java.util.Objects;

/** equipCostume 성공 결과 (계약서 §11). */
public final class EquipResult {
    public final PetState pet;
    public final boolean replayed;

    public EquipResult(PetState pet, boolean replayed) {
        this.pet = Objects.requireNonNull(pet, "pet");
        this.replayed = replayed;
    }
}
