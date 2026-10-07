package com.openmason.engine.ui.runtime.input;

import com.openmason.engine.ui.runtime.UiElement;

/**
 * Focus moved (#288). {@code FOCUS_OUT}/{@code BLUR} go to the element losing focus and
 * {@code FOCUS_IN}/{@code FOCUS} to the one gaining it; the in/out pair bubbles.
 */
public final class FocusEvent extends UiEvent {

    private final UiElement related;
    private final InputDevice device;

    public FocusEvent(UiEventType type, double time, UiElement related, InputDevice device) {
        super(type, time);
        this.related = related;
        this.device = device;
    }

    /** The other side of the move: the element gaining focus for out/blur, losing it for in/focus. */
    public UiElement related() {
        return related;
    }

    public InputDevice device() {
        return device;
    }
}
