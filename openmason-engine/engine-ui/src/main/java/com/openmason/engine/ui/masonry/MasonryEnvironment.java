package com.openmason.engine.ui.masonry;

import java.util.Objects;

/**
 * Host services Masonry widgets read but must not own: the player's UI scale and the system
 * clipboard. The engine never imports the game or tool, so each host installs adapters once at
 * startup (Stonebreak: {@code Settings.getUiScale} and the GLFW clipboard of the game window;
 * Open Mason: its preview scale and its own window). Until then a neutral default applies
 * (scale 1, a process-local clipboard), which is also what headless tests see.
 *
 * <p>Process-wide by design: widget code such as {@code MWidget.textScale()} has no frame
 * context to carry a per-instance service, and one process hosts one UI scale.
 */
public final class MasonryEnvironment {

    /** The user's UI scale multiplier (1 = authored size). */
    @FunctionalInterface
    public interface UiScaleSource {
        float uiScale();
    }

    /** Text clipboard. Implementations must never throw; failures read as {@code ""}. */
    public interface ClipboardProvider {
        String read();

        void write(String text);
    }

    private static final UiScaleSource DEFAULT_SCALE = () -> 1f;

    private static volatile UiScaleSource scale = DEFAULT_SCALE;
    private static volatile ClipboardProvider clipboard = new LocalClipboard();

    private MasonryEnvironment() {
    }

    public static void installUiScale(UiScaleSource source) {
        scale = Objects.requireNonNull(source, "source");
    }

    public static void installClipboard(ClipboardProvider provider) {
        clipboard = Objects.requireNonNull(provider, "provider");
    }

    /** Back to the neutral defaults (tests). */
    public static void reset() {
        scale = DEFAULT_SCALE;
        clipboard = new LocalClipboard();
    }

    public static float uiScale() {
        return scale.uiScale();
    }

    public static ClipboardProvider clipboard() {
        return clipboard;
    }

    /** Clipboard that only lives inside this process: the headless/test default. */
    private static final class LocalClipboard implements ClipboardProvider {
        private volatile String text = "";

        @Override
        public String read() {
            return text;
        }

        @Override
        public void write(String value) {
            if (value != null) {
                text = value;
            }
        }
    }
}
