package com.openmason.engine.ui.runtime.input;

import com.openmason.engine.ui.runtime.UiElement;

/**
 * One drag and drop (#288). The id is unique per router and never reused. {@link #isLive()} is
 * true only while the drag is in progress; once it ends, by drop or cancel, it never drops again.
 *
 * <p><b>Late replies.</b> A handler that starts asynchronous work from a drop (a server request to
 * move an item) checks {@link #isCurrent()} before applying the reply: a dropped session stays
 * current until a newer drag starts or the router abandons its interactions (screen closed,
 * disconnect), so a server-confirmed move is applied, and a reply that arrives after the player
 * moved on is not. {@code isLive()} is the wrong check there: it is already false when the drop
 * handler returns.
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
    private UiElement source;
    private final Object payload;
    private final int button;
    private final InputDevice device;
    private boolean superseded;
    private UiElement acceptor;
    private UiElement dropTarget;
    private Outcome outcome = Outcome.IN_PROGRESS;
    private CancelReason cancelReason;

    DragSession(long id, UiElement source, Object payload, int button, InputDevice device) {
        this.id = id;
        this.source = source;
        this.payload = payload;
        this.button = button;
        this.device = device;
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

    /** In progress: not yet dropped or cancelled. */
    public boolean isLive() {
        return outcome == Outcome.IN_PROGRESS;
    }

    /**
     * In progress, or dropped and not yet superseded by a newer drag or an abandoned screen: the
     * check for applying a late asynchronous reply to this drag's drop. Always false once cancelled.
     */
    public boolean isCurrent() {
        return outcome != Outcome.CANCELLED && !superseded;
    }

    /**
     * What started the drag: {@link InputDevice#MOUSE} for a pointer drag, otherwise the device
     * passed to {@link DragDropController#start(UiElement, Object, InputDevice)} (a controller or
     * keyboard pick-up, which focus navigation moves and Submit drops).
     */
    public InputDevice device() {
        return device;
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

    /** A pointer drag follows the pointer; any other is moved by focus navigation. */
    boolean focusDriven() {
        return device != InputDevice.MOUSE;
    }

    void rebindSource(UiElement twin) {
        source = twin;
    }

    void supersede() {
        superseded = true;
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
