package com.openmason.engine.ui.runtime.input;

import com.openmason.engine.ui.runtime.UiElement;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Unity UI Toolkit propagation (#288): the path is fixed when dispatch starts (root → target);
 * trickle-down handlers of each ancestor run root-first, then the target's handlers
 * (trickle-down ones first), then, for bubbling types, the bubble-up handlers of each ancestor
 * back to the root. Stopping propagation skips the rest of the path; elements removed by a
 * handler mid-dispatch are skipped.
 *
 * <p>A handler that throws is reported through {@code errors} and dispatch continues: one
 * broken script must not leave the router's press, capture or focus state half-updated.
 */
public final class EventDispatcher {

    private final BiConsumer<UiEvent, RuntimeException> errors;

    public EventDispatcher(BiConsumer<UiEvent, RuntimeException> errors) {
        this.errors = errors;
    }

    /** Dispatches {@code event} at {@code target}. Returns the event (handled flags set). */
    public <E extends UiEvent> E dispatch(E event, UiElement target) {
        event.begin(target);
        if (target == null || target.isRemoved()) {
            event.end();
            return event;
        }
        List<UiElement> path = new ArrayList<>();
        for (UiElement e = target; e != null; e = e.parent()) {
            path.add(e);
        }
        try {
            for (int i = path.size() - 1; i >= 1 && !event.isPropagationStopped(); i--) {
                invoke(event, path.get(i), EventPhase.TRICKLE_DOWN, EventCallbacks.Phase.TRICKLE_DOWN);
            }
            if (!event.isPropagationStopped()) {
                invoke(event, target, EventPhase.AT_TARGET, EventCallbacks.Phase.TRICKLE_DOWN);
                if (!event.isImmediatePropagationStopped()) {
                    invoke(event, target, EventPhase.AT_TARGET, EventCallbacks.Phase.BUBBLE_UP);
                }
            }
            if (event.type().bubbles()) {
                for (int i = 1; i < path.size() && !event.isPropagationStopped(); i++) {
                    invoke(event, path.get(i), EventPhase.BUBBLE_UP, EventCallbacks.Phase.BUBBLE_UP);
                }
            }
        } finally {
            event.end();
        }
        return event;
    }

    private void invoke(UiEvent event, UiElement el, EventPhase phase, EventCallbacks.Phase registered) {
        EventCallbacks callbacks = el.callbacks();
        if (callbacks == null || el.isRemoved()) {
            return;
        }
        event.at(el, phase);
        for (EventCallbacks.Entry entry : callbacks.snapshot()) {
            if (entry.type() != event.type() || entry.phase() != registered) {
                continue;
            }
            try {
                entry.handler().handle(event);
            } catch (RuntimeException ex) {
                event.failed();
                errors.accept(event, ex);
            }
            if (event.isImmediatePropagationStopped()) {
                return;
            }
        }
    }
}
