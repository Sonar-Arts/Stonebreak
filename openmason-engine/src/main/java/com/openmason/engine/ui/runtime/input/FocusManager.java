package com.openmason.engine.ui.runtime.input;

import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

/**
 * Keyboard/controller focus of one document (#288): which element has it, the tab order,
 * directional navigation, and the stack of focus scopes (modals and popups) that trap it.
 *
 * <p><b>Scopes.</b> An element with {@code focusScope: modal} or {@code popup} opens a scope
 * while it is visible (or one is opened from code). The topmost scope confines Tab, directional
 * navigation and focus; a modal additionally blocks pointer input to everything outside it
 * (popups opened above it stay usable). Opening a scope moves focus inside (its
 * {@code autofocus} element, else the first in tab order) and remembers where focus was;
 * closing it restores that element if it can still take focus.
 *
 * <p><b>Losing focus.</b> When the focused element is removed, disabled, collapsed, hidden or
 * falls outside the active scope, focus moves to the next element that followed it in
 * navigation order (then the previous one), so a controller player is never left without a
 * focus; a virtualized list that recycles the focused element calls {@link #recycled}.
 * Keyboard and controller focus is indicated with {@code :focus-visible}; pointer focus is not.
 */
public final class FocusManager {

    /** One open scope. */
    public static final class ScopeEntry {
        private UiElement element;
        private final InputTraits.Scope kind;
        private final boolean declarative;
        private final UiElement anchor;
        private final Consumer<DismissReason> onDismiss;
        private UiElement restore;
        private boolean restoreVisible;

        ScopeEntry(UiElement element, InputTraits.Scope kind, boolean declarative, UiElement anchor,
                   Consumer<DismissReason> onDismiss) {
            this.element = element;
            this.kind = kind;
            this.declarative = declarative;
            this.anchor = anchor;
            this.onDismiss = onDismiss;
        }

        public UiElement element() {
            return element;
        }

        public InputTraits.Scope kind() {
            return kind;
        }

        /** Opened by {@code focusScope} on a visible element (as opposed to from code). */
        public boolean declarative() {
            return declarative;
        }

        /** The element that opened a popup (a dropdown button); presses on it dismiss rather than reopen. */
        public UiElement anchor() {
            return anchor;
        }

        Consumer<DismissReason> onDismiss() {
            return onDismiss;
        }
    }

    /** Told after every focus change. */
    public interface Listener {
        void focusChanged(UiElement previous, UiElement current, InputDevice device);
    }

    private final UiDocumentInstance ui;
    private final EventDispatcher dispatcher;
    private final DoubleSupplier clock;
    private final List<ScopeEntry> scopes = new ArrayList<>();
    private Listener listener = (a, b, c) -> {
    };
    private UiElement focused;
    private boolean visible;
    /** Navigation order when focus was last confirmed; reused buffers, so a steady frame allocates nothing. */
    private List<UiElement> lastOrder = new ArrayList<>();
    private List<UiElement> spareOrder = new ArrayList<>();
    private int lastIndex = -1;
    private final List<UiElement> declaredScratch = new ArrayList<>();
    private final List<ScopeEntry> goneScratch = new ArrayList<>();

    public FocusManager(UiDocumentInstance ui, EventDispatcher dispatcher, DoubleSupplier clock) {
        this.ui = ui;
        this.dispatcher = dispatcher;
        this.clock = clock;
    }

    void setListener(Listener l) {
        listener = l;
    }

    // ── queries ─────────────────────────────────────────────────────────────

    public UiElement focused() {
        return focused;
    }

    /** Focus is indicated ({@code :focus-visible}): it came from the keyboard or a controller. */
    public boolean focusVisible() {
        return focused != null && visible;
    }

    /** Open scopes, bottom to top. */
    public List<ScopeEntry> scopes() {
        return Collections.unmodifiableList(scopes);
    }

    /** The element focus is confined to: the topmost scope, or the document root. */
    public UiElement scopeRoot() {
        return scopes.isEmpty() ? ui.root() : scopes.getLast().element;
    }

