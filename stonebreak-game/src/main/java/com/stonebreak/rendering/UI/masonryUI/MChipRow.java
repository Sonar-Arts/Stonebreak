package com.stonebreak.rendering.UI.masonryUI;

import java.util.ArrayList;
import java.util.List;

/**
 * A single line of {@link MBadge} chips: status effects beside a name, tags on a quest entry,
 * modifiers on an item. Chips are sized to their content and packed from one edge; a chip that
 * would cross the {@link #limit} is <b>dropped</b>, along with every chip after it, rather than
 * overflowing into whatever shares the line.
 *
 * <p>List order is priority order. With {@link Align#RIGHT} the first chip sits at the right edge
 * and later chips extend leftwards; with {@link Align#LEFT} the first chip sits at the left edge.
 * Either way it is the tail of the list that gets dropped.
 *
 * <p>The row owns its chips' geometry: laying out sets each visible chip's bounds (and this row's
 * explicit {@link #scale}, when one is set). That is idempotent layout, not animation state.
 */
public class MChipRow extends MWidget {

    /** Which edge the chips are packed from. */
    public enum Align { LEFT, RIGHT }

    private static final float DEFAULT_GAP = 5f;
    private static final float DEFAULT_CHIP_H = 20f;

    private List<MBadge> chips = List.of();
    private Align align = Align.RIGHT;
    private float gap = DEFAULT_GAP;
    private float chipHeight = DEFAULT_CHIP_H;
    private float limit = Float.NaN;

    // ─────────────────────────────────────────────── Fluent config

    public MChipRow chips(List<MBadge> value) {
        this.chips = value != null ? value.stream().filter(c -> c != null).toList() : List.of();
        return this;
    }

    public MChipRow align(Align value) { this.align = value != null ? value : Align.RIGHT; return this; }

    /** Space between chips, in unscaled pixels. */
    public MChipRow gap(float value) { this.gap = finite(value) ? Math.max(0f, value) : DEFAULT_GAP; return this; }

    /** Design chip height in unscaled pixels; never taller than the row itself. */
    public MChipRow chipHeight(float value) {
        this.chipHeight = finite(value) && value > 0f ? value : DEFAULT_CHIP_H;
        return this;
    }

    /**
     * The screen x no chip may cross: a minimum x for {@link Align#RIGHT} (where the text sharing
     * the line ends), a maximum x for {@link Align#LEFT}. NaN (the default) = this row's own far
     * edge. A limit outside the row is clamped to the row.
     */
    public MChipRow limit(float screenX) { this.limit = screenX; return this; }

    /** {@link #limit} under the name a right-aligned row reads best with. */
    public MChipRow minX(float screenX) { return limit(screenX); }

    @Override public MChipRow scale(float scale) { super.scale(scale); return this; }
    @Override public MChipRow scaleText(boolean v) { super.scaleText(v); return this; }
    @Override public MChipRow position(float x, float y) { super.position(x, y); return this; }
    @Override public MChipRow size(float w, float h) { super.size(w, h); return this; }
    @Override public MChipRow bounds(float x, float y, float w, float h) {
        super.bounds(x, y, w, h); return this;
    }

    public List<MBadge> chips() { return chips; }
    public Align align() { return align; }

    // ─────────────────────────────────────────────── Layout

    /**
     * {@code {x, y, w, h}} of every chip that fits, in list order. Also applies those rects to the
     * chips. Empty when the bounds are unusable or nothing fits.
     */
    public List<float[]> chipRects(MasonryUI ui) {
        List<float[]> rects = new ArrayList<>();
        if (ui == null || chips.isEmpty() || !boundsUsable()) return rects;
        float s = textScale();
        float h = Math.min(height, chipHeight * s);
        if (h <= 0f) return rects;
        float top = y + (height - h) / 2f;
        float spacing = gap * s;
        float left = x, right = x + width;
        if (finite(limit)) {
            if (align == Align.RIGHT) left = Math.max(left, limit);
            else right = Math.min(right, limit);
        }

        float pen = align == Align.RIGHT ? right : left;
        for (MBadge chip : chips) {
            if (explicitScale > 0f) chip.scale(explicitScale);
            else if (scaleText) chip.scaleText(true);
            chip.size(0f, h);
            float w = (float) Math.ceil(chip.preferredWidth(ui));
            if (!finite(w) || w <= 0f) break;
            if (align == Align.RIGHT) {
                if (pen - w < left) break;
                pen -= w;
                chip.bounds(pen, top, w, h);
                rects.add(new float[]{pen, top, w, h});
                pen -= spacing;
            } else {
                if (pen + w > right) break;
                chip.bounds(pen, top, w, h);
                rects.add(new float[]{pen, top, w, h});
                pen += w + spacing;
            }
        }
        return rects;
    }

    /** How many chips fit. */
    public int visibleCount(MasonryUI ui) {
        return chipRects(ui).size();
    }

    /**
     * Where the chips end on the side they grow towards: the leftmost chip's x for
     * {@link Align#RIGHT}, the rightmost chip's right edge for {@link Align#LEFT}; the starting
     * edge when none fit. Lets the caller fit the text that shares the line.
     */
    public float contentEdge(MasonryUI ui) {
        List<float[]> rects = chipRects(ui);
        if (rects.isEmpty()) return align == Align.RIGHT ? x + Math.max(0f, width) : x;
        float[] last = rects.get(rects.size() - 1);
        return align == Align.RIGHT ? last[0] : last[0] + last[2];
    }

    private boolean boundsUsable() {
        return finite(x) && finite(y) && finite(width) && finite(height) && width > 0f && height > 0f;
    }

    private static boolean finite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    // ─────────────────────────────────────────────── Render

    @Override
    public void render(MasonryUI ui) {
        if (ui == null || ui.canvas() == null) return;
        int visible = chipRects(ui).size();
        for (int i = 0; i < visible; i++) {
            chips.get(i).render(ui);
        }
    }
}
