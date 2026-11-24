package com.opensrc.cet.pet;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class PetEnvironment {
    public enum Action { DEFAULT, SHIVER, SWEAT, COUGH }
    public enum Background { DEFAULT, DESERT, DROPLETS }

    public final Action action;
    public final Background background;

    private PetEnvironment(Action action, Background background) {
        this.action = action;
        this.background = background;
    }

    public List<Action> actionsForReading(boolean coughEnabled, Integer validEco2Ppm) {
        // 첫 행동은 한 번 재생한 뒤 마지막 온도 행동을 유지
        if (coughEnabled && validEco2Ppm != null && validEco2Ppm >= 1000) {
            return Collections.unmodifiableList(Arrays.asList(Action.COUGH, action));
        }
        return Collections.singletonList(action);
    }

    public static PetEnvironment resolve(String temperatureState, String humidityState) {
        Action action = Action.DEFAULT;
        if ("COLD".equals(temperatureState)) {
            action = Action.SHIVER;
        } else if ("HOT".equals(temperatureState)) {
            action = Action.SWEAT;
        }

        Background background = Background.DEFAULT;
        if ("DRY".equals(humidityState)) {
            background = Background.DESERT;
        } else if ("HUMID".equals(humidityState)) {
            background = Background.DROPLETS;
        }
        return new PetEnvironment(action, background);
    }
}
