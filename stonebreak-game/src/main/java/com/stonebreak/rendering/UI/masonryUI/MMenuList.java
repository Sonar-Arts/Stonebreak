package com.stonebreak.rendering.UI.masonryUI;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;
import io.github.humbleui.types.Rect;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keyboard / gamepad / mouse driven vertical list inside a HUD frame: command menus, dialogue
 * choices, shop lists.
 *
 * <p><b>Rows are data.</b> A {@link Row} is a label, an enabled flag and an {@link Adornment}
 * (nothing, a submenu chevron, a coloured tag, cost pips, a count). The painter switches on the
 * adornment <em>kind</em> and never on which row it is, so callers add, remove and reorder rows
 * without touching this class.
 *
 * <p><b>Look.</b> The row under the cursor gets the {@link MStyle#ROW_CURRENT} fill, a 3px
 * {@link MStyle#TEXT_ACCENT} bar on its left edge and a bobbing {@link MSymbol#HAND_POINT}; a row
 * whose submenu is open ({@link #held}) keeps a quieter {@link MStyle#ROW_HELD} fill and a dimmed,
 * still hand so the path stays marked. Disabled rows draw in {@link MStyle#TEXT_DISABLED}.
 *
 * <p><b>Active vs static.</b> {@code active(false)} is the resting state of a list that is on
 * screen but not accepting input: every row listed, no cursor, no selection fill, nothing
 * animating. {@link #veil} lays {@link MPainter#hudVeil} over it; the caller fades that out as the
 * list wakes.
 *
 * <p><b>Geometry.</b> Rows compress below their design height rather than overflow a short frame.
 * {@link #rowRect} is the single slot formula shared by drawing and {@link #rowAt} hit-testing.
 * Navigation ({@link #moveCursor}, {@link #setCursorFromPointer}) is pure logic and needs no canvas.
 *
 * <p><b>Submenus.</b> A submenu is simply a second {@code MMenuList}: mark the opener row with
 * {@link #held}, and place the second list at {@link #anchorRightOf}.
 *
 * <p>Animation (cursor bob, glow pulse) advances only through {@link #update}; painting never
 * changes state.
 */
public class MMenuList extends MWidget {

    // ─────────────────────────────────────────────── Row data

    /** What a row shows on its right-hand side. Data, not behaviour: new rows never need new code here. */
    public sealed interface Adornment {
        /** Nothing on the right. */
        Adornment NONE = new None();
        /** A right chevron in the default colour: this row opens a submenu. */
        Adornment CHEVRON = new Chevron(0);

        record None() implements Adornment {}

        /** Submenu chevron; {@code color} 0 = {@link MStyle#TEXT_SECONDARY}. */
        record Chevron(int color) implements Adornment {}

        /** Small caption in the caller's colour ("READY", "42%", "NEW"). */
        record Tag(String text, int color) implements Adornment {
            public Tag { text = text != null ? text : ""; }
        }

        /** {@code count} diamond pips in {@code color}, greyed when not {@code affordable} (a cost). */
        record Pips(int count, int color, boolean affordable) implements Adornment {}

        /** Small neutral caption that dims with its row ("x3", "12g"). */
        record Count(String text) implements Adornment {
            public Count { text = text != null ? text : ""; }
        }

        static Adornment chevron(int color) { return new Chevron(color); }
        static Adornment tag(String text, int color) { return new Tag(text, color); }
        static Adornment pips(int count, int color, boolean affordable) { return new Pips(count, color, affordable); }
        static Adornment count(String text) { return new Count(text); }
    }

    /** One list entry. Null label/adornment are normalised, so a row is always safe to paint. */
    public record Row(String label, boolean enabled, Adornment adornment) {
        public Row {
            label = label != null ? label : "";
            adornment = adornment != null ? adornment : Adornment.NONE;
        }

        public Row(String label) { this(label, true, Adornment.NONE); }

        public Row(String label, boolean enabled) { this(label, enabled, Adornment.NONE); }
    }

    private record Glow(int color, float intensity) {}

    // ─────────────────────────────────────────────── Base metrics (multiplied by textScale())

    private static final float PAD = 10f;
    private static final float ROW_H = 30f;
    private static final float ROW_GAP = 2f;
    private static final float ROW_RADIUS = 3f;
    private static final float ACCENT_BAR_W = 3f;
    private static final float GUTTER_MAX = 40f;
    private static final float ADORN_INSET = 8f;
    private static final float CURSOR_TIP_GAP = 6f;
    private static final float CURSOR_BOB = 3f;
    private static final float SUBMENU_GAP = 6f;
    private static final int MAX_PIPS = 16;

    private static final float BOB_RATE = 6f;     // rad/s
    private static final float PULSE_RATE = 3f;   // rad/s
    private static final float PHASE_WRAP = (float) (Math.PI * 2.0);

    // ─────────────────────────────────────────────── State

    private List<Row> rows = List.of();
    private int cursor;
    private int held = -1;
    private boolean active = true;
    private float veil;
    private int accent;
    private boolean frame = true;
    private final Map<Integer, Glow> glows = new HashMap<>();
    /** Animation phase in seconds, wrapped at 2π so both sinusoids stay continuous and precise. */
    private float phase;

    // ─────────────────────────────────────────────── Fluent config

    public MMenuList rows(List<Row> value) {
        this.rows = value != null ? value.stream().filter(r -> r != null).toList() : List.of();
        return this;
    }

    /** Puts the cursor on {@code index}; out-of-range values clamp when read. */
    public MMenuList cursor(int index) { this.cursor = index; return this; }

    /**
     * The row whose submenu is open, or -1. While a row is held the live cursor is in the submenu,
     * so this list shows only the held mark.
     */
    public MMenuList held(int index) { this.held = index; return this; }

    /** False = STATIC: no cursor, no selection fill, nothing animating. */
    public MMenuList active(boolean v) { this.active = v; return this; }

    /** 0 = awake, 1 = fully veiled. Drawn only with the frame (a frameless list lives in someone else's). */
    public MMenuList veil(float amount) { this.veil = finite(amount) ? MColor.clamp01(amount) : 0f; return this; }

    /** Accent hairline of the HUD frame; 0 = none. */
    public MMenuList accent(int color) { this.accent = color; return this; }

    public MMenuList frame(boolean v) { this.frame = v; return this; }

    /**
     * Glow behind row {@code index} (a "ready" row). It pulses while the list is active and holds
     * steady at {@code intensity} while static; an enabled glowing row also takes the glow colour
     * for its label. {@code intensity} &lt;= 0 removes it.
     */
    public MMenuList rowGlow(int index, int color, float intensity) {
        if (!finite(intensity) || intensity <= 0f || (color & 0xFF000000) == 0) {
            glows.remove(index);
        } else {
            glows.put(index, new Glow(color, MColor.clamp01(intensity)));
        }
        return this;
    }

    public MMenuList clearRowGlows() { glows.clear(); return this; }

    @Override public MMenuList scale(float scale) { super.scale(scale); return this; }
    @Override public MMenuList scaleText(boolean v) { super.scaleText(v); return this; }
    @Override public MMenuList position(float x, float y) { super.position(x, y); return this; }
    @Override public MMenuList size(float w, float h) { super.size(w, h); return this; }
    @Override public MMenuList bounds(float x, float y, float w, float h) {
        super.bounds(x, y, w, h); return this;
    }

    // ─────────────────────────────────────────────── Queries

    public List<Row> rows() { return rows; }
    public int rowCount() { return rows.size(); }
    public int held() { return held >= 0 && held < rows.size() ? held : -1; }
    public boolean active() { return active; }
    public float veil() { return veil; }

    /** Cursor index clamped into the list, or -1 when the list is empty. */
    public int cursor() {
        if (rows.isEmpty()) return -1;
        return Math.max(0, Math.min(rows.size() - 1, cursor));
    }

    /** The row under the cursor, or null when the list is empty. */
    public Row selectedRow() {
        int i = cursor();
        return i < 0 ? null : rows.get(i);
    }

    // ─────────────────────────────────────────────── Navigation (no canvas needed)

    /** Moves the cursor by {@code delta} rows, wrapping in both directions. @return the new cursor */
    public int moveCursor(int delta) {
        int n = rows.size();
        if (n == 0) return -1;
        cursor = Math.floorMod(cursor() + delta, n);
        return cursor;
    }

    /**
     * Pointer hover/click: puts the cursor on the row under {@code (px, py)}. A static list ignores
     * the pointer.
     * @return true when a row was under the pointer
     */
    public boolean setCursorFromPointer(float px, float py) {
        if (!active) return false;
        int i = rowAt(px, py);
        if (i < 0) return false;
        cursor = i;
        return true;
    }

    /** Index of the row containing the point, or -1. Same rects as drawing. */
    public int rowAt(float px, float py) {
        for (int i = 0; i < rows.size(); i++) {
            float[] r = rowRect(i);
            if (r[2] > 0f && r[3] > 0f
                    && px >= r[0] && px < r[0] + r[2] && py >= r[1] && py < r[1] + r[3]) {
                return i;
            }
        }
        return -1;
    }

    // ─────────────────────────────────────────────── Geometry

    /** Frame height that holds {@code rowCount} rows at their design height, at this widget's scale. */
    public float preferredHeight(int rowCount) {
        float s = textScale();
        int n = Math.max(0, rowCount);
        return n * ROW_H * s + Math.max(0, n - 1) * rowGap(s) + 2f * Math.round(PAD * s);
    }

    /** {@link #preferredHeight(int)} for the current rows. */
    public float preferredHeight() { return preferredHeight(rows.size()); }

    /** Current row height: the design height, compressed when the frame cannot hold every row. */
    public float rowHeight() {
        if (!boundsUsable()) return 0f;
        float s = textScale();
        int n = Math.max(1, rows.size());
        float inner = height - 2f * pad(s) - (n - 1) * gapBetweenRows(s);
        return (float) Math.floor(Math.min(ROW_H * s, Math.max(0f, inner / n)));
    }

    /** {@code {x, y, w, h}} of row {@code index}; all zeros when out of range or the bounds are unusable. */
    public float[] rowRect(int index) {
        if (index < 0 || index >= rows.size() || !boundsUsable()) return new float[4];
        float s = textScale();
        float inset = Math.min(Math.round(PAD * s * 0.5f), (float) Math.floor(width / 2f));
        float rowH = rowHeight();
        float top = y + pad(s) + index * (rowH + gapBetweenRows(s));
        return new float[]{x + inset, top, Math.max(0f, width - 2f * inset), rowH};
    }

    /**
     * Where to attach a submenu opened from {@code row}: just right of this list, placed so the
     * submenu's first row is level with the opener. {@code {x, y}}; the caller clamps to its screen.
     */
    public float[] anchorRightOf(int row) {
        float s = textScale();
        float top = y;
        if (row >= 0 && row < rows.size() && boundsUsable()) top = rowRect(row)[1] - pad(s);
        return new float[]{x + Math.max(0f, width) + SUBMENU_GAP * s, top};
    }

    private float pad(float s) {
        return Math.min(Math.round(PAD * s), (float) Math.floor(height / 2f));
    }

    /** The design gap, given up before a frame too short for even the gaps lets a row slot escape it. */
    private float gapBetweenRows(float s) {
        int n = rows.size();
        if (n <= 1) return rowGap(s);
        float room = Math.max(0f, height - 2f * pad(s));
        return Math.min(rowGap(s), (float) Math.floor(room / (n - 1)));
    }

    private static float rowGap(float s) {
        return Math.round(ROW_GAP * s);
    }

    private boolean boundsUsable() {
        return finite(x) && finite(y) && finite(width) && finite(height) && width > 0f && height > 0f;
    }

    private static boolean finite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    // ─────────────────────────────────────────────── Animation

    /** Advances the cursor bob and glow pulse. Negative, NaN and infinite steps are ignored. */
    public void update(float dt) {
        if (!finite(dt) || dt <= 0f) return;
        phase = (phase + dt % PHASE_WRAP) % PHASE_WRAP;
    }

    /** 0..1 cursor bob. */
    private float bob() {
        return 0.5f + 0.5f * (float) Math.sin(phase * BOB_RATE);
    }

    /** Live glow strength around the caller's intensity; steady when the list is static. */
    private float glowStrength(float intensity) {
        if (!active) return intensity;
        return MColor.clamp01(intensity * (0.8f + 0.2f * (float) Math.sin(phase * PULSE_RATE)));
    }

    // ─────────────────────────────────────────────── Render

    @Override
    public void render(MasonryUI ui) {
        if (ui == null) return;
        Canvas canvas = ui.canvas();
        if (canvas == null || !boundsUsable()) return;
        float s = textScale();

        if (frame) MPainter.hudFrame(canvas, x, y, width, height, accent, 1f);

        int shownHeld = active ? held() : -1;
        int shownCursor = active && shownHeld < 0 ? cursor() : -1;

        int save = canvas.save();
        try {
            canvas.clipRect(Rect.makeXYWH(x, y, width, height));
            for (int i = 0; i < rows.size(); i++) {
                float[] r = rowRect(i);
                if (r[2] <= 0f || r[3] <= 0f) continue;
                paintRow(ui, canvas, r, rows.get(i), i == shownCursor, i == shownHeld, glows.get(i), s);
            }
        } finally {
            canvas.restoreToCount(save);
        }

        if (frame && veil > 0f) MPainter.hudVeil(canvas, x, y, width, height, veil);
    }

    private void paintRow(MasonryUI ui, Canvas canvas, float[] r, Row row, boolean selected, boolean isHeld,
                          Glow glow, float s) {
        if (glow != null) paintGlow(canvas, r, glow.color(), glowStrength(glow.intensity()), s);

        float radius = Math.min(ROW_RADIUS * s, r[3] / 2f);
        if (selected) {
            MPainter.fillRoundedRect(canvas, r[0], r[1], r[2], r[3], radius, MStyle.ROW_CURRENT);
            float barW = Math.min(ACCENT_BAR_W * s, r[2]);
            MPainter.fillRoundedRect(canvas, r[0], r[1], barW, r[3], Math.min(barW / 2f, radius), MStyle.TEXT_ACCENT);
        } else if (isHeld) {
            MPainter.fillRoundedRect(canvas, r[0], r[1], r[2], r[3], radius, MStyle.ROW_HELD);
        }

        float gutter = Math.min(r[3] * 1.35f, GUTTER_MAX * s);
        if (selected || isHeld) paintCursor(canvas, r, gutter, selected, s);

        float right = r[0] + r[2] - ADORN_INSET * s;
        right = paintAdornment(ui, canvas, r, row, right, s);

        int color = !row.enabled() ? MStyle.TEXT_DISABLED : glow != null ? glow.color() : MStyle.TEXT_PRIMARY;
        float labelX = r[0] + gutter;
        float maxW = right - labelX;
        if (maxW <= 0f || row.label().isEmpty()) return;
        Font sized = ui.fonts().forHeight(MStyle.FONT_ITEM * s, r[3]);
        if (sized == null) return;
        Font font = ui.fonts().fit(row.label(), sized.getSize(), maxW, 0.6f);
        if (font == null) return;
        MPainter.drawText(canvas, row.label(), labelX, MPainter.baselineFor(r[1] + r[3] / 2f, font.getSize()),
                font, color, MPainter.Align.LEFT);
    }

    private void paintCursor(Canvas canvas, float[] r, float gutter, boolean live, float s) {
        float tipGap = CURSOR_TIP_GAP * s;
        float size = Math.min(r[3] * 0.72f, gutter - tipGap - ACCENT_BAR_W * s);
        if (size <= 2f) return;
        float travel = live ? Math.min(bob() * CURSOR_BOB * s, Math.max(0f, tipGap - 1f)) : 0f;
        float hx = r[0] + gutter - tipGap - size + travel;
        float hy = r[1] + (r[3] - size) / 2f;
        float alpha = live ? 1f : 0.45f;
        MSymbol.HAND_POINT.drawWithShadow(canvas, hx, hy, size, size,
                MColor.fade(MStyle.TEXT_PRIMARY, alpha), MColor.fade(MStyle.TEXT_SHADOW, alpha));
    }

    /** Draws the row's adornment right-aligned at {@code right}; returns the right limit left for the label. */
    private float paintAdornment(MasonryUI ui, Canvas canvas, float[] r, Row row, float right, float s) {
        return switch (row.adornment()) {
            case Adornment.None ignored -> right;
            case Adornment.Chevron chevron -> {
                float size = r[3] * 0.7f;
                int base = (chevron.color() & 0xFF000000) != 0 ? chevron.color() : MStyle.TEXT_SECONDARY;
                MSymbol.CHEVRON_RIGHT.drawWithShadow(canvas, right - size, r[1] + (r[3] - size) / 2f, size, size,
                        row.enabled() ? base : MStyle.TEXT_DISABLED, MStyle.TEXT_SHADOW);
                yield right - size - 4f * s;
            }
            case Adornment.Tag tag -> paintCaption(ui, canvas, r, right, tag.text(), tag.color(), s);
            case Adornment.Count count -> paintCaption(ui, canvas, r, right, count.text(),
                    row.enabled() ? MStyle.TEXT_SECONDARY : MStyle.TEXT_DISABLED, s);
            case Adornment.Pips pips -> paintPips(canvas, r, right, pips, s);
        };
    }

    private float paintCaption(MasonryUI ui, Canvas canvas, float[] r, float right, String text, int color, float s) {
        if (text.isEmpty()) return right;
        Font font = ui.fonts().forHeight(MStyle.FONT_META * s, r[3]);
        if (font == null) return right;
        MPainter.drawText(canvas, text, right, MPainter.baselineFor(r[1] + r[3] / 2f, font.getSize()),
                font, color, MPainter.Align.RIGHT);
        return right - MPainter.measureWidth(font, text) - 6f * s;
    }

    private static float paintPips(Canvas canvas, float[] r, float right, Adornment.Pips pips, float s) {
        int n = Math.min(MAX_PIPS, pips.count());
        if (n <= 0) return right;
        float size = Math.min(r[3] * 0.5f, 14f * s);
        float gap = 2f * s;
        float left = right - n * size - (n - 1) * gap;
        float top = r[1] + (r[3] - size) / 2f;
        int color = pips.affordable() ? pips.color() : MStyle.TEXT_DISABLED;
        for (int i = 0; i < n; i++) {
            diamond(canvas, left + i * (size + gap), top, size, color);
        }
        return left - 4f * s;
    }

    private static void diamond(Canvas canvas, float px, float py, float size, int color) {
        if (size <= 0f) return;
        float h = size / 2f;
        try (PathBuilder pb = new PathBuilder()) {
            pb.moveTo(px + h, py);
            pb.lineTo(px + size, py + h);
            pb.lineTo(px + h, py + size);
            pb.lineTo(px, py + h);
            pb.closePath();
            try (Path path = pb.build();
                 Paint fill = new Paint().setColor(color).setAntiAlias(true);
                 Paint edge = new Paint().setColor(MStyle.GAUGE_OUTLINE).setAntiAlias(true)
                         .setMode(io.github.humbleui.skija.PaintMode.STROKE).setStrokeWidth(1f)) {
                canvas.drawPath(path, fill);
                canvas.drawPath(path, edge);
            }
        }
    }

    private static void paintGlow(Canvas canvas, float[] r, int color, float strength, float s) {
        float k = MColor.clamp01(strength);
        if (k <= 0f) return;
        float radius = Math.min(ROW_RADIUS * s, r[3] / 2f);
        float halo = 2f * s;
        MPainter.fillRoundedRect(canvas, r[0] - halo, r[1] - halo, r[2] + 2f * halo, r[3] + 2f * halo,
                radius + halo, MColor.withAlpha(color, 0.12f * k));
        MPainter.fillRoundedRect(canvas, r[0], r[1], r[2], r[3], radius, MColor.withAlpha(color, 0.22f * k));
        MPainter.strokeRoundedRect(canvas, r[0], r[1], r[2], r[3], radius, MColor.withAlpha(color, k),
                Math.max(1f, s));
    }
}
