package com.openmason.engine.ui.runtime.input;

import com.openmason.engine.ui.runtime.UiElement;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Drag and drop negotiation of one router (#288); see {@link DragEvent} for the event
 * sequence and {@link DragSession} for the guarantees. At most one drag is live. Every way a
 * drag can end funnels through {@link #end}, which sends the single {@code DRAG_END}: to the
 * source, or, when the source itself was removed, to the document root, so whoever owns the
 * payload always hears that the drag is over.
 *
 * <p><b>Rebuilt elements.</b> A list that re-renders a slot (an inventory sync, a ListView diff)
 * replaces the element with a twin under the same key. The drag follows the twin instead of
 * cancelling: the source and the accepting target are re-resolved by key, and only a key that
 * has no live element any more cancels ({@code SOURCE_REMOVED} / {@code TARGET_REMOVED}).
 *
 * <p><b>Focus-driven drags.</b> A drag started from code for a controller or keyboard
 * ({@link #start(UiElement, Object, InputDevice)}) is moved by focus navigation: the router sends
 * {@code DRAG_ENTER}/{@code DRAG_LEAVE}/{@code DRAG_OVER} to the newly focused element, Submit
 * drops on the acceptor and Cancel cancels. A pointer can still finish it with a primary click.
 */
public final class DragDropController {

    private final EventDispatcher dispatcher;
    private final DoubleSupplier clock;
    private final Supplier<UiElement> root;
    private final Function<String, UiElement> find;
    private long nextId = 1;
    private DragSession session;
    /** The most recent session, live or ended: superseded when the next one starts. */
    private DragSession latest;
    private final List<UiElement> overChain = new ArrayList<>();
    private float lastX;
    private float lastY;

    DragDropController(EventDispatcher dispatcher, DoubleSupplier clock, Supplier<UiElement> root,
                       Function<String, UiElement> find) {
        this.dispatcher = dispatcher;
        this.clock = clock;
        this.root = root;
        this.find = find;
    }

    /** The live drag, or null. */
    public DragSession session() {
        return session;
    }

    public boolean active() {
        return session != null;
    }

    /**
     * Asks {@code pressed} (bubbling) whether a drag starts. Returns the new session, or null
     * when no handler supplied a payload.
     */
    DragSession tryStart(UiElement pressed, float x, float y, int button) {
        DragEvent start = dispatcher.dispatch(new DragEvent(UiEventType.DRAG_START, clock.getAsDouble(), x, y, null),
            pressed);
        if (start.payload() == null || start.isDefaultPrevented()) {
            return null;
        }
        return begin(pressed, start.payload(), x, y, button, InputDevice.MOUSE);
    }

    /** Starts a drag from code ({@link InputDevice#PROGRAM}); see {@link #start(UiElement, Object, InputDevice)}. */
    public DragSession start(UiElement source, Object payload) {
        return start(source, payload, InputDevice.PROGRAM);
    }

    /**
     * Starts a drag from code (a controller or keyboard "pick up" action). It is focus-driven:
     * focus navigation moves it, Submit drops it, Cancel cancels it (a primary click also drops
     * it). A live drag is cancelled first ({@code PROGRAM}).
     */
    public DragSession start(UiElement source, Object payload, InputDevice device) {
        if (session != null) {
            cancel(CancelReason.PROGRAM);
        }
        return begin(source, payload, Float.NaN, Float.NaN, PointerEvent.PRIMARY,
            device == InputDevice.MOUSE ? InputDevice.PROGRAM : device);
    }

    private DragSession begin(UiElement source, Object payload, float x, float y, int button, InputDevice device) {
        if (latest != null) {
            latest.supersede();
        }
        session = new DragSession(nextId++, source, payload, button, device);
        latest = session;
        lastX = x;
        lastY = y;
        return session;
    }

    /**
     * Moves a live drag over {@code under} from code (the element's centre is the position), as
     * focus navigation does for a focus-driven drag. No-op without a drag.
     */
    public void hover(UiElement under) {
        if (session == null) {
            return;
        }
        if (under == null) {
            over(null, Float.NaN, Float.NaN);
            return;
        }
        com.openmason.engine.ui.runtime.UiRect r = under.rect();
        over(under, r.x() + r.width() / 2f, r.y() + r.height() / 2f);
    }

    /** Drops a live drag on its acceptor from code, or cancels it ({@code REJECTED}) without one. */
    public void drop() {
        release();
    }

    /**
     * Ends any live drag with {@code reason} and supersedes the latest one, so a late reply to an
     * earlier drop is no longer {@linkplain DragSession#isCurrent() current} (screen closed,
     * disconnect).
     */
    void abandon(CancelReason reason) {
        cancel(reason);
        if (latest != null) {
            latest.supersede();
        }
    }

    /** The pointer moved over {@code under} (null: nothing, or blocked). */
    void over(UiElement under, float x, float y) {
        DragSession s = session;
        if (s == null) {
            return;
        }
        lastX = x;
        lastY = y;
        List<UiElement> chain = new ArrayList<>();
        for (UiElement e = under; e != null; e = e.parent()) {
            if (InputTraits.canReceivePointer(e)) {
                chain.add(e);
            }
        }
        double now = clock.getAsDouble();
        List<UiElement> previous = List.copyOf(overChain);
        overChain.clear();
        overChain.addAll(chain);
        for (UiElement old : previous) {
            if (!chain.contains(old) && !old.isRemoved()) {
                dispatcher.dispatch(new DragEvent(UiEventType.DRAG_LEAVE, now, x, y, s), old);
                if (session != s) {
                    return; // a handler ended the drag
                }
            }
        }
        for (int i = chain.size() - 1; i >= 0; i--) {
            UiElement e = chain.get(i);
            if (!previous.contains(e)) {
                dispatcher.dispatch(new DragEvent(UiEventType.DRAG_ENTER, now, x, y, s), e);
                if (session != s) {
                    return;
                }
            }
        }
        s.accept(null);
        if (under != null && InputTraits.canReceivePointer(under)) {
            dispatcher.dispatch(new DragEvent(UiEventType.DRAG_OVER, now, x, y, s), under);
        }
    }

    /** The drag button was released: drop on the acceptor, or cancel when there is none. */
    void release() {
        DragSession s = session;
        if (s == null) {
            return;
        }
        UiElement target = s.acceptor();
        if (target == null || !InputTraits.canReceivePointer(target)) {
            end(CancelReason.REJECTED, null);
            return;
        }
        DragEvent drop = dispatcher.dispatch(new DragEvent(UiEventType.DRAG_DROP, clock.getAsDouble(), lastX, lastY,
            s), target);
        if (session != s) {
            return; // the drop handler cancelled the drag itself; it has already ended
        }
        // A drop handler that threw may not have taken the payload: the source must keep it.
        boolean rejected = drop.isDefaultPrevented() || drop.handlerFailed();
        end(rejected ? CancelReason.REJECTED : null, rejected ? null : target);
    }

    /** Cancels the live drag (no-op without one). */
    public void cancel(CancelReason reason) {
        if (session != null) {
            end(reason, null);
        }
    }

    /**
     * Re-resolves elements a rebuild replaced (same key, new element), then cancels when the
     * source or the accepting target can no longer take part.
     */
    void validate() {
        DragSession s = session;
        if (s == null) {
            return;
        }
        UiElement source = twin(s.source());
        if (source != s.source()) {
            s.rebindSource(source);
        }
        if (s.acceptor() != null) {
            s.accept(twin(s.acceptor()));
        }
        for (int i = 0; i < overChain.size(); i++) {
            overChain.set(i, twin(overChain.get(i)));
        }
        if (!InputTraits.canReceivePointer(s.source())) {
            cancel(CancelReason.SOURCE_REMOVED);
        } else if (s.acceptor() != null && !InputTraits.canReceivePointer(s.acceptor())) {
            cancel(CancelReason.TARGET_REMOVED);
        } else {
            overChain.removeIf(e -> !InputTraits.canReceivePointer(e));
        }
    }

    /** {@code el}, or the live element now under its key when a rebuild removed it. */
    private UiElement twin(UiElement el) {
        if (el == null || !el.isRemoved()) {
            return el;
        }
        UiElement t = find.apply(el.key());
        return t == null || t.isRemoved() ? el : t;
    }

    private void end(CancelReason reason, UiElement dropTarget) {
        DragSession s = session;
        session = null;
        double now = clock.getAsDouble();
        for (UiElement e : List.copyOf(overChain)) {
            if (!e.isRemoved()) {
                dispatcher.dispatch(new DragEvent(UiEventType.DRAG_LEAVE, now, lastX, lastY, s), e);
            }
        }
        overChain.clear();
        if (dropTarget != null) {
            s.dropped(dropTarget);
        } else {
            s.cancelled(reason == null ? CancelReason.REJECTED : reason);
        }
        UiElement notify = s.source().isRemoved() ? root.get() : s.source();
        dispatcher.dispatch(new DragEvent(UiEventType.DRAG_END, now, lastX, lastY, s), notify);
    }
}
