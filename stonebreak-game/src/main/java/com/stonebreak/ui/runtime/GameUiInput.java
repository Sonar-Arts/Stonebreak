package com.stonebreak.ui.runtime;

import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiPreferences;
import com.openmason.engine.ui.runtime.input.CancelReason;
import com.openmason.engine.ui.runtime.input.InputCapability;
import com.openmason.engine.ui.runtime.input.UiActionMap;
import com.openmason.engine.ui.runtime.input.UiInputRouter;
import com.openmason.engine.ui.runtime.paint.UiDocumentView;
import com.stonebreak.config.Settings;
import com.stonebreak.ui.runtime.screens.UiLayer;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntUnaryOperator;

/**
 * The game window's input host for UI documents (#288). {@code MenuInputRouter} offers every
 * GLFW event here first; open documents get it topmost first, and an event any of them
 * consumes never reaches the legacy screens or gameplay ({@code InputHandler}). Pointer input
 * is only offered while the cursor is free (not captured for camera look); while it is captured
 * new key presses and text go only to a document with an open modal, so a button focused earlier
 * cannot take Space or Enter away from gameplay. Releases are always offered, so a press a
 * document took is released there.
 *
 * <p>The open documents form ONE ordered stack ({@link #views()}, bottom to top by
 * {@link UiLayer}, then open order): the game draws them in that order and offers input in the
 * reverse, so what is on top is what gets the click.
 *
 * <p>Polled input ({@code glfwGetKey} for movement, Escape, E, 1-9 ...) cannot be stopped by
 * consuming a callback, so the game's key polls ask {@link #masksKey}: a key whose press a
 * document consumed reads as up until it is released, and every key reads as up while a document
 * owns the keyboard (a focused text field, an open modal, or a screen that claims it).
 *
 * <p>It also owns the per-frame clock (controller repeat, tooltips, caret blink; one
 * {@link UiFrameClock} sample per frame), the controller poll, and the player's UI settings:
 * reduced motion, text scale and remapped bindings apply to every open document. Window focus
 * loss, a disconnect or world unload, and closing a document cancel in-flight interactions
 * through the engine router.
 *
 * <p>Main thread only, like GLFW callbacks.
 */
public final class GameUiInput {

    /**
     * What the game window provides to documents ({@code UiInputGate}). Missing: IME
     * composition (GLFW 3.4 has no preedit callback), right-to-left text and complex shaping
     * (one Latin font, glyph-by-glyph drawing), font fallback, and a platform accessibility
     * bridge. Supplementary-plane text and controllers are new with #288. Without
     * {@code FONT_FALLBACK} a text field must be {@code inputFilter: ascii} (or the screen stays
     * legacy): the game font has no glyphs beyond Latin, so other text would draw as boxes.
     */
    public static final Set<InputCapability> CAPABILITIES = EnumSet.of(InputCapability.POINTER,
        InputCapability.WHEEL, InputCapability.KEYBOARD, InputCapability.TEXT_INPUT,
        InputCapability.TEXT_INPUT_SUPPLEMENTARY, InputCapability.CLIPBOARD, InputCapability.GAMEPAD);

    private static final GameUiInput INSTANCE = new GameUiInput();

    private static final class Entry {
        final UiDocumentView view;
        final UiLayer layer;
        boolean claimsKeyboard;
        boolean claimsGamepad;

        Entry(UiDocumentView view, UiLayer layer) {
            this.view = view;
            this.layer = layer;
        }
    }

    /** Bottom to top. */
    private final List<Entry> entries = new ArrayList<>();
    private List<UiDocumentView> viewsSnapshot = List.of();
    private final GamepadUiSource gamepads = new GamepadUiSource();
    /** Keys whose press a document consumed; up for polling until released. */
    private final BitSet uiHeld = new BitSet();
    private boolean cursorCaptured;
    private IntUnaryOperator keyTranslator = IntUnaryOperator.identity();
    private Map<String, List<String>> appliedBindings;
    private UiActionMap actionMap = UiActionMap.defaults();

    private GameUiInput() {
    }

    public static GameUiInput get() {
        return INSTANCE;
    }

