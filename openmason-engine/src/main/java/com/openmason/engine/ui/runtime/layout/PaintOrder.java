package com.openmason.engine.ui.runtime.layout;

import com.openmason.engine.ui.runtime.UiElement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Paint and hit order of a runtime tree, shared by the painter and {@link HitTester} so the two
 * can never disagree (#287).
 *
 * <p>The tree paints in pre-order (children over parents, later siblings over earlier ones).
 * An element with an explicit {@code -sb-layer} different from its surroundings starts an
 * <b>overlay</b>: its subtree is lifted out and painted after every lower layer, in layer
 * order, and is hit-tested before them. Overlays escape ancestor clips (a popup is not cut off
 * by the scroll view that opened it) but keep their layout position. Collapsed subtrees are
 * left out entirely.
 *
 * <p>A {@code -sb-anchor: pointer} element is always lifted into {@link #CURSOR_LAYER}, above
 * every authored layer and the tooltip; it is never hit.
 *
 * @param entries subtrees in paint order; hit testing walks them backwards
 */
public record PaintOrder(List<Entry> entries, Map<UiElement, Entry> lifted) {

    /** One subtree painted as a unit in {@code layer}. */
    public record Entry(UiElement root, int layer) {

        /** The pointer-anchored cursor layer: painted last, never hit. */
        public boolean cursor() {
            return layer == CURSOR_LAYER;
        }
    }

    /** Layer of pointer-anchored subtrees (C2): above every {@code -sb-layer} and the tooltip. */
    public static final int CURSOR_LAYER = Integer.MAX_VALUE;

    private static Integer layerOf(UiElement el) {
        return el.isPointerAnchored() ? Integer.valueOf(CURSOR_LAYER) : el.explicitLayer();
    }

    public static PaintOrder of(UiElement root) {
        List<Entry> entries = new ArrayList<>();
        Map<UiElement, Entry> lifted = new IdentityHashMap<>();
        if (!root.computedStyle().collapsed()) {
            Integer own = layerOf(root);
            Entry first = new Entry(root, own == null ? 0 : own);
            entries.add(first);
            collect(root, first.layer(), entries, lifted);
        }
        // Stable: equal layers keep tree order.
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingInt(Entry::layer));
        return new PaintOrder(Collections.unmodifiableList(sorted), Collections.unmodifiableMap(lifted));
    }

    private static void collect(UiElement el, int layer, List<Entry> entries, Map<UiElement, Entry> lifted) {
        for (UiElement c : el.children()) {
            if (c.computedStyle().collapsed()) {
                continue;
            }
            Integer own = layerOf(c);
            if (own != null && own != layer) {
                Entry e = new Entry(c, own);
                entries.add(e);
                lifted.put(c, e);
                collect(c, own, entries, lifted);
            } else {
                collect(c, layer, entries, lifted);
            }
        }
    }

    /** True when {@code el} starts its own overlay entry (its parent's walk must skip it). */
    public boolean isLifted(UiElement el) {
        return lifted.containsKey(el);
    }
}