    /** The topmost open modal, or null. */
    public UiElement activeModal() {
        for (int i = scopes.size() - 1; i >= 0; i--) {
            if (scopes.get(i).kind == InputTraits.Scope.MODAL) {
                return scopes.get(i).element;
            }
        }
        return null;
    }

    /**
     * True when a modal hides {@code target} from pointer input: a modal is open and the target
     * is neither inside it nor inside a scope opened above it.
     */
    public boolean blocks(UiElement target) {
        int modal = -1;
        for (int i = scopes.size() - 1; i >= 0; i--) {
            if (scopes.get(i).kind == InputTraits.Scope.MODAL) {
                modal = i;
                break;
            }
        }
        if (modal < 0 || target == null) {
            return false;
        }
        for (int i = modal; i < scopes.size(); i++) {
            if (InputTraits.isInside(target, scopes.get(i).element)) {
                return false;
            }
        }
        return true;
    }

    /** Inside the topmost scope (anywhere when none is open). */
    public boolean inActiveScope(UiElement el) {
        return InputTraits.isInside(el, scopeRoot());
    }

    /** Focusable elements of the active scope in Tab order (positive tabIndex first, then tree order). */
    public List<UiElement> tabOrder() {
        List<UiElement> all = navigationOrder();
        List<UiElement> out = new ArrayList<>();
        for (UiElement e : all) {
            if (InputTraits.tabIndex(e) >= 0) {
                out.add(e);
            }
        }
        out.sort((a, b) -> Integer.compare(rank(a), rank(b))); // stable: tree order within a rank
        return out;
    }

    private static int rank(UiElement e) {
        int t = InputTraits.tabIndex(e);
        return t > 0 ? t : Integer.MAX_VALUE;
    }

    /** Every element that can take focus in the active scope, in tree order. */
    public List<UiElement> navigationOrder() {
        List<UiElement> out = new ArrayList<>();
        collect(scopeRoot(), out);
        return out;
    }

    private static void collect(UiElement el, List<UiElement> out) {
        if (el.computedStyle().collapsed()) {
            return;
        }
        if (InputTraits.canFocus(el)) {
            out.add(el);
        }
        for (UiElement c : el.children()) {
            collect(c, out);
        }
    }

    // ── focusing ────────────────────────────────────────────────────────────

    /**
     * Focuses {@code el} if it can take focus and is inside the active scope.
     *
     * @return true when {@code el} is focused afterwards
     */
    public boolean focus(UiElement el, InputDevice device) {
        if (el == null) {
            blur(device);
            return true;
        }
        if (!InputTraits.canFocus(el) || !inActiveScope(el)) {
            return false;
        }
        set(el, device);
        return true;
    }

    public void blur(InputDevice device) {
        set(null, device);
    }

    /** Focuses the active scope's {@code autofocus} element, else the first in tab order. */
    public boolean focusFirst(InputDevice device) {
        UiElement first = firstIn(scopeRoot());
        if (first == null) {
            return false;
        }
        set(first, device);
        return true;
    }

    private UiElement firstIn(UiElement scope) {
        List<UiElement> order = new ArrayList<>();
        collect(scope, order);
        for (UiElement e : order) {
            if (InputTraits.autofocus(e)) {
                return e;
            }
        }
        List<UiElement> tab = new ArrayList<>();
        for (UiElement e : order) {
            if (InputTraits.tabIndex(e) >= 0) {
                tab.add(e);
            }
        }
        tab.sort((a, b) -> Integer.compare(rank(a), rank(b)));
        return tab.isEmpty() ? (order.isEmpty() ? null : order.getFirst()) : tab.getFirst();
    }

