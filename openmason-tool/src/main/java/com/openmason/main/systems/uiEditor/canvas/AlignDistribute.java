package com.openmason.main.systems.uiEditor.canvas;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Align and distribute for absolutely positioned elements. Flow-laid-out elements are placed by
 * their container (use its justify/align controls instead), so callers pass only absolute ones.
 * One element aligns to its parent; several align to their common bounds.
 */
public final class AlignDistribute {

    public enum Op {
        LEFT, CENTER_X, RIGHT, TOP, CENTER_Y, BOTTOM, DISTRIBUTE_X, DISTRIBUTE_Y;

        public boolean distributes() {
            return this == DISTRIBUTE_X || this == DISTRIBUTE_Y;
        }
    }

    /**
     * An element to place.
     *
     * @param box        current frame-pixel box
     * @param offsetLeft current {@code left} in logical px
     * @param offsetTop  current {@code top} in logical px
     */
    public record Item(String key, Box box, float offsetLeft, float offsetTop) {
    }

    /** New {@code left}/{@code top} (logical px) per key; only elements that move appear. */
    public record Placement(String key, float left, float top) {
    }

    private AlignDistribute() {
    }

    /**
     * @param parent the parent's box, used when a single item aligns
     * @param scale  device pixels per logical pixel
     */
    public static List<Placement> apply(Op op, List<Item> items, Box parent, float scale) {
        if (items.isEmpty() || op.distributes() && items.size() < 3) {
            return List.of();
        }
        Box bounds = items.size() == 1 && parent != null ? parent : null;
        if (bounds == null) {
            for (Item i : items) {
                bounds = i.box().union(bounds);
            }
        }
        Map<String, float[]> moves = new LinkedHashMap<>();
        if (op.distributes()) {
            distribute(op == Op.DISTRIBUTE_X, items, moves);
        } else {
            for (Item i : items) {
                Box b = i.box();
                float dx = switch (op) {
                    case LEFT -> bounds.l() - b.l();
                    case CENTER_X -> bounds.cx() - b.cx();
                    case RIGHT -> bounds.r() - b.r();
                    default -> 0;
                };
                float dy = switch (op) {
                    case TOP -> bounds.t() - b.t();
                    case CENTER_Y -> bounds.cy() - b.cy();
                    case BOTTOM -> bounds.b() - b.b();
                    default -> 0;
                };
                moves.put(i.key(), new float[]{dx, dy});
            }
        }
        List<Placement> out = new ArrayList<>();
        for (Item i : items) {
            float[] d = moves.get(i.key());
            if (d == null || Math.abs(d[0]) < 0.01f && Math.abs(d[1]) < 0.01f) {
                continue;
            }
            out.add(new Placement(i.key(), Math.round(i.offsetLeft() + d[0] / scale),
                Math.round(i.offsetTop() + d[1] / scale)));
        }
        return out;
    }

    /** Equal gaps between consecutive items; the outermost two stay put. */
    private static void distribute(boolean horizontal, List<Item> items, Map<String, float[]> moves) {
        List<Item> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparingDouble(i -> horizontal ? i.box().l() : i.box().t()));
        float start = horizontal ? sorted.getFirst().box().l() : sorted.getFirst().box().t();
        float end = horizontal ? sorted.getLast().box().r() : sorted.getLast().box().b();
        float total = 0;
        for (Item i : sorted) {
            total += horizontal ? i.box().width() : i.box().height();
        }
        float gap = (end - start - total) / (sorted.size() - 1);
        float cursor = start;
        for (Item i : sorted) {
            float at = horizontal ? i.box().l() : i.box().t();
            float d = cursor - at;
            moves.put(i.key(), horizontal ? new float[]{d, 0} : new float[]{0, d});
            cursor += (horizontal ? i.box().width() : i.box().height()) + gap;
        }
    }
}
