package com.openmason.engine.ui.runtime.input;

/**
 * Standard gamepad button indices, GLFW's {@code GLFW_GAMEPAD_BUTTON_*} values (SDL layout), so
 * a host passes its gamepad state through unchanged while the engine never links GLFW.
 */
public final class GamepadButtons {

    public static final int A = 0;
    public static final int B = 1;
    public static final int X = 2;
    public static final int Y = 3;
    public static final int LEFT_BUMPER = 4;
    public static final int RIGHT_BUMPER = 5;
    public static final int BACK = 6;
    public static final int START = 7;
    public static final int GUIDE = 8;
    public static final int LEFT_THUMB = 9;
    public static final int RIGHT_THUMB = 10;
    public static final int DPAD_UP = 11;
    public static final int DPAD_RIGHT = 12;
    public static final int DPAD_DOWN = 13;
    public static final int DPAD_LEFT = 14;
    public static final int COUNT = 15;

    private static final String[] NAMES = {"A", "B", "X", "Y", "LB", "RB", "Back", "Start", "Guide", "LS", "RS",
        "D-Pad Up", "D-Pad Right", "D-Pad Down", "D-Pad Left"};

    private GamepadButtons() {
    }

    /** Short label for hints ("A", "LB", "D-Pad Up"). */
    public static String name(int button) {
        return button >= 0 && button < NAMES.length ? NAMES[button] : "Button " + button;
    }
}