    /**
     * Moves focus for a navigation action: Tab order for {@code NEXT}/{@code PREVIOUS} (wrapping
     * inside the scope), the explicit {@code nav*} neighbour or the spatial search for
     * directions. With nothing focused, any navigation focuses the scope's first element.
     *
     * @return true when focus is in this document afterwards (the action was the UI's)
     */
    public boolean navigate(UiAction action, InputDevice device) {
        if (focused == null || !InputTraits.canFocus(focused)) {
            return focusFirst(device);
        }
        UiElement next = switch (action) {
            case NEXT, PREVIOUS -> cycle(action == UiAction.NEXT);
            case NAVIGATE_UP, NAVIGATE_DOWN, NAVIGATE_LEFT, NAVIGATE_RIGHT -> direction(action);
            default -> null;
        };
        if (next != null) {
            set(next, device);
        } else if (device.showsFocus() && !visible) {
            set(focused, device); // a key press on pointer focus only reveals it
        }
        return true;
    }

    private UiElement cycle(boolean forward) {
        List<UiElement> order = tabOrder();
        if (order.isEmpty()) {
            return null;
        }
        int i = order.indexOf(focused);
        if (i < 0) {
            return forward ? order.getFirst() : order.getLast();
        }
        int n = order.size();
        return order.get(((forward ? i + 1 : i - 1) % n + n) % n);
    }

    private UiElement direction(UiAction action) {
        String explicit = InputTraits.explicitNeighbour(focused, action);
        if (explicit != null) {
            UiElement target = resolve(focused, explicit);
            if (target != null && InputTraits.canFocus(target) && inActiveScope(target)) {
                return target;
            }
            ui.reportDiagnostic(UiRuntimeDiagnostic.warning(UiRuntimeDiagnostic.Code.NAV_TARGET_MISSING,
                focused.key(), "navigation target " + explicit + " is missing or cannot take focus"));
        }
        List<UiElement> candidates = new ArrayList<>();
        for (UiElement c : navigationOrder()) {
            if (reachable(c)) {
                candidates.add(c);
            }
        }
        UiElement group = group(focused);
        if (group != null) {
            List<UiElement> inGroup = new ArrayList<>();
            for (UiElement c : candidates) {
                if (InputTraits.isInside(c, group)) {
                    inGroup.add(c);
                }
            }
            UiElement hit = SpatialNavigator.find(focused, inGroup, action);
            if (hit != null) {
                return hit;
            }
        }
        return SpatialNavigator.find(focused, candidates, action);
    }

    /**
     * An element scrolled out of its scroll container's view is a directional candidate only
     * while focus is already inside that container: a player walks through a list (which then
     * scrolls), but never jumps from outside onto a row nobody can see.
     */
    private boolean reachable(UiElement candidate) {
        for (UiElement c = candidate.parent(); c != null; c = c.parent()) {
            if (c.clipsChildren() && !overlaps(candidate.rect(), c.rect())) {
                return InputTraits.isInside(focused, c);
            }
        }
        return true;
    }

    private static boolean overlaps(com.openmason.engine.ui.runtime.UiRect a, com.openmason.engine.ui.runtime.UiRect b) {
        return a.x() < b.right() && b.x() < a.right() && a.y() < b.bottom() && b.y() < a.bottom();
    }

    /** {@code nav*} values are node keys relative to the element's own component scope, then absolute. */
    private UiElement resolve(UiElement from, String key) {
        int slash = from.key().lastIndexOf('/');
        if (slash >= 0) {
            UiElement local = ui.find(from.key().substring(0, slash + 1) + key);
            if (local != null) {
                return local;
            }
        }
        return ui.find(key);
    }

    private UiElement group(UiElement el) {
        UiElement root = scopeRoot();
        for (UiElement e = el.parent(); e != null && e != root; e = e.parent()) {
            if (InputTraits.scope(e) == InputTraits.Scope.GROUP) {
                return e;
            }
        }
        return null;
    }

