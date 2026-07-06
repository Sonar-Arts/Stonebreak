package com.openmason.main.systems.menus.dialogs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Most-recently-used ingredient objectIds, shared by the recipe/smelting
 * editors and the ingredient picker (the picker's "Recent" strip and the
 * recipe grid's palette chips both read it). Session-scoped — not persisted.
 *
 * <p>{@link #shared()} is the app-wide instance; tests construct their own.</p>
 */
public final class IngredientMru {

    private static final IngredientMru SHARED = new IngredientMru(12);

    private final int capacity;
    private final Deque<String> recents = new ArrayDeque<>();

    public IngredientMru(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    /** The app-wide shared list. */
    public static IngredientMru shared() {
        return SHARED;
    }

    /** Record a use: moves {@code objectId} to the front, evicting past capacity. */
    public synchronized void touch(String objectId) {
        if (objectId == null || objectId.isEmpty()) return;
        recents.remove(objectId);
        recents.addFirst(objectId);
        while (recents.size() > capacity) {
            recents.removeLast();
        }
    }

    /** Snapshot, most recent first. */
    public synchronized List<String> list() {
        return new ArrayList<>(recents);
    }

    public synchronized boolean isEmpty() {
        return recents.isEmpty();
    }

    public synchronized void clear() {
        recents.clear();
    }
}
