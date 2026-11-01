package com.openmason.engine.format.omui;

import com.openmason.engine.format.oma.Easing;

/**
 * Easing curves of style transitions and animation keys, with USS-style wire names. The
 * curve math is the engine's OMANIM {@link Easing}, so UI and model animation ease alike.
 */
public enum UiEasing implements WireEnum {
    LINEAR("linear", Easing.LINEAR),
    EASE_IN("ease-in", Easing.EASE_IN),
    EASE_OUT("ease-out", Easing.EASE_OUT),
    EASE_IN_OUT("ease-in-out", Easing.EASE_IN_OUT),
    STEP("step", Easing.STEP);

    private final String wire;
    private final Easing curve;

    UiEasing(String wire, Easing curve) {
        this.wire = wire;
        this.curve = curve;
    }

    @Override
    public String wire() {
        return wire;
    }

    public Easing curve() {
        return curve;
    }
}
