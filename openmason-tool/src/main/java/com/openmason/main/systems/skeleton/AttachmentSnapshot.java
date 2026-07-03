package com.openmason.main.systems.skeleton;

import com.openmason.engine.format.omo.OMOFormat;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable snapshot of an {@link AttachmentStore}'s contents for undo/redo.
 * Captures the socket list (each {@link OMOFormat.AttachmentPointEntry} is a
 * record and therefore immutable, so a shallow copy of the list suffices) plus
 * the currently-selected socket id. Mirrors {@link BoneSnapshot}.
 */
public record AttachmentSnapshot(List<OMOFormat.AttachmentPointEntry> points,
                                 String selectedAttachmentId) {

    public AttachmentSnapshot {
        // Defensive copy on construction so callers can't mutate the snapshot
        // by holding onto the original list.
        points = points == null ? List.of() : List.copyOf(points);
    }

    /** Capture the current state of the given store. */
    public static AttachmentSnapshot capture(AttachmentStore store) {
        return new AttachmentSnapshot(new ArrayList<>(store.getPoints()),
                store.getSelectedAttachmentId());
    }

    /** Restore this snapshot into the given store. */
    public void restore(AttachmentStore store) {
        store.setPoints(points);
        store.setSelectedAttachmentId(selectedAttachmentId);
    }

    public boolean equalsSnapshot(AttachmentSnapshot other) {
        if (other == null) return false;
        if (points.size() != other.points.size()) return false;
        for (int i = 0; i < points.size(); i++) {
            if (!points.get(i).equals(other.points.get(i))) return false;
        }
        return java.util.Objects.equals(selectedAttachmentId, other.selectedAttachmentId);
    }
}
