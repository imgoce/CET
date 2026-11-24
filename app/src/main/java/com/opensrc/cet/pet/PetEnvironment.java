package com.opensrc.cet.pet;

public final class PetEnvironment {
    public enum Action { DEFAULT, SHIVER, SWEAT }
    public enum Background { DEFAULT, DESERT, DROPLETS }

    public final Action action;
    public final Background background;

    private PetEnvironment(Action action, Background background) {
        this.action = action;
        this.background = background;
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
