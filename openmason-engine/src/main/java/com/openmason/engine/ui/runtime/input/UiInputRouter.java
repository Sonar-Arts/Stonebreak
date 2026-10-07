package com.openmason.engine.ui.runtime.input;

import com.openmason.engine.ui.masonry.MKeys;
import com.openmason.engine.ui.runtime.TextLineMetrics;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic;
import com.openmason.engine.ui.runtime.layout.ScrollbarGeometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The one interaction model of a UI document (#288): hosts feed it raw pointer, wheel, key,
 * text, composition and controller input in viewport device pixels; it hit-tests the laid-out
 * tree, dispatches events (trickle-down, target, bubble-up; see {@link EventDispatcher}),
 * runs default actions, and answers whether the host must treat the input as <b>consumed</b>.
 * The game window and the Open Mason preview drive documents through this class, so pointer,
 * focus and keyboard behaviour cannot differ between them.
 *
 * <p><b>Consumed means the UI owns it</b>, and a consumed event must never also reach gameplay
 * or editor shortcuts:
 * <ul>
 *   <li>pointer: a press is consumed when the hit test found an element (authors give
 *       full-screen containers {@code picking-mode: ignore} to let presses through to the
 *       world), a modal is open, or a popup was dismissed; a release exactly when its press was
 *       (or a capture/drag ended), so gameplay never sees a button stay down;</li>
 *   <li>keys: a handler stopped or prevented it, a default action ran (focus moved, the focused
 *       element was clicked, a text field edited, a popup closed, a drag cancelled), or a
 *       modal is open. A release or repeat is consumed exactly when its press was (a modal that
 *       opened in between does not take it); repeats
 *       and releases of keys whose press the router never saw are ignored (a key held across a
 *       screen switch must not act in the new screen — the Focus battle rule);</li>
 *   <li>text: a text field has focus, a handler took it, or a modal is open.</li>
 * </ul>
 *
 * <p><b>Who receives input.</b> Collapsed, hidden, disabled and removed elements never receive
 * events; a disabled element under the pointer still blocks what is below it. A modal blocks
 * pointer input outside itself (and outside popups opened above it). Positions outside the
 * viewport are not on the document: they only reach a live capture or drag, so the editor
 * preview never takes input from beyond its canvas.
 *
 * <p><b>Keyboard repeat.</b> Keyboard repeats come from the platform (the player's OS rate),
 * marked with {@code repeat}; controller actions repeat on the router clock
 * ({@link InputSettings#controllerRepeatDelay()}). Confirm and back never repeat
 * ({@link UiAction#repeats()}).
 *
 * <p>Call {@link #tick} once per frame with the frame time and {@link #sync} after every layout.
 * Single-threaded, like the instance.
 */
public final class UiInputRouter {

    private static final Logger LOGGER = LoggerFactory.getLogger(UiInputRouter.class);

    private final UiDocumentInstance ui;
    private final EventDispatcher dispatcher;
    private final FocusManager focus;
    private final TooltipController tooltips;
    private final DragDropController drag;
    private final Map<UiElement, TextFieldController> textFields = new IdentityHashMap<>();
    private InputSettings settings;
    private UiActionMap actionMap;
    private double time;
    private InputDevice lastDevice = InputDevice.MOUSE;

    // pointer
    private final List<UiElement> hoverChain = new ArrayList<>();
    private float lastX = Float.NaN;
    private float lastY = Float.NaN;
    private UiElement pressed;
    private int pressButton = -1;
    private float pressX;
    private float pressY;
    private boolean dragChecked;
    private final List<UiElement> activeChain = new ArrayList<>();
    private UiElement captured;
    private ScrollbarDrag scrollbarDrag;
    private UiElement lastClick;
    private UiElement lastDownTarget;
    private int lastDownButton = -1;
    private double lastDownTime = Double.NEGATIVE_INFINITY;
    private float lastDownX;
    private float lastDownY;
    private int clickCount;
    /** Mouse buttons held, bit {@code 1 << button}, from every press/release the host reported. */
    private int heldButtons;
    /** Modifier bits as last reported by the host's events, kept current by modifier key presses. */
    private int currentMods;

    // keys and controller
    private final BitSet buttonsConsumed = new BitSet();
    private final BitSet keysDown = new BitSet();
    private final BitSet keysConsumed = new BitSet();
    private final boolean[] padDown = new boolean[GamepadButtons.COUNT];
    private final boolean[] padConsumed = new boolean[GamepadButtons.COUNT];
    private int repeatButton = -1;
    private UiAction repeatAction;
    private double repeatTimer;

    private record ScrollbarDrag(UiElement container, boolean vertical, float grab) {
    }

    private record Hit(UiElement target, boolean blocked) {
    }

    public UiInputRouter(UiDocumentInstance ui) {
        this(ui, InputSettings.DEFAULTS, UiActionMap.defaults());
    }

    public UiInputRouter(UiDocumentInstance ui, InputSettings settings, UiActionMap actionMap) {
        this.ui = Objects.requireNonNull(ui, "ui");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.actionMap = Objects.requireNonNull(actionMap, "actionMap");
        this.dispatcher = new EventDispatcher(this::handlerFailed);
        this.focus = new FocusManager(ui, dispatcher, this::time);
        this.tooltips = new TooltipController(this::settings, ui::pointerGhostShown);
        this.drag = new DragDropController(dispatcher, this::time, ui::root, ui::find);
        focus.setListener(this::focusChanged);
    }

    // ── accessors ───────────────────────────────────────────────────────────

    public UiDocumentInstance instance() {
        return ui;
    }

    public FocusManager focus() {
        return focus;
    }

    public TooltipController tooltips() {
        return tooltips;
    }

    public DragDropController drag() {
        return drag;
    }

    public EventDispatcher dispatcher() {
        return dispatcher;
    }

    public InputSettings settings() {
        return settings;
    }

    /**
     * Replaces this view's timings, from the next event on: a migrating screen sets its legacy
     * values (the furnace shows slot tooltips at once, #298).
     */
    public void setSettings(InputSettings s) {
        this.settings = Objects.requireNonNull(s, "settings");
    }

    public UiActionMap actionMap() {
        return actionMap;
    }

    /** Rebinds keys and buttons; hints follow at once. */
    public void setActionMap(UiActionMap map) {
        actionMap = Objects.requireNonNull(map, "map");
    }

    /** The device of the last input: decides which glyphs {@link ActionHints} show. */
    public InputDevice lastDevice() {
        return lastDevice;
    }

    /** Router clock, seconds; advanced only by {@link #tick}, so replays are deterministic. */
    public double time() {
        return time;
    }

    /** Mouse buttons held (bit {@code 1 << button}), as {@link PointerEvent#buttons()}. */
    public int heldButtons() {
        return heldButtons;
    }

    /** Modifier bits ({@code MKeys.MOD_*}) as last seen. */
    public int modifiers() {
        return currentMods;
    }

    /** The element the last {@link #pointerUp} clicked, or null. */
    public UiElement lastClick() {
        return lastClick;
    }

    /** The editing controller of a {@code TextField} (created on first use). */
    public TextFieldController textField(UiElement el) {
        if (el == null || !"TextField".equals(el.type()) || el.isRemoved()) {
            return null;
        }
        return textFields.computeIfAbsent(el, e -> new TextFieldController(e, dispatcher, this::time,
            () -> lineMetrics(e)));
    }

    /** The existing controller of {@code el}, without creating one (for painting). */
    public TextFieldController existingTextField(UiElement el) {
        return textFields.get(el);
    }

    public UiElement pointerCapture() {
        return captured;
    }

    /** Last pointer position over the frame in device pixels, or {@code NaN} after it left. */
    public float pointerX() {
        return lastX;
    }

    /** @see #pointerX() */
    public float pointerY() {
        return lastY;
    }

    /** All pointer input goes to {@code el} until the primary button is released or {@link #releasePointer}. */
    public boolean capturePointer(UiElement el) {
        if (!InputTraits.canReceivePointer(el)) {
            return false;
        }
        captured = el;
        return true;
    }

    public void releasePointer() {
        captured = null;
        scrollbarDrag = null;
    }

    // ── frame ───────────────────────────────────────────────────────────────

    /** Advances the router clock: controller repeats, tooltip delay, caret blink. */
    public void tick(double dt) {
        if (!(dt > 0)) {
            return;
        }
        time += dt;
        if (repeatAction != null) {
            repeatTimer -= dt;
            int guard = 0;
            while (repeatTimer <= 0 && repeatAction != null && guard++ < 8) {
                repeatTimer += Math.max(1e-3, settings.controllerRepeatInterval());
                perform(repeatAction, InputDevice.GAMEPAD, true);
            }
        }
        tooltips.tick(dt);
    }

    /**
     * Reconciles input state with the tree after layout: open/closed scopes, focus on an element
     * that can no longer have it, a capture, press or drag whose element left, the hover chain
     * under a still pointer, editing state of text fields changed from outside.
     */
    public void sync() {
        forgetReloaded(hoverChain, UiElement.HOVER);
        forgetReloaded(activeChain, UiElement.ACTIVE);
        if (pressed != null && pressed.isRemoved()) {
            pressed = null;
            pressButton = -1;
        }
        focus.sync();
        drag.validate();
        if (captured != null && !InputTraits.canReceivePointer(captured)) {
            cancelPointer(captured);
            captured = null;
            scrollbarDrag = null;
        }
        if (pressed != null && !InputTraits.canReceivePointer(pressed)) {
            cancelPointer(pressed);
            pressed = null;
            setActive(null);
        }
        if (!Float.isNaN(lastX) && captured == null && !drag.active()) {
            setHover(pick(lastX, lastY).target());
        } else {
            hoverChain.removeIf(UiElement::isRemoved);
        }
        tooltips.validate();
        textFields.keySet().removeIf(UiElement::isRemoved);
        for (TextFieldController tf : textFields.values()) {
            tf.sync();
        }
    }

    // ── pointer ─────────────────────────────────────────────────────────────

    /** Pointer moved to viewport pixel {@code (x, y)}. */
    public boolean pointerMove(float x, float y) {
        lastDevice = InputDevice.MOUSE;
        boolean inside = UiCoordinates.onCanvas(ui.metrics(), x, y);
        lastX = inside ? x : Float.NaN;
        lastY = inside ? y : Float.NaN;
        if (drag.active()) {
            UiElement under = inside ? pick(x, y).target() : null;
            setHover(under);
            drag.over(under, x, y);
            return true;
        }
        if (captured != null) {
            dragCaptured(x, y);
            return true;
        }
        if (!inside) {
            setHover(null);
            tooltips.pointer(null, x, y);
            return pressed != null;
        }
        Hit h = pick(x, y);
        setHover(h.target());
        tooltips.pointer(h.target(), x, y);
        if (maybeStartDrag(h.target(), x, y)) {
            return true;
        }
        if (InputTraits.canReceivePointer(h.target())) {
            dispatcher.dispatch(pointer(UiEventType.POINTER_MOVE, x, y, -1, currentMods, 0), h.target());
        }
        return h.target() != null || h.blocked() || pressed != null;
    }

    /** The pointer left the viewport (or the preview image). */
    public void pointerLeave() {
        lastX = Float.NaN;
        lastY = Float.NaN;
        if (captured == null && !drag.active()) {
            setHover(null);
        }
        tooltips.pointer(null, 0, 0);
    }

    public boolean pointerDown(float x, float y, int button, int mods) {
        currentMods = mods;
        if (button >= 0 && button < 31) {
            heldButtons |= 1 << button;
        }
        boolean consumed = down(x, y, button, mods);
        if (button >= 0) {
            buttonsConsumed.set(button, consumed);
        }
        return consumed;
    }

    private boolean down(float x, float y, int button, int mods) {
        lastDevice = InputDevice.MOUSE;
        if (!UiCoordinates.onCanvas(ui.metrics(), x, y)) {
            return false;
        }
        lastX = x;
        lastY = y;
        if (drag.active()) {
            return true;
        }
        Hit h = pick(x, y);
        setHover(h.target());
        tooltips.hide();
        if (dismissPopupsOutside(h.target())) {
            return true;
        }
        if (h.blocked()) {
            return true;
        }
        UiElement target = h.target();
        if (target == null) {
            if (focus.focused() != null) {
                focus.blur(InputDevice.MOUSE);
            }
            return false;
        }
        if (!InputTraits.canReceivePointer(target)) {
            return true; // a disabled element blocks without receiving anything
        }
        countClick(target, button, x, y);
        pressed = target;
        pressButton = button;
        pressX = x;
        pressY = y;
        dragChecked = false;
        if (button == PointerEvent.PRIMARY) {
            setActive(target);
        }
        PointerEvent down = dispatcher.dispatch(pointer(UiEventType.POINTER_DOWN, x, y, button, mods, clickCount),
            target);
        if (!down.isDefaultPrevented() && button == PointerEvent.PRIMARY && pressed == target) {
            if (!grabScrollbar(target, x, y)) {
                UiElement f = InputTraits.focusableAncestor(target);
                if (f != null) {
                    focus.focus(f, InputDevice.MOUSE);
                } else {
                    focus.blur(InputDevice.MOUSE);
                }
                TextFieldController tf = f == null ? null : textField(f);
                if (tf != null) {
                    tf.pointerDown(x, y, clickCount, (mods & MKeys.MOD_SHIFT) != 0, geometry(f), lineMetrics(f));
                    captured = f;
                }
            }
        }
        return true;
    }

    public boolean pointerUp(float x, float y, int button, int mods) {
        lastDevice = InputDevice.MOUSE;
        lastClick = null;
        currentMods = mods;
        if (button >= 0 && button < 31) {
            heldButtons &= ~(1 << button);
        }
        DragSession session = drag.session();
        if (session != null) {
            if (button == session.button()) {
                drag.over(UiCoordinates.onCanvas(ui.metrics(), x, y) ? pick(x, y).target() : null, x, y);
                drag.release();
                captured = null;
            }
            boolean pressConsumed = button >= 0 && buttonsConsumed.get(button);
            if (button >= 0) {
                buttonsConsumed.clear(button);
            }
            return pressConsumed || button == session.button();
        }
        boolean inside = UiCoordinates.onCanvas(ui.metrics(), x, y);
        Hit h = inside ? pick(x, y) : new Hit(null, false);
        boolean wasPressed = pressed != null && button == pressButton;
        // Only the primary release ends a capture, so only it is the capture's: a right press the
        // world took during a text-field drag must get its release back (#288 review).
        boolean endedCapture = captured != null && button == PointerEvent.PRIMARY;
        if (captured != null) {
            if (InputTraits.canReceivePointer(captured)) {
                dispatcher.dispatch(pointer(UiEventType.POINTER_UP, x, y, button, mods, clickCount), captured);
            }
            if (button == PointerEvent.PRIMARY) {
                releasePointer();
            }
        } else if (InputTraits.canReceivePointer(h.target())) {
            dispatcher.dispatch(pointer(UiEventType.POINTER_UP, x, y, button, mods, clickCount), h.target());
        }
        UiElement clicked = wasPressed && !h.blocked() && pressed == h.target()
            && InputTraits.canReceivePointer(pressed) ? pressed : null;
        if (button == pressButton) {
            pressed = null;
            pressButton = -1;
            setActive(null);
        }
        if (inside) {
            lastX = x;
            lastY = y;
            setHover(h.target());
        }
        if (clicked != null) {
            lastClick = clicked;
            dispatcher.dispatch(pointer(UiEventType.CLICK, x, y, button, mods, clickCount), clicked);
        }
        boolean pressConsumed = button >= 0 && buttonsConsumed.get(button);
        if (button >= 0) {
            buttonsConsumed.clear(button);
        }
        return pressConsumed || wasPressed || endedCapture;
    }

    /** Wheel notches at {@code (x, y)}; Shift turns vertical wheel into horizontal scrolling. */
    public boolean wheel(float x, float y, float dx, float dy, int mods) {
        lastDevice = InputDevice.MOUSE;
        currentMods = mods;
        if (!UiCoordinates.onCanvas(ui.metrics(), x, y)) {
            return false;
        }
        tooltips.hide();
        if (drag.active()) {
            return true;
        }
        Hit h = pick(x, y);
        if (h.blocked()) {
            return true;
        }
        if (h.target() == null) {
            return false;
        }
        if (!InputTraits.canReceivePointer(h.target())) {
            return true;
        }
        WheelEvent e = dispatcher.dispatch(new WheelEvent(time, x, y, dx, dy, mods), h.target());
        if (!e.isDefaultPrevented()) {
            float sx = dx;
            float sy = dy;
            if ((mods & MKeys.MOD_SHIFT) != 0 && sx == 0) {
                sx = sy;
                sy = 0;
            }
            scrollFrom(h.target(), -sx * settings.wheelStep() * ui.metrics().scale(),
                -sy * settings.wheelStep() * ui.metrics().scale());
        }
        return true;
    }

    // ── keyboard and text ───────────────────────────────────────────────────

    /** GLFW-style key callback: {@code action} is {@code MKeys.PRESS}, {@code REPEAT} or {@code RELEASE}. */
    public boolean key(int key, int action, int mods) {
        return switch (action) {
            case MKeys.PRESS -> keyDown(key, mods, false);
            case MKeys.REPEAT -> keyDown(key, mods, true);
            case MKeys.RELEASE -> keyUp(key, mods);
            default -> false;
        };
    }

    public boolean keyDown(int key, int mods, boolean repeat) {
        if (key < 0) {
            return false;
        }
        currentMods = mods | modifierBit(key);
        lastDevice = InputDevice.KEYBOARD;
        if (repeat) {
            if (!keysDown.get(key) || !keysConsumed.get(key)) {
                return false; // the press was not ours (or predates this document): neither is its repeat
            }
        } else {
            keysDown.set(key);
            keysConsumed.clear(key);
        }
        tooltips.hide();
        UiAction action = actionMap.actionForKey(key, mods);
        KeyEvent e = dispatcher.dispatch(new KeyEvent(UiEventType.KEY_DOWN, time, key, mods, repeat, action), keyTarget());
        boolean handled = e.isHandled();
        if (!e.isDefaultPrevented()) {
            TextFieldController tf = focusedTextField();
            if (!handled && tf != null) {
                handled = tf.key(key, mods);
            }
            if (!handled && action != null && (!repeat || action.repeats())) {
                handled = perform(action, InputDevice.KEYBOARD, repeat);
            }
        }
        if (repeat) {
            return true;
        }
        handled |= focus.activeModal() != null;
        if (handled) {
            keysConsumed.set(key);
        }
        return handled;
    }

    public boolean keyUp(int key, int mods) {
        if (key >= 0) {
            currentMods = mods & ~modifierBit(key);
        }
        if (key < 0 || !keysDown.get(key)) {
            return false;
        }
        keysDown.clear(key);
        UiAction action = actionMap.actionForKey(key, mods);
        dispatcher.dispatch(new KeyEvent(UiEventType.KEY_UP, time, key, mods, false, action), keyTarget());
        boolean consumed = keysConsumed.get(key);
        keysConsumed.clear(key);
        return consumed;
    }

    /** One code point of committed text. Lone surrogates and invalid code points are dropped. */
    public boolean text(int codePoint) {
        if (!Character.isValidCodePoint(codePoint) || codePoint <= 0xFFFF && Character.isSurrogate((char) codePoint)) {
            return false;
        }
        return text(Character.toString(codePoint));
    }

    public boolean text(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        lastDevice = InputDevice.KEYBOARD;
        TextInputEvent e = dispatcher.dispatch(new TextInputEvent(time, s), keyTarget());
        TextFieldController tf = focusedTextField();
        if (tf != null) {
            if (!e.isDefaultPrevented()) {
                tf.text(s);
            }
            return true;
        }
        return e.isHandled() || focus.activeModal() != null;
    }

    /** IME composition from a host that has a composition source. */
    public boolean composition(CompositionEvent.Phase phase, String text, int cursor) {
        lastDevice = InputDevice.KEYBOARD;
        CompositionEvent e = dispatcher.dispatch(new CompositionEvent(time, phase, text, cursor), keyTarget());
        TextFieldController tf = focusedTextField();
        if (tf != null) {
            if (!e.isDefaultPrevented()) {
                tf.composition(e);
            }
            return true;
        }
        return e.isHandled() || focus.activeModal() != null;
    }

    // ── controller and actions ──────────────────────────────────────────────

    /** A controller button changed (GLFW gamepad layout, {@link GamepadButtons}). */
    public boolean gamepadButton(int button, boolean down) {
        if (button < 0 || button >= GamepadButtons.COUNT) {
            return false;
        }
        lastDevice = InputDevice.GAMEPAD;
        UiAction action = actionMap.actionForButton(button);
        if (down) {
            if (padDown[button]) {
                return padConsumed[button];
            }
            padDown[button] = true;
            boolean handled = action != null && perform(action, InputDevice.GAMEPAD, false);
            handled |= focus.activeModal() != null;
            padConsumed[button] = handled;
            if (action != null && action.repeats()) {
                repeatButton = button;
                repeatAction = action;
                repeatTimer = settings.controllerRepeatDelay();
            }
            return handled;
        }
        if (!padDown[button]) {
            return false;
        }
        padDown[button] = false;
        if (repeatButton == button) {
            repeatButton = -1;
            repeatAction = null;
        }
        boolean consumed = padConsumed[button];
        padConsumed[button] = false;
        return consumed;
    }

    /**
     * Performs a UI action as if a bound key or button produced it: dispatches the navigation
     * event to the focused element (or the scope root), then runs the default action unless a
     * handler prevented it.
     *
     * @return true when the UI handled it
     */
    public boolean perform(UiAction action, InputDevice device, boolean repeat) {
        UiEventType type = switch (action) {
            case SUBMIT -> UiEventType.SUBMIT;
            case CANCEL -> UiEventType.CANCEL;
            default -> UiEventType.NAVIGATE;
        };
        tooltips.hide();
        NavigationEvent e = dispatcher.dispatch(new NavigationEvent(type, time, action, device, repeat), keyTarget());
        if (e.isHandled()) {
            return true;
        }
        DragSession dragging = drag.session();
        if (dragging != null && dragging.focusDriven() && action == UiAction.SUBMIT) {
            drag.drop(); // a controller pick-up drops on the focused slot instead of clicking it
            return true;
        }
        return switch (action) {
            case NAVIGATE_UP, NAVIGATE_DOWN, NAVIGATE_LEFT, NAVIGATE_RIGHT, NEXT, PREVIOUS -> {
                boolean moved = focus.navigate(action, device);
                tooltips.focus(focus.focused(), focus.focusVisible());
                if (dragging != null && dragging.focusDriven() && drag.session() == dragging) {
                    drag.hover(focus.focused());
                }
                yield moved;
            }
            case SUBMIT -> submit(device);
            case CANCEL -> cancelAction();
            case PAGE_UP, PAGE_DOWN -> page(action == UiAction.PAGE_DOWN);
        };
    }

    private boolean submit(InputDevice device) {
        UiElement f = focus.focused();
        if (f == null || !InputTraits.canFocus(f)) {
            return false;
        }
        TextFieldController tf = textField(f);
        if (tf != null) {
            tf.commit();
            return true;
        }
        lastClick = f;
        dispatcher.dispatch(new PointerEvent(UiEventType.CLICK, time, Float.NaN, Float.NaN, ui.metrics().scale(),
            PointerEvent.PRIMARY, 0, 1, device), f);
        return true;
    }

    private boolean cancelAction() {
        if (drag.active()) {
            drag.cancel(CancelReason.ESCAPE);
            releasePointer();
            return true;
        }
        List<FocusManager.ScopeEntry> scopes = focus.scopes();
        if (!scopes.isEmpty() && scopes.getLast().kind() == InputTraits.Scope.POPUP) {
            dismissPopup(scopes.getLast().element(), DismissReason.CANCEL);
            return true;
        }
        return false;
    }

    private boolean page(boolean down) {
        for (UiElement e = focus.focused() != null ? focus.focused() : null; e != null; e = e.parent()) {
            if (e.isScrollContainer() && e.maxScrollY() > 0) {
                e.scrollBy(0, (down ? 1 : -1) * e.rect().height() * 0.9f);
                return true;
            }
        }
        return false;
    }

    /**
     * Starts a focus-driven drag (a controller or keyboard "pick up" of {@code source}): it is
     * immediately over the focused element (else the source), focus navigation moves it, Submit
     * drops it on the acceptor and Cancel cancels it.
     */
    public DragSession startDrag(UiElement source, Object payload, InputDevice device) {
        DragSession s = drag.start(source, payload, device);
        tooltips.hide();
        UiElement f = focus.focused();
        drag.hover(f != null && InputTraits.canReceivePointer(f) ? f : source);
        return s;
    }

    // ── popups ──────────────────────────────────────────────────────────────

    /** Opens {@code popup} from code; {@code anchor} (may be null) is the element that opened it. */
    public void openPopup(UiElement popup, UiElement anchor, Consumer<DismissReason> onDismiss) {
        focus.openPopup(popup, anchor, onDismiss);
    }

    /**
     * Shows a declarative popup ({@code focusScope: popup}): clears the local
     * {@code display: none} an earlier dismissal wrote and, when the authored style still
     * collapses it, writes a local {@code display: flex}. The scope opens on the next
     * {@link #sync}.
     */
    public void showPopup(UiElement popup) {
        popup.clearStyle("display");
        ui.resolveStyles();
        if (popup.computedStyle().collapsed()) {
            popup.setStyle("display", com.openmason.engine.format.omui.UiValue.of("flex"));
        }
    }

    /**
     * Sends {@code DISMISS}; unless a handler prevents it, closes the popup: a declarative popup
     * gets a local {@code display: none}, a popup opened from code runs its callback.
     *
     * @return true when the popup closed
     */
    public boolean dismissPopup(UiElement popup, DismissReason reason) {
        FocusManager.ScopeEntry entry = focus.entryOf(popup);
        if (entry == null || entry.kind() != InputTraits.Scope.POPUP) {
            return false;
        }
        DismissEvent e = dispatcher.dispatch(new DismissEvent(time, reason), popup);
        if (e.isDefaultPrevented()) {
            return false;
        }
        if (entry.declarative()) {
            popup.setStyle("display", com.openmason.engine.format.omui.UiValue.of("none"));
        }
        focus.close(popup);
        if (entry.onDismiss() != null) {
            entry.onDismiss().accept(reason);
        }
        return true;
    }

    /** A press outside the open popups dismisses them top-down; the press is then consumed. */
    private boolean dismissPopupsOutside(UiElement target) {
        boolean any = false;
        List<FocusManager.ScopeEntry> scopes = new ArrayList<>(focus.scopes());
        for (int i = scopes.size() - 1; i >= 0; i--) {
            FocusManager.ScopeEntry entry = scopes.get(i);
            if (entry.kind() != InputTraits.Scope.POPUP) {
                break;
            }
            if (target != null && InputTraits.isInside(target, entry.element())) {
                break;
            }
            any = true;
            if (!dismissPopup(entry.element(), DismissReason.OUTSIDE_POINTER)) {
                break; // a popup that refused to close keeps the popups beneath it open too
            }
            if (entry.anchor() != null && target != null && InputTraits.isInside(target, entry.anchor())) {
                break; // pressing the opener closes its popup instead of reopening it
            }
        }
        return any;
    }

    // ── lifecycle ───────────────────────────────────────────────────────────

    /**
     * Abandons every in-flight interaction: the drag ends with {@code reason} (exactly one
     * {@code DRAG_END}), a capture or press gets {@code POINTER_CANCEL}, held keys and controller
     * buttons are forgotten (their later releases are ignored), composition is cancelled and the
     * tooltip hides. Window focus loss and screen close also dismiss popups and clear hover;
     * screen close also drops focus.
     */
    public void cancelInteractions(CancelReason reason) {
        if (reason == CancelReason.SCREEN_CLOSED || reason == CancelReason.DISCONNECT) {
            drag.abandon(reason); // a late reply to an earlier drop no longer applies
        } else {
            drag.cancel(reason);
        }
        heldButtons = 0;
        currentMods = 0;
        if (captured != null) {
            cancelPointer(captured);
            releasePointer();
        }
        if (pressed != null) {
            cancelPointer(pressed);
            pressed = null;
            pressButton = -1;
            setActive(null);
        }
        keysDown.clear();
        keysConsumed.clear();
        buttonsConsumed.clear();
        java.util.Arrays.fill(padDown, false);
        java.util.Arrays.fill(padConsumed, false);
        repeatAction = null;
        repeatButton = -1;
        TextFieldController tf = focusedTextField();
        if (tf != null && tf.model().isComposing()) {
            tf.model().cancelComposition();
        }
        tooltips.clear();
        if (reason == CancelReason.WINDOW_FOCUS_LOST || reason == CancelReason.SCREEN_CLOSED) {
            List<FocusManager.ScopeEntry> scopes = new ArrayList<>(focus.scopes());
            for (int i = scopes.size() - 1; i >= 0; i--) {
                if (scopes.get(i).kind() == InputTraits.Scope.POPUP) {
                    dismissPopup(scopes.get(i).element(), DismissReason.INTERACTION_CANCELLED);
                }
            }
            lastX = Float.NaN;
            lastY = Float.NaN;
            setHover(null);
        }
        if (reason == CancelReason.SCREEN_CLOSED) {
            focus.blur(InputDevice.PROGRAM);
        }
    }

    public void windowFocusLost() {
        cancelInteractions(CancelReason.WINDOW_FOCUS_LOST);
    }

    public void screenClosed() {
        cancelInteractions(CancelReason.SCREEN_CLOSED);
    }

    // ── internals ───────────────────────────────────────────────────────────

    private Hit pick(float x, float y) {
        UiElement target = ui.hitTest(x, y);
        if (focus.activeModal() != null && (target == null || focus.blocks(target))) {
            return new Hit(null, true);
        }
        return new Hit(target, false);
    }

    private UiElement keyTarget() {
        UiElement f = focus.focused();
        return f != null && InputTraits.canFocus(f) ? f : focus.scopeRoot();
    }

    private TextFieldController focusedTextField() {
        UiElement f = focus.focused();
        return f != null && InputTraits.canFocus(f) ? textField(f) : null;
    }

    private void focusChanged(UiElement previous, UiElement current, InputDevice device) {
        TextFieldController before = previous == null ? null : textFields.get(previous);
        if (before != null) {
            before.focusOut();
        }
        TextFieldController after = textField(current);
        if (after != null) {
            after.focusIn(device);
        }
        tooltips.focus(current, focus.focusVisible());
    }

    /**
     * A live reload replaces elements, and each rebuilt twin inherits its predecessor's
     * {@code :hover}/{@code :active}. The router's chains still hold the old objects, so clear the
     * state on the twins; the next hover pass sets it again where it belongs.
     */
    private void forgetReloaded(List<UiElement> chain, String state) {
        boolean any = false;
        for (UiElement e : chain) {
            if (e.isRemoved()) {
                any = true;
                UiElement twin = ui.find(e.key());
                if (twin != null && twin != e) {
                    twin.setState(state, false);
                }
            }
        }
        if (any) {
            for (UiElement e : chain) {
                if (!e.isRemoved()) {
                    e.setState(state, false);
                }
            }
            chain.clear();
        }
    }

    private void setHover(UiElement target) {
        if (sameChain(target, hoverChain)) {
            return; // the common per-frame case: nothing to restyle, nothing to allocate
        }
        List<UiElement> next = new ArrayList<>();
        for (UiElement e = target; e != null; e = e.parent()) {
            next.add(e);
        }
        for (UiElement old : hoverChain) {
            if (!next.contains(old) && !old.isRemoved()) {
                old.setState(UiElement.HOVER, false);
                if (InputTraits.canReceivePointer(old)) {
                    dispatcher.dispatch(pointer(UiEventType.POINTER_LEAVE, lastX, lastY, -1, currentMods, 0), old);
                }
            }
        }
        for (int i = next.size() - 1; i >= 0; i--) {
            UiElement e = next.get(i);
            if (!hoverChain.contains(e)) {
                e.setState(UiElement.HOVER, true);
                if (InputTraits.canReceivePointer(e)) {
                    dispatcher.dispatch(pointer(UiEventType.POINTER_ENTER, lastX, lastY, -1, currentMods, 0), e);
                }
            }
        }
        hoverChain.clear();
        hoverChain.addAll(next);
    }

    private void setActive(UiElement target) {
        if (sameChain(target, activeChain)) {
            return;
        }
        List<UiElement> next = new ArrayList<>();
        for (UiElement e = target; e != null; e = e.parent()) {
            next.add(e);
        }
        for (UiElement old : activeChain) {
            if (!next.contains(old) && !old.isRemoved()) {
                old.setState(UiElement.ACTIVE, false);
            }
        }
        for (UiElement e : next) {
            if (!activeChain.contains(e)) {
                e.setState(UiElement.ACTIVE, true);
            }
        }
        activeChain.clear();
        activeChain.addAll(next);
    }

    /** Multi-click counting: same element, same button, within the time and distance limits. */
    /** {@code chain} is exactly {@code target} and its ancestors, none removed. */
    private static boolean sameChain(UiElement target, List<UiElement> chain) {
        int i = 0;
        for (UiElement e = target; e != null; e = e.parent()) {
            if (i >= chain.size() || chain.get(i) != e || e.isRemoved()) {
                return false;
            }
            i++;
        }
        return i == chain.size();
    }

    private void countClick(UiElement target, int button, float x, float y) {
        float slop = settings.doubleClickDistance() * ui.metrics().scale();
        boolean again = target == lastDownTarget && button == lastDownButton
            && time - lastDownTime <= settings.doubleClickTime()
            && Math.abs(x - lastDownX) <= slop && Math.abs(y - lastDownY) <= slop;
        clickCount = again ? clickCount + 1 : 1;
        lastDownTarget = target;
        lastDownButton = button;
        lastDownTime = time;
        lastDownX = x;
        lastDownY = y;
    }

    private boolean maybeStartDrag(UiElement under, float x, float y) {
        if (pressed == null || pressButton != PointerEvent.PRIMARY || dragChecked) {
            return false;
        }
        float threshold = settings.dragThreshold() * ui.metrics().scale();
        if (Math.abs(x - pressX) <= threshold && Math.abs(y - pressY) <= threshold) {
            return false;
        }
        dragChecked = true;
        UiElement source = null;
        for (UiElement e = pressed; e != null; e = e.parent()) {
            if (InputTraits.draggable(e)) {
                source = e;
                break;
            }
        }
        if (source == null || !InputTraits.canReceivePointer(source)) {
            return false;
        }
        DragSession session = drag.tryStart(source, pressX, pressY, pressButton);
        if (session == null) {
            return false;
        }
        pressed = null;
        pressButton = -1;
        setActive(null);
        tooltips.hide();
        drag.over(under, x, y);
        return true;
    }

    private void dragCaptured(float x, float y) {
        if (!InputTraits.canReceivePointer(captured)) {
            releasePointer();
            return;
        }
        dispatcher.dispatch(pointer(UiEventType.POINTER_MOVE, x, y, -1, currentMods, 0), captured);
        if (scrollbarDrag != null) {
            float scale = ui.metrics().scale();
            UiElement c = scrollbarDrag.container();
            ScrollbarGeometry bar = scrollbarDrag.vertical() ? ScrollbarGeometry.vertical(c, scale)
                : ScrollbarGeometry.horizontal(c, scale);
            if (bar != null) {
                float start = (scrollbarDrag.vertical() ? y : x) - scrollbarDrag.grab();
                if (scrollbarDrag.vertical()) {
                    c.scrollTo(c.scrollX(), bar.offsetFor(start, true, c.maxScrollY()));
                } else {
                    c.scrollTo(bar.offsetFor(start, false, c.maxScrollX()), c.scrollY());
                }
            }
            return;
        }
        TextFieldController tf = textFields.get(captured);
        if (tf != null) {
            tf.pointerDrag(x, y, geometry(captured), lineMetrics(captured));
        }
    }

    private boolean grabScrollbar(UiElement target, float x, float y) {
        float scale = ui.metrics().scale();
        for (UiElement e = target; e != null; e = e.parent()) {
            if (!e.isScrollContainer()) {
                continue;
            }
            for (boolean vertical : new boolean[]{true, false}) {
                ScrollbarGeometry bar = vertical ? ScrollbarGeometry.vertical(e, scale) : ScrollbarGeometry.horizontal(e, scale);
                if (bar != null && bar.track().contains(x, y)) {
                    UiRect thumb = bar.thumb();
                    float grab = thumb.contains(x, y)
                        ? (vertical ? y - thumb.y() : x - thumb.x())
                        : (vertical ? thumb.height() : thumb.width()) / 2f;
                    captured = e;
                    scrollbarDrag = new ScrollbarDrag(e, vertical, grab);
                    dragCaptured(x, y);
                    return true;
                }
            }
        }
        return false;
    }

    /** Scrolls the nearest container at or above {@code from} that can still move each way. */
    private void scrollFrom(UiElement from, float dx, float dy) {
        for (UiElement e = from; e != null && (dx != 0 || dy != 0); e = e.parent()) {
            if (!e.isScrollContainer()) {
                continue;
            }
            float beforeX = e.scrollX();
            float beforeY = e.scrollY();
            e.scrollBy(dx, dy);
            if (e.scrollX() != beforeX) {
                dx = 0;
            }
            if (e.scrollY() != beforeY) {
                dy = 0;
            }
        }
    }

    private void cancelPointer(UiElement el) {
        if (!el.isRemoved()) {
            dispatcher.dispatch(pointer(UiEventType.POINTER_CANCEL, lastX, lastY, -1, 0, 0), el);
        }
    }

    private PointerEvent pointer(UiEventType type, float x, float y, int button, int mods, int clicks) {
        return new PointerEvent(type, time, x, y, ui.metrics().scale(), button, mods, clicks, InputDevice.MOUSE,
            heldButtons);
    }

    /** GLFW modifier keys: left/right Shift, Control, Alt, Super (340-347). */
    private static int modifierBit(int key) {
        return switch (key) {
            case 340, 344 -> MKeys.MOD_SHIFT;
            case 341, 345 -> MKeys.MOD_CONTROL;
            case 342, 346 -> MKeys.MOD_ALT;
            case 343, 347 -> MKeys.MOD_SUPER;
            default -> 0;
        };
    }

    TextLineMetrics lineMetrics(UiElement el) {
        return ui.context().measurer().textLine(el, ui.metrics().scale());
    }

    TextFieldGeometry geometry(UiElement el) {
        TextFieldController tf = textFields.get(el);
        return TextFieldGeometry.of(el, lineMetrics(el), ui.metrics().scale(), tf != null && tf.multiline());
    }

    private void handlerFailed(UiEvent event, RuntimeException ex) {
        String where = event.currentTarget() == null ? "" : event.currentTarget().key();
        LOGGER.warn("UI event handler failed on {} for {}", where, event.type(), ex);
        ui.reportDiagnostic(UiRuntimeDiagnostic.error(UiRuntimeDiagnostic.Code.EVENT_HANDLER_FAILED, where,
            event.type() + " handler threw " + ex));
    }
}
