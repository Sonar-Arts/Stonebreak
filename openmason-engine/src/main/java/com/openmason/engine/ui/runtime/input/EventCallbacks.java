package com.openmason.engine.ui.runtime.input;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Handlers registered on one element, in registration order (#288). Copy-on-write: a handler
 * may register or remove handlers while an event is being dispatched; the change applies to
 * the next event.
 */
public final class EventCallbacks {

    /** Registration phase: trickle-down handlers run on the way down, others on the way up. */
    public enum Phase { TRICKLE_DOWN, BUBBLE_UP }

    record Entry(UiEventType type, UiEventHandler handler, Phase phase) {
    }

    private List<Entry> entries = List.of();

    public void register(UiEventType type, UiEventHandler handler, Phase phase) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(phase, "phase");
        for (Entry e : entries) {
            if (e.type == type && e.handler == handler && e.phase == phase) {
                return;
            }
        }
        List<Entry> next = new ArrayList<>(entries);
        next.add(new Entry(type, handler, phase));
        entries = List.copyOf(next);
    }

    public void unregister(UiEventType type, UiEventHandler handler, Phase phase) {
        List<Entry> next = new ArrayList<>(entries);
        if (next.removeIf(e -> e.type == type && e.handler == handler && e.phase == phase)) {
            entries = List.copyOf(next);
        }
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** True when any handler listens for {@code type} in either phase. */
    public boolean has(UiEventType type) {
        for (Entry e : entries) {
            if (e.type == type) {
                return true;
            }
        }
        return false;
    }

    List<Entry> snapshot() {
        return entries;
    }

    /** A copy for a rebuilt element (live reload keeps handlers with their element key). */
    public EventCallbacks copy() {
        EventCallbacks c = new EventCallbacks();
        c.entries = entries;
        return c;
    }
}
