package com.opensrc.cet.pet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PetRewards {
    public enum Reason {
        FIRST_SENSOR_DAY(2), NORMAL_FIVE(1), SHADOW_FIVE(2), FEEDBACK(5);

        public final int food;

        Reason(int food) {
            this.food = food;
        }
    }

    private PetRewards() {}

    public static List<Reason> forCollection(boolean credited, boolean firstSensorDay,
                                             boolean isShadowBefore, boolean quotaReached) {
        if (!credited) return Collections.emptyList();

        List<Reason> grants = new ArrayList<>(2);
        // 첫 수집·해당 지역 누적 보상 동시 지급
        if (firstSensorDay) grants.add(Reason.FIRST_SENSOR_DAY);
        if (quotaReached) grants.add(isShadowBefore ? Reason.SHADOW_FIVE : Reason.NORMAL_FIVE);
        return Collections.unmodifiableList(grants);
    }

    public static List<Reason> forFeedback(boolean accepted) {
        return accepted ? Collections.singletonList(Reason.FEEDBACK) : Collections.emptyList();
    }
}