    /**
     * Maps GLFW key tokens to the keyboard layout before documents see them (C8); the game
     * installs {@link LayoutKeys} once GLFW is up. Identity until then (tests, headless).
     */
    public void setKeyTranslator(IntUnaryOperator translator) {
        keyTranslator = translator == null ? IntUnaryOperator.identity() : translator;
    }

    /** Puts {@code view} on top of the {@link UiLayer#OVERLAY} layer (developer overlays, tests). */
    public void open(UiDocumentView view) {
        open(view, UiLayer.OVERLAY);
    }

    /** Puts {@code view} on top of {@code layer}: above every document of that or a lower layer. */
    public void open(UiDocumentView view, UiLayer layer) {
        if (indexOf(view) >= 0) {
            return;
        }
        int at = entries.size();
        while (at > 0 && entries.get(at - 1).layer.compareTo(layer) > 0) {
            at--;
        }
        entries.add(at, new Entry(view, layer));
        viewsSnapshot = null;
        applySettings(view);
    }

    /** Removes {@code view}, ending its in-flight interactions ({@code SCREEN_CLOSED}). */
    public void close(UiDocumentView view) {
        int i = indexOf(view);
        if (i >= 0) {
            entries.remove(i);
            viewsSnapshot = null;
            view.input().screenClosed();
        }
    }

    /**
     * Whether {@code view} takes the whole keyboard while open (a full screen with its own key
     * handling): gameplay polls see no key at all, consumed or not.
     */
    public void setClaimsKeyboard(UiDocumentView view, boolean claims) {
        int i = indexOf(view);
        if (i >= 0) {
            entries.get(i).claimsKeyboard = claims;
        }
    }

    /** Whether {@code view} gets controller buttons even while the cursor is captured for camera look. */
    public void setClaimsGamepad(UiDocumentView view, boolean claims) {
        int i = indexOf(view);
        if (i >= 0) {
            entries.get(i).claimsGamepad = claims;
        }
    }

    public boolean active() {
        return !entries.isEmpty();
    }

    public boolean isOpen(UiDocumentView view) {
        return indexOf(view) >= 0;
    }

    /** Open documents bottom to top: the draw order (input goes top to bottom). */
    public List<UiDocumentView> views() {
        List<UiDocumentView> v = viewsSnapshot;
        if (v == null) {
            List<UiDocumentView> out = new ArrayList<>(entries.size());
            for (Entry e : entries) {
                out.add(e.view);
            }
            v = viewsSnapshot = Collections.unmodifiableList(out);
        }
        return v;
    }

    /** The layer {@code view} was opened in, or null when it is not open. */
    public UiLayer layerOf(UiDocumentView view) {
        int i = indexOf(view);
        return i < 0 ? null : entries.get(i).layer;
    }

    // ── polled-key ownership ────────────────────────────────────────────────

    /**
     * True while gameplay key polls must read {@code key} as up: a document consumed its press
     * (until release), or a document owns the whole keyboard ({@link #ownsKeyboard}).
     */
    public boolean masksKey(int key) {
        return (key >= 0 && uiHeld.get(key)) || ownsKeyboard();
    }

