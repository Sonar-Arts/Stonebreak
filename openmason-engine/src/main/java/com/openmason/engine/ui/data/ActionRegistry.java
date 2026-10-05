package com.openmason.engine.ui.data;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** The actions a host offers (#289), by id. Registration is explicit: nothing is reflected. */
public final class ActionRegistry {

    record Entry(ActionSpec spec, ActionHandler handler) {
    }

    private final Map<String, Entry> actions = new LinkedHashMap<>();
    private final Runnable onChange;

    ActionRegistry(Runnable onChange) {
        this.onChange = onChange;
    }

    /** @throws IllegalArgumentException when the id is taken */
    public void register(ActionSpec spec, ActionHandler handler) {
        Objects.requireNonNull(handler, "handler");
        if (actions.putIfAbsent(spec.id(), new Entry(spec, handler)) != null) {
            throw new IllegalArgumentException("action " + spec.id() + " is already registered");
        }
        onChange.run();
    }

    public ActionSpec spec(String id) {
        Entry e = actions.get(id);
        return e == null ? null : e.spec();
    }

    public Collection<ActionSpec> specs() {
        return Collections.unmodifiableCollection(actions.values().stream().map(Entry::spec).toList());
    }

    Entry entry(String id) {
        return actions.get(id);
    }
}
