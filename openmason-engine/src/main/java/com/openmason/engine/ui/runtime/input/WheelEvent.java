package com.openmason.engine.ui.runtime.input;

/**
 * Mouse wheel or trackpad scroll over an element (#288). Deltas are wheel notches as the host
 * reports them (GLFW: positive y = away from the user). The default action scrolls the nearest
 * scroll container that can still move that way.
 */
public final class WheelEvent extends UiEvent {

    private final float x;
    private final float y;
    private final float deltaX;
    private final float deltaY;
    private final int modifiers;

    public WheelEvent(double time, float x, float y, float deltaX, float deltaY, int modifiers) {
        super(UiEventType.WHEEL, time);
        this.x = x;
        this.y = y;
        this.deltaX = deltaX;
        this.deltaY = deltaY;
        this.modifiers = modifiers;
    }

    public float x() {
        return x;
    }

    public float y() {
        return y;
    }

    public float deltaX() {
        return deltaX;
    }

    public float deltaY() {
        return deltaY;
    }

    public int modifiers() {
        return modifiers;
    }
}
