package com.openmason.engine.ui.runtime.input;

/** A text field's value changed ({@code CHANGE}) or was committed ({@code COMMIT}) (#288). */
public final class ChangeEvent extends UiEvent {

    private final String previous;
    private final String value;

    public ChangeEvent(UiEventType type, double time, String previous, String value) {
        super(type, time);
        this.previous = previous;
        this.value = value;
    }

    /** For COMMIT: the last committed value. */
    public String previous() {
        return previous;
    }

    public String value() {
        return value;
    }
}
