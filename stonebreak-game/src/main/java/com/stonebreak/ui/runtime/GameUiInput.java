package com.stonebreak.ui.runtime;

import com.openmason.engine.ui.runtime.UiPreferences;
import com.openmason.engine.ui.runtime.input.CancelReason;
import com.openmason.engine.ui.runtime.input.InputCapability;
import com.openmason.engine.ui.runtime.input.UiActionMap;
import com.openmason.engine.ui.runtime.input.UiInputRouter;
import com.openmason.engine.ui.runtime.paint.UiDocumentView;
import com.stonebreak.config.Settings;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The game window's input host for UI documents (#288). {@code MenuInputRouter} offers every
 * GLFW event here first; open documents get it topmost first, and an event any of them
 * consumes never reaches the legacy screens or gameplay ({@code InputHandler}). Pointer input
 * is only offered while the cursor is free (not captured for camera look); while it is captured
 * new key presses and text go only to a document with an open modal, so a button focused earlier
 * cannot take Space or Enter away from gameplay. Releases are always offered, so a press a
 * document took is released there.
 *
 * <p>It also owns the per-frame clock (controller repeat, tooltips, caret blink), the
 * controller poll, and the player's UI settings: reduced motion, text scale and remapped
 * bindings apply to every open document. Window focus loss, a disconnect or world unload, and
 * closing a document cancel in-flight interactions through the engine router.
 *
 * <p>Main thread only, like GLFW callbacks.
 */
public final class GameUiInput {

    /**
     * What the game window provides to documents ({@code UiInputGate}). Missing: IME
     * composition (GLFW 3.4 has no preedit callback), right-to-left text and complex shaping
     * (one Latin font, glyph-by-glyph drawing), font fallback, and a platform accessibility
     * bridge. Supplementary-plane text and controllers are new with #288.
     */
    public static final Set<InputCapability> CAPABILITIES = EnumSet.of(InputCapability.POINTER,
        InputCapability.WHEEL, InputCapability.KEYBOARD, InputCapability.TEXT_INPUT,
        InputCapability.TEXT_INPUT_SUPPLEMENTARY, InputCapability.CLIPBOARD, InputCapability.GAMEPAD);

    private static final GameUiInput INSTANCE = new GameUiInput();

    private final List<UiDocumentView> views = new ArrayList<>();
    private final GamepadUiSource gamepads = new GamepadUiSource();
    private long lastFrame;
    private Map<String, List<String>> appliedBindings;
    private UiActionMap actionMap = UiActionMap.defaults();

    private GameUiInput() {
    }

    public static GameUiInput get() {
        return INSTANCE;
    }

    /** Puts {@code view} on top of the open documents. */
    public void open(UiDocumentView view) {
        if (!views.contains(view)) {
            views.add(view);
            applySettings(view);
        }
    }

    /** Removes {@code view}, ending its in-flight interactions ({@code SCREEN_CLOSED}). */
    public void close(UiDocumentView view) {
        if (views.remove(view)) {
            view.input().screenClosed();
        }
    }

    public boolean active() {
        return !views.isEmpty();
    }

    // ── GLFW callbacks (via MenuInputRouter) ────────────────────────────────

    public boolean onKey(int key, int action, int mods, boolean cursorCaptured) {
        for (int i = views.size() - 1; i >= 0; i--) {
            UiInputRouter r = views.get(i).input();
            if (cursorCaptured && action == org.lwjgl.glfw.GLFW.GLFW_PRESS && r.focus().activeModal() == null) {
                continue;
            }
            if (r.key(key, action, mods)) {
                return true;
            }
        }
        return false;
    }

    /** Whole code points, supplementary planes included (legacy screens still get BMP chars only). */
    public boolean onCharacter(int codePoint, boolean cursorCaptured) {
        for (int i = views.size() - 1; i >= 0; i--) {
            UiInputRouter r = views.get(i).input();
            if (cursorCaptured && r.focus().activeModal() == null) {
                continue;
            }
            if (r.text(codePoint)) {
                return true;
            }
        }
        return false;
    }

    /** @param x framebuffer pixels (the UI space) */
    public boolean onMouseButton(double x, double y, int button, int action, int mods, boolean cursorCaptured) {
        if (cursorCaptured && action == org.lwjgl.glfw.GLFW.GLFW_PRESS) {
            return false;
        }
        for (int i = views.size() - 1; i >= 0; i--) {
            UiInputRouter r = views.get(i).input();
            boolean consumed = action == org.lwjgl.glfw.GLFW.GLFW_PRESS
                ? r.pointerDown((float) x, (float) y, button, mods)
                : r.pointerUp((float) x, (float) y, button, mods);
            if (consumed) {
                return true;
            }
        }
        return false;
    }

    /** Hover goes to the topmost document under the pointer; documents below it see the pointer leave. */
    public boolean onMouseMove(double x, double y, boolean cursorCaptured) {
        boolean consumed = false;
        for (int i = views.size() - 1; i >= 0; i--) {
            UiInputRouter r = views.get(i).input();
            if (cursorCaptured || consumed) {
                r.pointerLeave();
            } else {
                consumed = r.pointerMove((float) x, (float) y);
            }
        }
        return consumed;
    }

    public boolean onScroll(double x, double y, double dx, double dy, boolean cursorCaptured) {
        if (cursorCaptured) {
            return false;
        }
        for (int i = views.size() - 1; i >= 0; i--) {
            if (views.get(i).input().wheel((float) x, (float) y, (float) dx, (float) dy, 0)) {
                return true;
            }
        }
        return false;
    }

    // ── frame and lifecycle ─────────────────────────────────────────────────

    /** Once per frame: settings, the controller poll, then the routers' clocks. */
    public void frame() {
        long now = System.nanoTime();
        double dt = lastFrame == 0 ? 0 : Math.min(0.1, (now - lastFrame) / 1e9);
        lastFrame = now;
        // Polled even with no document open, so a button already held when one opens is not a new press.
        boolean any = !views.isEmpty();
        if (any) {
            views.forEach(this::applySettings);
        }
        gamepads.poll((button, down) -> {
            if (!any) {
                return;
            }
            for (int i = views.size() - 1; i >= 0; i--) {
                if (views.get(i).input().gamepadButton(button, down)) {
                    return;
                }
            }
        });
        for (UiDocumentView v : views) {
            v.input().tick(dt);
        }
    }

    public void windowFocusLost() {
        // The pad poll keeps its state: a button still held after refocus is not a fresh press.
        cancelAll(CancelReason.WINDOW_FOCUS_LOST);
    }

    /** Cancels in-flight interactions in every open document (disconnect, world unload). */
    public void cancelAll(CancelReason reason) {
        for (UiDocumentView v : List.copyOf(views)) {
            v.input().cancelInteractions(reason);
        }
    }

    private void applySettings(UiDocumentView view) {
        Settings s = Settings.getInstance();
        UiPreferences prefs = new UiPreferences(s.isReducedMotion(), s.getUiTextScale());
        if (!prefs.equals(view.instance().preferences())) {
            view.instance().setPreferences(prefs);
        }
        if (s.getUiBindings() != appliedBindings) {
            appliedBindings = s.getUiBindings();
            actionMap = UiActionMap.fromWire(appliedBindings);
        }
        if (!actionMap.equals(view.input().actionMap())) {
            view.input().setActionMap(actionMap);
        }
    }
}
