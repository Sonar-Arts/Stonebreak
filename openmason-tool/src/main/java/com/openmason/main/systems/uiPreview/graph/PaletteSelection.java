package com.openmason.main.systems.uiPreview.graph;

import java.util.List;

/**
 * Keyboard selection of the node palette (pure): which result row is highlighted as the author
 * arrows through a filtered list. The highlight clamps (no wrap) and resets to the top when the
 * result list changes, so typing always lands on the best match.
 */
public final class PaletteSelection {

    private int count;
    private int index;

    /** Installs a new result count; returns true when it differs from the previous one (the index resets). */
    public boolean setCount(int newCount) {
        boolean changed = newCount != count;
        count = Math.max(0, newCount);
        index = changed ? 0 : Math.min(index, Math.max(0, count - 1));
        return changed;
    }

    /** Back to the first row (the query text changed). */
    public void reset() {
        index = 0;
    }

    public void move(int delta) {
        index = count == 0 ? 0 : Math.clamp((long) index + delta, 0, count - 1);
    }

    public void set(int i) {
        index = count == 0 ? 0 : Math.clamp(i, 0, count - 1);
    }

    public int index() {
        return index;
    }

    public boolean isSelected(int i) {
        return count > 0 && i == index;
    }

    /** The highlighted element of {@code items}, or null when there is none. */
    public <T> T selected(List<T> items) {
        return items.isEmpty() ? null : items.get(Math.min(index, items.size() - 1));
    }
}
