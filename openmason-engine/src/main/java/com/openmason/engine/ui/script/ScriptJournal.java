package com.openmason.engine.ui.script;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiElement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Undo log of the local-layer writes a script makes during one dispatch (#292): when the handler
 * fails, its partial writes are rolled back so the screen keeps its last good presentation.
 * Besides element writes it undoes what the dispatch registered or started (#282 hardening):
 * handlers, signal listeners, watches, timers and animations (released, so the rolled-back
 * values are not re-claimed by a tween the failed handler started). What it cannot undo is
 * documented: actions already invoked on the host, focus moves, scroll positions and
 * state-machine moves.
 * Dispatches nest (a signal reaching another script); each has its own mark. Steady state is
 * allocation-free: a dispatch that writes nothing pushes and pops one int.
 */
final class ScriptJournal {

    private enum Kind { PROP, STYLE, CLASS, STATE, ENABLED, CALLBACK }

    private record Entry(Kind kind, String key, String name, Object previous) {
    }

    private final UiDocumentInstance ui;
    private final List<Entry> entries = new ArrayList<>();
    private int[] marks = new int[8];
    private int depth;

    ScriptJournal(UiDocumentInstance ui) {
        this.ui = ui;
    }

    void begin() {
        if (depth == marks.length) {
            marks = Arrays.copyOf(marks, depth * 2);
        }
        marks[depth++] = entries.size();
    }

    /** Keeps this dispatch's writes (folded into the enclosing dispatch, if any). */
    void commit() {
        if (--depth == 0) {
            entries.clear();
        }
    }

    /** Undoes this dispatch's writes, newest first. */
    void rollback() {
        int mark = marks[--depth];
        for (int i = entries.size() - 1; i >= mark; i--) {
            undo(entries.remove(i));
        }
    }

    boolean active() {
        return depth > 0;
    }

    void prop(UiElement el, String name) {
        if (depth > 0) {
            entries.add(new Entry(Kind.PROP, el.key(), name, el.localProp(name)));
        }
    }

    void style(UiElement el, String property) {
        if (depth > 0) {
            entries.add(new Entry(Kind.STYLE, el.key(), property, el.localStyle(property)));
        }
    }

    void cls(UiElement el, String className) {
        if (depth > 0) {
            entries.add(new Entry(Kind.CLASS, el.key(), className, el.localClass(className)));
        }
    }

    void state(UiElement el, String state) {
        if (depth > 0) {
            entries.add(new Entry(Kind.STATE, el.key(), state, el.hasState(state)));
        }
    }

    void enabled(UiElement el) {
        if (depth > 0) {
            entries.add(new Entry(Kind.ENABLED, el.key(), null, el.isEnabledSelf()));
        }
    }

    /** {@code undo} runs if this dispatch fails (registrations and started animations). */
    void callback(Runnable undo) {
        if (depth > 0) {
            entries.add(new Entry(Kind.CALLBACK, null, null, undo));
        }
    }

    private void undo(Entry e) {
        if (e.kind == Kind.CALLBACK) {
            ((Runnable) e.previous).run();
            return;
        }
        UiElement el = ui.find(e.key);
        if (el == null) {
            return;
        }
        switch (e.kind) {
            case PROP -> {
                if (e.previous == null) {
                    el.clearProp(e.name);
                } else {
                    el.setProp(e.name, (UiValue) e.previous);
                }
            }
            case STYLE -> {
                if (e.previous == null) {
                    el.clearStyle(e.name);
                } else {
                    el.setStyle(e.name, (UiValue) e.previous);
                }
            }
            case CLASS -> {
                el.clearLocalClass(e.name);
                if (Boolean.TRUE.equals(e.previous)) {
                    el.addClass(e.name);
                } else if (Boolean.FALSE.equals(e.previous)) {
                    el.removeClass(e.name);
                }
            }
            case STATE -> el.setState(e.name, (Boolean) e.previous);
            case ENABLED -> el.setEnabled((Boolean) e.previous);
            case CALLBACK -> { }
        }
    }
}
