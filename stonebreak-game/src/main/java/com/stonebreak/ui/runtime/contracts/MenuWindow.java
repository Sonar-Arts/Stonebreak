package com.stonebreak.ui.runtime.contracts;

/**
 * The window and UI scale a menu lays out in, for contracts that publish legacy geometry in device
 * pixels (#299: the main menu's title motion, the world select screen's list and info card).
 */
public interface MenuWindow {

    /** {@code [width, height]} in device pixels. */
    default int[] menuWindow() {
        return new int[]{1920, 1080};
    }

    default float menuScale() {
        return 1f;
    }
}
