package com.openmason.engine.ui.runtime.input;

/** Where an interaction came from; decides focus indication and which hint glyphs show. */
public enum InputDevice {
    MOUSE,
    KEYBOARD,
    GAMEPAD,
    /** Script or host code, not a person. */
    PROGRAM;

    /** Keyboard and controller focus is indicated ({@code :focus-visible}); pointer focus is not. */
    public boolean showsFocus() {
        return this == KEYBOARD || this == GAMEPAD;
    }
}
