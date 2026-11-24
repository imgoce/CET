package com.opensrc.cet.pet;

public final class PetGrowth {
    public enum Stage { EGG, BABY, HATCHLING, ADULT }

    private PetGrowth() {}

    public static Stage stageFor(long level) {
        if (level < 1) throw new IllegalArgumentException("level");
        if (level < 6) return Stage.EGG;
        if (level < 11) return Stage.BABY;
        if (level < 16) return Stage.HATCHLING;
        return Stage.ADULT;
    }

    public static FeedResult feed(long level, long progress, long foodBalance) {
        if (level < 1 || progress < 0 || progress > level || foodBalance < 0) {
            throw new IllegalArgumentException("level, progress, foodBalance");
        }
        if (foodBalance == 0) {
            return new FeedResult(level, level, progress, foodBalance, PetActionError.INSUFFICIENT_FOOD);
        }

        // 현재 진행도가 L이면 이번 한 개로 L+1 달성
        if (progress == level) {
            return new FeedResult(level, Math.incrementExact(level), 0, foodBalance - 1, null);
        }
        return new FeedResult(level, level, progress + 1, foodBalance - 1, null);
    }

    public static final class FeedResult {
        public final long level;
        public final long progress;
        public final long foodBalance;
        public final Stage stage;
        public final boolean leveledUp;
        public final boolean evolved;
        public final PetActionError error;

        private FeedResult(long previousLevel, long level, long progress, long foodBalance,
                           PetActionError error) {
            this.level = level;
            this.progress = progress;
            this.foodBalance = foodBalance;
            this.stage = stageFor(level);
            this.leveledUp = level != previousLevel;
            this.evolved = leveledUp && stage != stageFor(previousLevel);
            this.error = error;
        }
    }
}
