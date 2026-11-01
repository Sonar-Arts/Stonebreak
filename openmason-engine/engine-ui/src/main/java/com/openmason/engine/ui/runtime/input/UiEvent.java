package com.openmason.engine.ui.runtime.input;

import com.openmason.engine.ui.runtime.UiElement;

import java.util.Objects;

/**
 * Base of every dispatched event (#288). One object travels the whole propagation path; the
 * dispatcher sets {@link #currentTarget()} and {@link #phase()} as it goes.
 *
 * <ul>
 *   <li>{@link #stopPropagation()}: later elements on the path are skipped; the remaining
 *       handlers of the current element still run.</li>
 *   <li>{@link #stopImmediatePropagation()}: nothing else runs, not even this element's
 *       remaining handlers.</li>
 *   <li>{@link #preventDefault()}: the router skips the event's default action (moving focus,
 *       clicking the focused element, editing text, scrolling, dismissing a popup).</li>
 * </ul>
 * An event a handler stopped or prevented is <b>handled</b>: the router reports it consumed, so
 * it never also reaches gameplay or editor shortcuts.
 */
public abstract class UiEvent {

    private final UiEventType type;
    private final double time;
    private UiElement target;
    private UiElement currentTarget;
    private EventPhase phase = EventPhase.NONE;
    private boolean propagationStopped;
    private boolean immediateStopped;
    private boolean defaultPrevented;
    private boolean dispatched;
    private boolean handlerFailed;

    protected UiEvent(UiEventType type, double time) {
        this.type = Objects.requireNonNull(type, "type");
        this.time = time;
    }

    public UiEventType type() {
        return type;
    }

    /** Router clock in seconds when the event was created. */
    public double time() {
        return time;
    }

    /** The element the event is for (the hit element, the focused element, ...). */
    public UiElement target() {
        return target;
    }

    /** The element whose handlers are running now. */
    public UiElement currentTarget() {
        return currentTarget;
    }

    public EventPhase phase() {
        return phase;
    }

    public void stopPropagation() {
        propagationStopped = true;
    }

    public void stopImmediatePropagation() {
        propagationStopped = true;
        immediateStopped = true;
    }

    public void preventDefault() {
        defaultPrevented = true;
    }

    public boolean isPropagationStopped() {
        return propagationStopped;
    }

    public boolean isImmediatePropagationStopped() {
        return immediateStopped;
    }

    public boolean isDefaultPrevented() {
        return defaultPrevented;
    }

    /** Stopped or prevented by a handler: the host must treat the input as consumed. */
    public boolean isHandled() {
        return propagationStopped || defaultPrevented;
    }

    /** A handler threw while this event was dispatched (the router treats a failed drop as rejected). */
    public boolean handlerFailed() {
        return handlerFailed;
    }

    // ── dispatcher hooks ────────────────────────────────────────────────────

    void failed() {
        handlerFailed = true;
    }

    void begin(UiElement target) {
        if (dispatched) {
            throw new IllegalStateException(type + " was already dispatched; create a new event");
        }
        dispatched = true;
        this.target = target;
    }

    void at(UiElement element, EventPhase p) {
        currentTarget = element;
        phase = p;
    }

    void end() {
        currentTarget = null;
        phase = EventPhase.NONE;
    }

    @Override
    public String toString() {
        return type + (target == null ? "" : " -> " + target.key());
    }
}
