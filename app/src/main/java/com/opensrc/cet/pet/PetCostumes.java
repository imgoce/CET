package com.opensrc.cet.pet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class PetCostumes {
    private static final List<String> CATALOG = Arrays.asList("SPROUT", "RIBBON", "STAR", "CROWN");

    private PetCostumes() {}

    public static long nextDrawCost(long drawCount) {
        if (drawCount < 0) throw new IllegalArgumentException("drawCount");
        return Math.addExact(24L, drawCount);
    }

    public static PetActionError drawError(long level, long foodBalance, long drawCount,
                                           Collection<String> ownedCostumes) {
        if (foodBalance < 0) throw new IllegalArgumentException("foodBalance");
        long cost = nextDrawCost(drawCount);
        if (PetGrowth.stageFor(level) != PetGrowth.Stage.ADULT) return PetActionError.LEVEL_LOCKED;
        if (ownedCostumes.containsAll(CATALOG)) return PetActionError.ALL_OWNED;
        if (foodBalance < cost) return PetActionError.INSUFFICIENT_FOOD;
        return null;
    }

    public static DrawResult draw(long level, long foodBalance, long drawCount,
                                  Collection<String> ownedCostumes) {
        List<String> inventory = new ArrayList<>(ownedCostumes);
        PetActionError error = drawError(level, foodBalance, drawCount, inventory);
        if (error != null) {
            return new DrawResult(foodBalance, drawCount, inventory, null, 0, error);
        }

        // 미보유 장식 중 균등 추첨
        List<String> remaining = new ArrayList<>(CATALOG);
        remaining.removeAll(inventory);
        String drawn = remaining.get(ThreadLocalRandom.current().nextInt(remaining.size()));
        inventory.add(drawn);
        long spent = nextDrawCost(drawCount);
        return new DrawResult(foodBalance - spent, drawCount + 1, inventory, drawn, spent, null);
    }

    public static EquipResult equip(Collection<String> ownedCostumes, String equippedCostume,
                                   String selectedCostume) {
        if (selectedCostume != null && !CATALOG.contains(selectedCostume)) {
            return new EquipResult(equippedCostume, PetActionError.INVALID_INPUT);
        }
        if (selectedCostume != null && !ownedCostumes.contains(selectedCostume)) {
            return new EquipResult(equippedCostume, PetActionError.NOT_OWNED);
        }
        return new EquipResult(selectedCostume, null);
    }

    public static final class DrawResult {
        public final long foodBalance;
        public final long drawCount;
        public final long nextDrawCost;
        public final List<String> ownedCostumes;
        public final String drawnCostume;
        public final long spentFood;
        public final PetActionError error;

        private DrawResult(long foodBalance, long drawCount, List<String> inventory,
                           String drawnCostume, long spentFood, PetActionError error) {
            this.foodBalance = foodBalance;
            this.drawCount = drawCount;
            this.nextDrawCost = PetCostumes.nextDrawCost(drawCount);
            this.ownedCostumes = Collections.unmodifiableList(inventory);
            this.drawnCostume = drawnCostume;
            this.spentFood = spentFood;
            this.error = error;
        }
    }

    public static final class EquipResult {
        public final String equippedCostume;
        public final PetActionError error;

        private EquipResult(String equippedCostume, PetActionError error) {
            this.equippedCostume = equippedCostume;
            this.error = error;
        }
    }
}