    /**
     * True while an open document owns the keyboard: a focused text field, an open modal, or a
     * screen that claims it. With the cursor captured only a modal (or a claim) counts: presses
     * do not reach other documents then ({@link #onKey}).
     */
    public boolean ownsKeyboard() {
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            if (e.claimsKeyboard) {
                return true;
            }
            UiInputRouter r = e.view.input();
            if (r.focus().activeModal() != null) {
                return true;
            }
            if (!cursorCaptured) {
                UiElement focused = r.focus().focused();
                if (focused != null && !focused.isRemoved() && "TextField".equals(focused.type())) {
                    return true;
                }
            }
        }
        return false;
    }

    // ── GLFW callbacks (via MenuInputRouter) ────────────────────────────────

    public boolean onKey(int key, int action, int mods, boolean cursorCaptured) {
        this.cursorCaptured = cursorCaptured;
        boolean release = action == org.lwjgl.glfw.GLFW.GLFW_RELEASE;
        boolean consumed = route(keyTranslator.applyAsInt(key), action, mods, cursorCaptured);
        if (key >= 0) {
            if (release) {
                uiHeld.clear(key);
            } else if (consumed && action == org.lwjgl.glfw.GLFW.GLFW_PRESS) {
                uiHeld.set(key);
            }
        }
        // A release the gameplay saw pressed must reach it, or its key would stick down there.
        return consumed;
    }

    private boolean route(int key, int action, int mods, boolean cursorCaptured) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            UiInputRouter r = e.view.input();
            if (cursorCaptured && action == org.lwjgl.glfw.GLFW.GLFW_PRESS && r.focus().activeModal() == null
                    && !e.claimsKeyboard) {
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
        this.cursorCaptured = cursorCaptured;
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            UiInputRouter r = e.view.input();
            if (cursorCaptured && r.focus().activeModal() == null && !e.claimsKeyboard) {
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
        this.cursorCaptured = cursorCaptured;
        if (cursorCaptured && action == org.lwjgl.glfw.GLFW.GLFW_PRESS) {
            return false;
        }
        for (int i = entries.size() - 1; i >= 0; i--) {
            UiInputRouter r = entries.get(i).view.input();
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
        this.cursorCaptured = cursorCaptured;
        boolean consumed = false;
        for (int i = entries.size() - 1; i >= 0; i--) {
            UiInputRouter r = entries.get(i).view.input();
            if (cursorCaptured || consumed) {
                r.pointerLeave();
            } else {
                consumed = r.pointerMove((float) x, (float) y);
            }
        }
        return consumed;
    }

    /** As {@link #onScroll(double, double, double, double, int, boolean)} without modifiers. */
    public boolean onScroll(double x, double y, double dx, double dy, boolean cursorCaptured) {
        return onScroll(x, y, dx, dy, 0, cursorCaptured);
    }

    /**
     * @param mods GLFW modifier bits held during the scroll (GLFW's scroll callback has none, so
     *             the router samples them): Shift+wheel scrolls horizontally, as in the preview
     */
    public boolean onScroll(double x, double y, double dx, double dy, int mods, boolean cursorCaptured) {
        this.cursorCaptured = cursorCaptured;
        if (cursorCaptured) {
            return false;
        }
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).view.input().wheel((float) x, (float) y, (float) dx, (float) dy, mods)) {
                return true;
            }
        }
        return false;
    }

    // ── frame and lifecycle ─────────────────────────────────────────────────

    /** As {@link #frame(boolean)} with a free cursor. */
    public void frame() {
        frame(false);
    }

    /**
     * Once per frame: samples the {@link UiFrameClock}, applies settings, polls controllers, then
     * advances the routers' clocks by the same {@code uiDt} the documents use. While the cursor
     * is captured for camera look, controller buttons only reach a document with an open modal or
     * one that claims the controller ({@link #setClaimsGamepad}); a HUD button focused earlier
     * never takes the A button away from gameplay.
     */
    public void frame(boolean cursorCaptured) {
        this.cursorCaptured = cursorCaptured;
        UiFrameClock clock = UiFrameClock.get();
        clock.beginFrame();
        double dt = clock.uiDt();
        // Polled even with no document open, so a button already held when one opens is not a new press.
        boolean any = !entries.isEmpty();
        if (any) {
            for (Entry e : entries) {
                applySettings(e.view);
            }
        }
        gamepads.poll((button, down) -> {
            if (!any) {
                return;
            }
            for (int i = entries.size() - 1; i >= 0; i--) {
                Entry e = entries.get(i);
                if (cursorCaptured && !e.claimsGamepad && e.view.input().focus().activeModal() == null) {
                    continue;
                }
                if (e.view.input().gamepadButton(button, down)) {
                    return;
                }
            }
        });
        for (UiDocumentView v : views()) {
            v.input().tick(dt);
        }
    }

    public void windowFocusLost() {
        // The pad poll keeps its state: a button still held after refocus is not a fresh press.
        uiHeld.clear(); // GLFW sends no release for keys let go while unfocused
        cancelAll(CancelReason.WINDOW_FOCUS_LOST);
    }

    /** Cancels in-flight interactions in every open document (disconnect, world unload). */
    public void cancelAll(CancelReason reason) {
        for (UiDocumentView v : List.copyOf(views())) {
            v.input().cancelInteractions(reason);
        }
    }

    private int indexOf(UiDocumentView view) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).view == view) {
                return i;
            }
        }
        return -1;
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
