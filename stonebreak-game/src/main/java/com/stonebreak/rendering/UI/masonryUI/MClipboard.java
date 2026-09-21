package com.stonebreak.rendering.UI.masonryUI;

import static org.lwjgl.glfw.GLFW.glfwGetClipboardString;
import static org.lwjgl.glfw.GLFW.glfwSetClipboardString;

import com.stonebreak.core.Game;

/**
 * Single access point for the system clipboard, shared by every text field in
 * the UI (MasonryUI widgets and the hand-rolled Skija fields alike).
 *
 * <p>GLFW clipboard calls need the window handle and must run on the main
 * thread; both hold for UI input callbacks. Failures are swallowed — a missing
 * or unreadable clipboard is never worth taking the game down for.
 */
public final class MClipboard {

    private MClipboard() {}

    /** Clipboard contents, or {@code ""} when empty/unavailable. */
    public static String read() {
        try {
            long window = Game.getInstance().getWindow();
            if (window != 0) {
                String s = glfwGetClipboardString(window);
                if (s != null) return s;
            }
        } catch (Exception ignored) {}
        return "";
    }

    public static void write(String text) {
        if (text == null) return;
        try {
            long window = Game.getInstance().getWindow();
            if (window != 0) glfwSetClipboardString(window, text);
        } catch (Exception ignored) {}
    }
}