    private void set(UiElement el, InputDevice device) {
        boolean nextVisible = el != null && (device == InputDevice.PROGRAM ? visible : device.showsFocus());
        if (el == focused) {
            if (el != null && nextVisible != visible) {
                visible = nextVisible;
                el.setState(UiElement.FOCUS_VISIBLE, visible);
            }
            return;
        }
        UiElement old = focused;
        focused = el;
        visible = nextVisible;
        double now = clock.getAsDouble();
        if (old != null && !old.isRemoved()) {
            old.setState(UiElement.FOCUS, false);
            old.setState(UiElement.FOCUS_VISIBLE, false);
        }
        if (el != null) {
            el.setState(UiElement.FOCUS, true);
            el.setState(UiElement.FOCUS_VISIBLE, visible);
            remember(el);
        }
        if (old != null && !old.isRemoved()) {
            dispatcher.dispatch(new FocusEvent(UiEventType.FOCUS_OUT, now, el, device), old);
            dispatcher.dispatch(new FocusEvent(UiEventType.BLUR, now, el, device), old);
        }
        if (el != null && el == focused) {
            dispatcher.dispatch(new FocusEvent(UiEventType.FOCUS_IN, now, old, device), el);
            dispatcher.dispatch(new FocusEvent(UiEventType.FOCUS, now, old, device), el);
            if (device != InputDevice.MOUSE) {
                el.scrollIntoView();
            }
        }
        listener.focusChanged(old, focused, device);
    }

    private void remember(UiElement el) {
        List<UiElement> order = spareOrder;
        order.clear();
        collect(scopeRoot(), order);
        spareOrder = lastOrder;
        lastOrder = order;
        lastIndex = order.indexOf(el);
    }

    // ── scopes ──────────────────────────────────────────────────────────────

    /** Opens a modal scope from code (a dialog the host shows). */
    public void openModal(UiElement element) {
        open(element, InputTraits.Scope.MODAL, false, null, null);
    }

    /** Opens a popup scope from code; {@code onDismiss} runs when it is dismissed. */
    public void openPopup(UiElement element, UiElement anchor, Consumer<DismissReason> onDismiss) {
        open(element, InputTraits.Scope.POPUP, false, anchor, onDismiss);
    }

    /**
     * Closes a scope opened from code. A declarative scope follows its element's visibility: hide
     * the element (or dismiss the popup) to close it; closing it here only lasts until the next
     * {@link #sync} sees it still visible.
     */
    public void close(UiElement element) {
        for (int i = scopes.size() - 1; i >= 0; i--) {
            if (scopes.get(i).element == element) {
                closeAt(i);
                return;
            }
        }
    }

    public boolean isOpen(UiElement element) {
        return entryOf(element) != null;
    }

    ScopeEntry entryOf(UiElement element) {
        for (ScopeEntry e : scopes) {
            if (e.element == element) {
                return e;
            }
        }
        return null;
    }

    private void open(UiElement element, InputTraits.Scope kind, boolean declarative, UiElement anchor,
                      Consumer<DismissReason> onDismiss) {
        if (element == null || isOpen(element)) {
            return;
        }
        ScopeEntry entry = new ScopeEntry(element, kind, declarative, anchor, onDismiss);
        entry.restore = focused;
        entry.restoreVisible = visible;
        scopes.add(entry);
        UiElement first = firstIn(element);
        if (first != null) {
            set(first, entry.restoreVisible ? InputDevice.KEYBOARD : InputDevice.PROGRAM);
        } else if (focused != null && !inActiveScope(focused)) {
            set(null, InputDevice.PROGRAM);
        }
    }

    private void closeAt(int index) {
        ScopeEntry entry = scopes.remove(index);
        boolean focusWasInside = focused == null || InputTraits.isInside(focused, entry.element)
            || index == scopes.size();
        if (!focusWasInside) {
            return;
        }
        UiElement back = live(entry.restore);
        if (back != null && InputTraits.canFocus(back) && inActiveScope(back)) {
            set(back, entry.restoreVisible ? InputDevice.KEYBOARD : InputDevice.MOUSE);
        } else if (focused != null && (!inActiveScope(focused) || !InputTraits.canFocus(focused))) {
            if (!(visible && focusFirst(InputDevice.KEYBOARD))) {
                set(null, InputDevice.PROGRAM);
            }
        }
    }

