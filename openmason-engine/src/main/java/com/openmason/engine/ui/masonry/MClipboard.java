package com.openmason.engine.ui.masonry;

/**
 * Single access point for the system clipboard, shared by every text field in
 * the UI (MasonryUI widgets and the hand-rolled Skija fields alike).
 *
 * <p>The clipboard itself is a host service ({@link MasonryEnvironment#installClipboard}):
 * Stonebreak installs the GLFW clipboard of the game window. Failures are swallowed — a
 * missing or unreadable clipboard is never worth taking the game down for.
 */
public final class MClipboard {

    private MClipboard() {}

    /** Clipboard contents, or {@code ""} when empty/unavailable. */
    public static String read() {
        try {
            String s = MasonryEnvironment.clipboard().read();
            return s == null ? "" : s;
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    /** Replace the clipboard contents; {@code null} is ignored. */
    public static void write(String text) {
        if (text == null) return;
        try {
            MasonryEnvironment.clipboard().write(text);
        } catch (RuntimeException ignored) {
            // see class doc
        }
    }
}
