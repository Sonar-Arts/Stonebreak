package com.openmason.main.systems.skeleton;

import com.openmason.engine.format.omo.OMOFormat;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable snapshot of a {@link BoneStore}'s contents for undo/redo.
 * Captures the bone list (each {@link OMOFormat.BoneEntry} is itself a record
 * and therefore immutable, so a shallow copy of the list suffices) plus the
 * currently-selected bone id.
 */
public record BoneSnapshot(List<OMOFormat.BoneEntry> bones, String selectedBoneId) {

    public BoneSnapshot {
        // Defensive copy on construction so callers can't mutate the snapshot
        // by holding onto the original list.
        bones = bones == null ? List.of() : List.copyOf(bones);
    }

    /** Capture the current state of the given store. */
    public static BoneSnapshot capture(BoneStore store) {
        return new BoneSnapshot(new ArrayList<>(store.getBones()), store.getSelectedBoneId());
    }

    /** Restore this snapshot into the given store. */
    public void restore(BoneStore store) {
        store.setBones(bones);
        store.setSelectedBoneId(selectedBoneId);
    }

    public boolean equalsSnapshot(BoneSnapshot other) {
        if (other == null) return false;
        if (bones.size() != other.bones.size()) return false;
        for (int i = 0; i < bones.size(); i++) {
            if (!bones.get(i).equals(other.bones.get(i))) return false;
        }
        return java.util.Objects.equals(selectedBoneId, other.selectedBoneId);
    }
}
