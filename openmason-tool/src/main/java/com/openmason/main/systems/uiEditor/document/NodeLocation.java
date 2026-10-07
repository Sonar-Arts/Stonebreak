package com.openmason.main.systems.uiEditor.document;

import java.util.Objects;

/**
 * Where a node sits in a document tree: its parent, which of the parent's lists holds it, and
 * its index there. A parent's children are the {@code null} slot; an {@code Instance} node's
 * named slots hold slot content.
 *
 * @param parentId id of the parent node
 * @param slot     component slot name, or {@code null} for the parent's children
 * @param index    position in that list
 */
public record NodeLocation(String parentId, String slot, int index) {

    public NodeLocation {
        Objects.requireNonNull(parentId, "parentId");
        if (index < 0) {
            throw new IllegalArgumentException("index must be >= 0");
        }
    }

    /** Appends to {@code parentId}'s children. */
    public static NodeLocation endOf(String parentId) {
        return new NodeLocation(parentId, null, Integer.MAX_VALUE);
    }

    public static NodeLocation childAt(String parentId, int index) {
        return new NodeLocation(parentId, null, index);
    }

    /** Same list, different index. */
    public NodeLocation at(int newIndex) {
        return new NodeLocation(parentId, slot, newIndex);
    }

    public boolean sameList(NodeLocation other) {
        return other != null && parentId.equals(other.parentId) && Objects.equals(slot, other.slot);
    }
}
