package com.openmason.engine.ui.runtime.input;

/**
 * A UI action from the keyboard, a controller or a script (#288): {@code NAVIGATE} for
 * directions, next and previous; {@code SUBMIT}; {@code CANCEL}. Sent to the focused element
 * (or the active scope's root) and bubbles, so a dialog can handle {@code CANCEL} for all of
 * its controls. Preventing the default stops the router's focus move, click or dismissal.
 */
public final class NavigationEvent extends UiEvent {

    private final UiAction action;
    private final InputDevice device;
    private final boolean repeat;

    public NavigationEvent(UiEventType type, double time, UiAction action, InputDevice device, boolean repeat) {
        super(type, time);
        this.action = action;
        this.device = device;
        this.repeat = repeat;
    }

    public UiAction action() {
        return action;
    }

    public InputDevice device() {
        return device;
    }

    public boolean isRepeat() {
        return repeat;
    }
}
