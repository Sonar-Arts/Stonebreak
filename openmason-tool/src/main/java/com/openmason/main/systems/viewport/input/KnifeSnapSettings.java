package com.openmason.main.systems.viewport.input;

/**
 * Mutable state for knife tool grid snapping.
 * Independent from the viewport's general grid snap settings,
 * allowing the knife tool to have its own toggle and increment.
 */
public class KnifeSnapSettings {

    private boolean enabled;
    private float increment;

    public KnifeSnapSettings() {
        this.enabled = false;
        this.increment = 0.5f;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public float getIncrement() {
        return increment;
    }

    public void setIncrement(float increment) {
        this.increment = increment;
    }
}
