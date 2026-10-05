package com.openmason.engine.ui.masonry;

/**
 * Key codes, actions and modifier bits Masonry widgets understand. The values are GLFW's, which
 * is what both Stonebreak and Open Mason deliver, so hosts pass their key events through
 * unchanged — but Masonry itself never links against a windowing library.
 */
public final class MKeys {

    public static final int RELEASE = 0;
    public static final int PRESS = 1;
    public static final int REPEAT = 2;

    public static final int MOD_SHIFT = 0x0001;
    public static final int MOD_CONTROL = 0x0002;

    public static final int KEY_A = 65;
    public static final int KEY_C = 67;
    public static final int KEY_V = 86;
    public static final int KEY_X = 88;
    public static final int KEY_BACKSPACE = 259;
    public static final int KEY_DELETE = 261;
    public static final int KEY_RIGHT = 262;
    public static final int KEY_LEFT = 263;
    public static final int KEY_HOME = 268;
    public static final int KEY_END = 269;

    private MKeys() {
    }
}
