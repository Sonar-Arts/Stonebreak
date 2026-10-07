package com.openmason.main.systems.uiPreview.graph;

import imgui.ImGui;
import imgui.type.ImString;

import java.util.HashMap;
import java.util.Map;

/**
 * Text inputs that edit a document value but only commit when the author finishes (Enter or
 * focus lost), so typing never creates an undo step per keystroke. Each field keeps its own
 * buffer keyed by an id; the buffer follows the document value whenever the author is not
 * mid-edit (undo, other panels, reloads), so the field never shows stale text.
 */
final class CommitField {

    private static final class Slot {
        final ImString buffer = new ImString(1024);
        String shown;
    }

    private final Map<String, Slot> slots = new HashMap<>();

    /**
     * Draws a text input and returns the typed text when the author committed it, else null.
     *
     * @param id      unique among the fields of the current window (also the ImGui id)
     * @param current the document's value
     */
    String text(String id, String current, float width) {
        Slot s = slots.computeIfAbsent(id, k -> new Slot());
        if (!current.equals(s.shown)) {
            s.buffer.set(current);
            s.shown = current;
        }
        if (width != 0) {
            ImGui.setNextItemWidth(width);
        }
        ImGui.inputText("##" + id, s.buffer);
        if (ImGui.isItemDeactivatedAfterEdit()) {
            return s.buffer.get();
        }
        return null;
    }

    /** Shows the document value again (the typed text was rejected). */
    void revert(String id) {
        Slot s = slots.get(id);
        if (s != null) {
            s.shown = null;
        }
    }

    /** Drops buffers of fields that are gone (call occasionally; keeps the map bounded). */
    void clear() {
        slots.clear();
    }
}
