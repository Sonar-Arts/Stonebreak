package com.openmason.engine.ui.runtime.input;

/**
 * A key press, auto-repeat or release (#288), sent to the focused element (or the active
 * scope's root when nothing is focused). Key codes and modifier bits are GLFW's
 * ({@code MKeys}). {@link #action()} is the UI action the player's bindings map the key to, or
 * null.
 */
public final class KeyEvent extends UiEvent {

    private final int key;
    private final int modifiers;
    private final boolean repeat;
    private final UiAction action;

    public KeyEvent(UiEventType type, double time, int key, int modifiers, boolean repeat, UiAction action) {
        super(type, time);
        this.key = key;
        this.modifiers = modifiers;
        this.repeat = repeat;
        this.action = action;
    }

    public int key() {
        return key;
    }

    public int modifiers() {
        return modifiers;
    }

    public boolean isRepeat() {
        return repeat;
    }

    public UiAction action() {
        return action;
    }
}
