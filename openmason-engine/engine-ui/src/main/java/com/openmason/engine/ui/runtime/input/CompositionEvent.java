package com.openmason.engine.ui.runtime.input;

/**
 * IME composition (#288): the platform's in-progress text (preedit) before the player commits
 * it. Hosts without a composition source (GLFW 3.4 has none) never send these; see
 * {@link InputCapability#IME_COMPOSITION}.
 */
public final class CompositionEvent extends UiEvent {

    public enum Phase {
        START,
        UPDATE,
        COMMIT,
        CANCEL
    }

    private final Phase compositionPhase;
    private final String text;
    private final int cursor;

    public CompositionEvent(double time, Phase phase, String text, int cursor) {
        super(UiEventType.COMPOSITION, time);
        this.compositionPhase = phase;
        this.text = text == null ? "" : text;
        this.cursor = cursor;
    }

    public Phase compositionPhase() {
        return compositionPhase;
    }

    /** Preedit for START/UPDATE, the committed text for COMMIT. */
    public String text() {
        return text;
    }

    /** Caret inside the preedit (UTF-16 index). */
    public int cursor() {
        return cursor;
    }
}
