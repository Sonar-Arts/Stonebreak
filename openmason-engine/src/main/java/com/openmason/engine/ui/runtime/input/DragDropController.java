package com.openmason.engine.ui.runtime.input;

import com.openmason.engine.ui.runtime.UiElement;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * Drag and drop negotiation of one router (#288); see {@link DragEvent} for the event
 * sequence and {@link DragSession} for the guarantees. At most one drag is live. Every way a
 * drag can end funnels through {@link #end}, which sends the single {@code DRAG_END}: to the
 * source, or, when the source itself was removed, to the document root, so whoever owns the
 * payload always hears that the drag is over.
 */
public final class DragDropController {

    private final EventDispatcher dispatcher;
    private final DoubleSupplier clock;
    private final Supplier<UiElement> root;
    private long nextId = 1;
    private DragSession session;
    private final List<UiElement> overChain = new ArrayList<>();
    private float lastX;
    private float lastY;

    DragDropController(EventDispatcher dispatcher, DoubleSupplier clock, Supplier<UiElement> root) {
        this.dispatcher = dispatcher;
        this.clock = clock;
        this.root = root;
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
        return begin(pressed, start.payload(), x, y, button);
    }

    /** Starts a drag from code (a controller "pick up" action). */
    public DragSession start(UiElement source, Object payload) {
        if (session != null) {
            cancel(CancelReason.PROGRAM);
        }
        return begin(source, payload, Float.NaN, Float.NaN, PointerEvent.PRIMARY);
    }

    private DragSession begin(UiElement source, Object payload, float x, float y, int button) {
        session = new DragSession(nextId++, source, payload, button);
        lastX = x;
        lastY = y;
        return session;
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

    /** Cancels when the source or the accepting target can no longer take part. */
    void validate() {
        if (session == null) {
            return;
        }
        if (!InputTraits.canReceivePointer(session.source())) {
            cancel(CancelReason.SOURCE_REMOVED);
        } else if (session.acceptor() != null && !InputTraits.canReceivePointer(session.acceptor())) {
            cancel(CancelReason.TARGET_REMOVED);
        } else {
            overChain.removeIf(e -> !InputTraits.canReceivePointer(e));
        }
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
