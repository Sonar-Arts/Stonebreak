package com.openmason.engine.ui.runtime.input;

import com.openmason.engine.ui.runtime.UiElement;

/**
 * One drag and drop (#288). The id is unique per router and never reused, so a handler that
 * starts asynchronous work from a drop (a server request to move an item) can check
 * {@link #isLive()} or compare ids before acting on a late reply: once a session ends, by drop
 * or cancel, it never drops again.
 *
 * <p><b>Contract.</b> The source receives exactly one {@code DRAG_END} with the
 * {@link #outcome()}; {@code DRAG_DROP} reaches at most one element and never after a cancel.
 * The source keeps ownership of what it is dragging until it sees {@link Outcome#DROPPED}, so a
 * cancelled drag can never lose an item.
 */
public final class DragSession {

    /** How a drag ended. */
    public enum Outcome {
        IN_PROGRESS,
        DROPPED,
        CANCELLED
    }

    private final long id;
    private final UiElement source;
    private final Object payload;
    private final int button;
    private UiElement acceptor;
    private UiElement dropTarget;
    private Outcome outcome = Outcome.IN_PROGRESS;
    private CancelReason cancelReason;

    DragSession(long id, UiElement source, Object payload, int button) {
        this.id = id;
        this.source = source;
        this.payload = payload;
        this.button = button;
    }

    public long id() {
        return id;
    }

    public UiElement source() {
        return source;
    }

    public Object payload() {
        return payload;
    }

    public boolean isLive() {
        return outcome == Outcome.IN_PROGRESS;
    }

    public Outcome outcome() {
        return outcome;
    }

    /** Why the drag was cancelled, or null. */
    public CancelReason cancelReason() {
        return cancelReason;
    }

    /** The element that accepted the payload in the last {@code DRAG_OVER}, or null. */
    public UiElement acceptor() {
        return acceptor;
    }

    /** Where the payload was dropped, once {@link Outcome#DROPPED}. */
    public UiElement dropTarget() {
        return dropTarget;
    }

    int button() {
        return button;
    }

    void accept(UiElement element) {
        acceptor = element;
    }

    void dropped(UiElement target) {
        dropTarget = target;
        outcome = Outcome.DROPPED;
    }

    void cancelled(CancelReason reason) {
        cancelReason = reason;
        outcome = Outcome.CANCELLED;
    }

    @Override
    public String toString() {
        return "drag#" + id + "(" + outcome + (cancelReason == null ? "" : " " + cancelReason) + ")";
    }
}