    /**
     * Reconciles declarative scopes with what is visible, re-resolves elements a live reload
     * replaced, and moves focus off an element that can no longer have it. The router calls
     * this after every layout and before handling input.
     */
    public void sync() {
        for (ScopeEntry e : scopes) {
            UiElement twin = live(e.element);
            if (twin != null) {
                e.element = twin;
            }
        }
        List<UiElement> declared = declaredScratch;
        declared.clear();
        for (UiElement el : ui.elements()) {
            InputTraits.Scope k = InputTraits.scope(el);
            if ((k == InputTraits.Scope.MODAL || k == InputTraits.Scope.POPUP) && el.isVisible()) {
                declared.add(el);
            }
        }
        List<ScopeEntry> gone = goneScratch;
        gone.clear();
        for (int i = scopes.size() - 1; i >= 0; i--) {
            ScopeEntry e = scopes.get(i);
            if (e.element.isRemoved() || !e.element.isVisible() || e.declarative && !declared.contains(e.element)) {
                gone.add(e);
            }
        }
        // Callbacks may open or close scopes, or even sync again (which reuses the scratch lists):
        // iterate over copies, made only when there is something to do.
        for (ScopeEntry e : gone.isEmpty() ? List.<ScopeEntry>of() : List.copyOf(gone)) {
            int at = scopes.indexOf(e);
            if (at >= 0) {
                closeAt(at);
                if (e.onDismiss != null) {
                    e.onDismiss.accept(DismissReason.HIDDEN);
                }
            }
        }
        boolean opening = false;
        for (int i = 0; i < declared.size() && !opening; i++) {
            opening = !isOpen(declared.get(i));
        }
        if (opening) {
            for (UiElement el : List.copyOf(declared)) {
                if (!isOpen(el)) {
                    open(el, InputTraits.scope(el), true, null, null);
                }
            }
        }
        validate();
    }

    private void validate() {
        if (focused == null) {
            return;
        }
        if (focused.isRemoved()) {
            UiElement twin = live(focused);
            if (twin != null) {
                focused = twin; // live reload: the twin carries the :focus state
                listener.focusChanged(null, twin, InputDevice.PROGRAM); // its editor starts fresh
            }
        }
        if (!focused.isRemoved() && InputTraits.canFocus(focused) && inActiveScope(focused)) {
            remember(focused);
            return;
        }
        lose();
    }

    /** Focus moves to what followed the lost element in navigation order, then what preceded it. */
    private void lose() {
        UiElement replacement = null;
        for (int i = lastIndex + 1; i < lastOrder.size() && replacement == null; i++) {
            replacement = usable(lastOrder.get(i));
        }
        for (int i = Math.min(lastIndex, lastOrder.size()) - 1; i >= 0 && replacement == null; i--) {
            replacement = usable(lastOrder.get(i));
        }
        if (replacement == null) {
            replacement = firstIn(scopeRoot());
        }
        set(replacement, InputDevice.PROGRAM);
    }

    private UiElement usable(UiElement e) {
        UiElement l = e.isRemoved() ? live(e) : e;
        return l != null && InputTraits.canFocus(l) && inActiveScope(l) ? l : null;
    }

    /**
     * A virtualized container reused {@code element} for another item. When it (or something
     * inside it) had focus, focus follows the item to {@code replacement} (the element now
     * showing it), or, when the item is no longer realized, moves as if the element were
     * removed.
     */
    public void recycled(UiElement element, UiElement replacement) {
        if (focused == null || !InputTraits.isInside(focused, element)) {
            return;
        }
        if (replacement != null && InputTraits.canFocus(replacement) && inActiveScope(replacement)) {
            set(replacement, InputDevice.PROGRAM);
        } else {
            lose();
        }
    }

    /** {@code el}, or its rebuilt twin after a live reload, or null when it is gone. */
    UiElement live(UiElement el) {
        if (el == null) {
            return null;
        }
        if (!el.isRemoved()) {
            return el;
        }
        UiElement twin = ui.find(el.key());
        return twin == null || twin.isRemoved() ? null : twin;
    }
}
