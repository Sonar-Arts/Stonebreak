package com.openmason.engine.ui.data;

import com.openmason.engine.format.omui.UiValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keyed diff of two lists into {@link ListChange}s (#289): removals, then a left-to-right walk
 * that moves, inserts or updates so that replaying the changes on {@code before} yields
 * {@code after}. Items are matched by identity, so a reorder keeps rows (and their state)
 * instead of rebinding every row to a different item.
 */
public final class ListDiff {

    private ListDiff() {
    }

    /**
     * @param identity identity field; {@code null} matches by position (no moves)
     * @return the changes, or {@code [Reset]} when an item lacks a unique identity
     */
    public static List<ListChange> diff(List<UiValue> before, List<UiValue> after, String identity) {
        List<ListChange> out = new ArrayList<>();
        if (identity == null) {
            int common = Math.min(before.size(), after.size());
            for (int i = before.size() - 1; i >= common; i--) {
                out.add(new ListChange.Removed(i, null));
            }
            for (int i = 0; i < common; i++) {
                if (!before.get(i).equals(after.get(i))) {
                    out.add(new ListChange.Updated(i, after.get(i)));
                }
            }
            for (int i = common; i < after.size(); i++) {
                out.add(new ListChange.Inserted(i, after.get(i)));
            }
            return out;
        }
        List<String> cur = ids(before, identity);
        List<String> target = ids(after, identity);
        if (cur == null || target == null) {
            return List.of(ListChange.RESET);
        }
        Set<String> keep = new HashSet<>(target);
        List<UiValue> items = new ArrayList<>(before);
        for (int i = cur.size() - 1; i >= 0; i--) {
            if (!keep.contains(cur.get(i))) {
                out.add(new ListChange.Removed(i, cur.get(i)));
                cur.remove(i);
                items.remove(i);
            }
        }
        for (int i = 0; i < target.size(); i++) {
            String id = target.get(i);
            int at = i < cur.size() && cur.get(i).equals(id) ? i : cur.indexOf(id);
            if (at < 0) {
                out.add(new ListChange.Inserted(i, after.get(i)));
                cur.add(i, id);
                items.add(i, after.get(i));
                continue;
            }
            if (at != i) {
                out.add(new ListChange.Moved(at, i));
                cur.add(i, cur.remove(at));
                items.add(i, items.remove(at));
            }
            if (!items.get(i).equals(after.get(i))) {
                out.add(new ListChange.Updated(i, after.get(i)));
                items.set(i, after.get(i));
            }
        }
        return out;
    }

    /** Applies {@code changes} to a copy of {@code items}; a {@link ListChange.Reset} is not applicable. */
    public static List<UiValue> apply(List<UiValue> items, List<ListChange> changes) {
        List<UiValue> out = new ArrayList<>(items);
        for (ListChange c : changes) {
            switch (c) {
                case ListChange.Inserted ins -> out.add(ins.index(), ins.item());
                case ListChange.Removed rem -> out.remove(rem.index());
                case ListChange.Updated up -> out.set(up.index(), up.item());
                case ListChange.Moved mv -> out.add(mv.to(), out.remove(mv.from()));
                case ListChange.Reset r -> throw new IllegalArgumentException("a reset carries no items");
            }
        }
        return out;
    }

    private static List<String> ids(List<UiValue> items, String identity) {
        List<String> out = new ArrayList<>(items.size());
        Map<String, Boolean> seen = new HashMap<>();
        for (UiValue v : items) {
            String id = DataType.identityOf(v, identity);
            if (id == null || seen.put(id, Boolean.TRUE) != null) {
                return null;
            }
            out.add(id);
        }
        return out;
    }
}
