package com.stonebreak.rendering.UI.masonryUI;

import com.stonebreak.ui.startupIntro.tween.EasingFunctions;
import com.stonebreak.ui.startupIntro.tween.EasingType;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.types.Rect;

import java.util.ArrayList;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.Locale;

/**
 * A modal result / summary card: how a battle went, what a crafting run produced, the day's totals.
 * A title (the gold extruded house title when its accent is {@link MStyle#TEXT_ACCENT}), an optional
 * subtitle, a block of label/value stats in columns ({@link MStatRow}s; numeric ones can count up),
 * and a row of {@link MButton}s along the bottom.
 *
 * <p>The card owns its buttons' layout and focus: keyboard ({@link #moveFocus}), pointer
 * ({@link #hover}, {@link #click}) and {@link #activate} all go through it, and the focused button is
 * simply the selected {@code MButton}. {@link #buttonRect} is the one formula the painter and the
 * hit-test share. Its bounds are where it <em>sits</em>; {@link #rise} slides it up into them and
 * fades it in, and it answers input only once fully risen, so a confirm mashed during whatever came
 * before cannot press a button nobody has seen yet.
 *
 * <p>Deterministic: the count-up advances only through {@link #update} (and only once risen). A card
 * given less height than {@link #preferredHeight()} compresses every internal metric to fit.
 */
public class MResultCard extends MWidget {

    private static final float PAD = 18f;
    private static final float TITLE_H = 52f;
    private static final float SUBTITLE_H = 20f;
    private static final float HEADER_GAP = 10f;
    private static final float STAT_ROW_H = 24f;
    private static final float STAT_COL_GAP = 28f;
    private static final float BUTTON_H = 40f;
    private static final float BUTTON_GAP = 10f;
    private static final float BUTTONS_GAP_ABOVE = 12f;
    private static final float EXTRUDE_STEP = 1.5f;
    private static final int EXTRUDE_LAYERS = 4;

    private static final class Stat {
        final MStatRow row = new MStatRow();
        final boolean numeric;
        final float value;
        final String format;
        final String text;

        Stat(String label, String text, boolean numeric, float value, String format) {
            this.text = text == null ? "" : text;
            this.numeric = numeric;
            this.value = value;
            this.format = format;
            row.label(label == null ? "" : label);
            if (!numeric) row.value(this.text);
        }
    }

    private String title = "";
    private int titleAccent = MStyle.TEXT_ACCENT;
    private String subtitle = "";
    private final List<Stat> stats = new ArrayList<>();
    private final List<MButton> buttons = new ArrayList<>();
    private int columns = 2;
    private int focused;
    private float rise = 1f;
    private boolean scrim;
    private float screenW, screenH;
    private float countSeconds;
    private float countAge;

    // ─────────────────────────────────────────────── Fluent config

    /** Headline. {@code accent} colours it and the rule under it; gold gets the extruded house title. */
    public MResultCard title(String text, int accent) {
        this.title = text == null ? "" : text;
        this.titleAccent = accent;
        return this;
    }

    public MResultCard subtitle(String text) {
        this.subtitle = text == null ? "" : text;
        return this;
    }

    /** Adds a fixed label/value line. */
    public MResultCard stat(String label, String value) {
        stats.add(new Stat(label, value, false, 0f, null));
        return this;
    }

    /**
     * Adds a numeric line that takes part in the count-up. {@code format} is a
     * {@link String#format} pattern for one number ({@code "%.0f"}, {@code "%d"}, {@code "%.1f s"});
     * null means a whole number. A pattern that cannot format the value falls back to the whole number.
     */
    public MResultCard statNumber(String label, float value, String format) {
        stats.add(new Stat(label, null, true, Float.isNaN(value) || Float.isInfinite(value) ? 0f : value, format));
        refreshNumbers();
        return this;
    }

    public MResultCard clearStats() {
        stats.clear();
        return this;
    }

    /** Columns the stats are dealt into, first column filled first (default 2, 1..4). */
    public MResultCard columns(int count) {
        this.columns = Math.max(1, Math.min(4, count));
        return this;
    }

    /**
     * Numeric stats climb from 0 to their value over {@code seconds} once the card has risen
     * (0 = show final values at once, the default). Restarts the climb.
     */
    public MResultCard countUp(float seconds) {
        this.countSeconds = seconds > 0f && seconds < 1.0e4f ? seconds : 0f;
        return restartCountUp();
    }

    public MResultCard restartCountUp() {
        countAge = 0f;
        refreshNumbers();
        return this;
    }

    /**
     * The buttons, left to right. The card lays them out, scales them with itself and marks the
     * focused one selected; their labels, enabled state and callbacks stay the caller's.
     */
    public MResultCard buttons(List<MButton> list) {
        buttons.clear();
        if (list != null) {
            for (MButton b : list) if (b != null) buttons.add(b);
        }
        focused = 0;
        if (!buttons.isEmpty() && !buttons.get(0).enabled()) focused = nextEnabled(0, 1);
        syncSelection();
        return this;
    }

    /**
     * 0 = below its seat and invisible, 1 = seated. The caller eases and advances it; the card slides
     * and fades accordingly and is {@link #interactive} only at 1. Default 1.
     */
    public MResultCard rise(float amount) {
        this.rise = Float.isNaN(amount) ? 0f : MColor.clamp01(amount);
        syncSelection();
        return this;
    }

    /** Whether to darken everything behind the card ({@link MScreenFx#scrim}, following the rise). */
    public MResultCard scrim(boolean on) {
        this.scrim = on;
        return this;
    }

    /**
     * Window size: what the scrim covers and how far below its seat the card starts (just off the
     * bottom edge). Without it the scrim covers the canvas clip and the card rises 60% of its height.
     */
    public MResultCard screen(float w, float h) {
        this.screenW = w > 0f ? w : 0f;
        this.screenH = h > 0f ? h : 0f;
        return this;
    }

    @Override public MResultCard scale(float scale) { super.scale(scale); return this; }
    @Override public MResultCard scaleText(boolean v) { super.scaleText(v); return this; }
    @Override public MResultCard position(float x, float y) { super.position(x, y); return this; }
    @Override public MResultCard size(float width, float height) { super.size(width, height); return this; }
    @Override public MResultCard bounds(float x, float y, float width, float height) {
        super.bounds(x, y, width, height); return this;
    }

    // ─────────────────────────────────────────────── Timeline

    /** Drives the count-up; it only runs while the card is fully risen. */
    public void update(float dt) {
        if (!(dt > 0f) || Float.isInfinite(dt) || !interactive()) return;
        if (countAge < countSeconds) {
            countAge = Math.min(countSeconds, countAge + dt);
            refreshNumbers();
        }
    }

    /** 0..1 share of each numeric stat's final value currently shown (eased). */
    public float countProgress() {
        if (countSeconds <= 0f || countAge >= countSeconds) return 1f;
        return EasingFunctions.apply(MColor.clamp01(countAge / countSeconds), EasingType.EaseOutCubic);
    }

    /** The text stat {@code index} shows right now, or "" for an index that is no stat. */
    public String statText(int index) {
        if (index < 0 || index >= stats.size()) return "";
        Stat s = stats.get(index);
        return s.numeric ? format(s.format, shown(s)) : s.text;
    }

    public int statCount() { return stats.size(); }

    private float shown(Stat s) {
        float p = countProgress();
        return p >= 1f ? s.value : s.value * p;   // the exact value at the end, never value × 0.99999
    }

    private void refreshNumbers() {
        for (Stat s : stats) if (s.numeric) s.row.value(format(s.format, shown(s)));
    }

    static String format(String pattern, float value) {
        float v = value == 0f ? 0f : value;       // no "-0"
        if (pattern == null || pattern.isEmpty()) return String.valueOf(Math.round(v));
        try {
            return String.format(Locale.ROOT, pattern, v);
        } catch (IllegalFormatException floatRefused) {
            try {
                return String.format(Locale.ROOT, pattern, Math.round(v));   // "%d" and friends
            } catch (IllegalFormatException e) {
                return String.valueOf(Math.round(v));
            }
        }
    }

    // ─────────────────────────────────────────────── Focus + input

    public float rise() { return rise; }

    /** True once the card is seated: only then do focus changes and {@link #activate} do anything. */
    public boolean interactive() { return rise >= 1f; }

    public int buttonCount() { return buttons.size(); }

    /** The buttons as given, left to right (read-only view). */
    public List<MButton> buttons() { return java.util.Collections.unmodifiableList(buttons); }

    /** Index of the focused button, or −1 when there are none. */
    public int focused() { return buttons.isEmpty() ? -1 : focused; }

    /** Pointer / direct focus. Ignored until {@link #interactive}, for a bad index, and for a disabled button. */
    public void focus(int index) {
        if (!interactive() || index < 0 || index >= buttons.size() || !buttons.get(index).enabled()) return;
        focused = index;
        syncSelection();
    }

    /** Keyboard: {@code delta} buttons along, wrapping, skipping disabled ones. Ignored until {@link #interactive}. */
    public void moveFocus(int delta) {
        if (!interactive() || buttons.isEmpty() || delta == 0) return;
        int target = Math.floorMod(focused + delta, buttons.size());
        focused = buttons.get(target).enabled() ? target : nextEnabled(target, delta > 0 ? 1 : -1);
        syncSelection();
    }

    private int nextEnabled(int from, int step) {
        int n = buttons.size();
        for (int i = 1; i <= n; i++) {
            int candidate = Math.floorMod(from + i * step, n);
            if (buttons.get(candidate).enabled()) return candidate;
        }
        return Math.floorMod(from, Math.max(1, n));   // nothing enabled: stay put
    }

    /**
     * Presses the focused button.
     * @return true when its callback ran. The callback may have torn the screen down, so callers
     *         should not assume the card is still in use afterwards.
     */
    public boolean activate() {
        if (!interactive() || buttons.isEmpty()) return false;
        MButton b = buttons.get(focused);
        if (!b.enabled() || b.getOnClick() == null) return false;
        b.click();
        return true;
    }

    /** Pointer move: focuses the button under it. @return that button's index, or −1 */
    public int hover(float px, float py) {
        int index = interactive() ? buttonAt(px, py) : -1;
        if (index >= 0) focus(index);
        return index;
    }

    /** Pointer press: focuses and presses the button under it. @return true when a callback ran */
    public boolean click(float px, float py) {
        if (!interactive()) return false;
        int index = buttonAt(px, py);
        if (index < 0 || !buttons.get(index).enabled()) return false;
        focus(index);
        return activate();
    }

    private void syncSelection() {
        boolean live = interactive();
        for (int i = 0; i < buttons.size(); i++) buttons.get(i).setSelected(live && i == focused);
    }

    // ─────────────────────────────────────────────── Layout (shared by painting and hit-testing)

    /** Height, at the current scale, at which nothing inside has to compress. */
    public float preferredHeight() {
        return designHeight() * textScale();
    }

    private float designHeight() {
        float h = PAD * 0.5f + headerDesignHeight();
        if (!stats.isEmpty()) h += HEADER_GAP + rowsPerColumn() * STAT_ROW_H;
        if (!buttons.isEmpty()) h += BUTTONS_GAP_ABOVE + BUTTON_H;
        return h + PAD;
    }

    private float headerDesignHeight() {
        return (title.isEmpty() ? 0f : TITLE_H) + (subtitle.isEmpty() ? 0f : SUBTITLE_H);
    }

    private int rowsPerColumn() {
        return (stats.size() + columns - 1) / columns;
    }

    /** Scale × compression: the one unit every internal metric is multiplied by. */
    private float unit() {
        float s = textScale();
        if (!(s > 0f) || !(height > 0f)) return 0f;
        float design = designHeight() * s;
        return s * (design <= 0f ? 1f : Math.min(1f, height / design));
    }

    /** Rect {@code {x, y, w, h}} of button {@code index} in the seated card; zeros for a bad index. */
    public float[] buttonRect(int index) {
        int n = buttons.size();
        float u = unit();
        if (index < 0 || index >= n || u <= 0f || !(width > 0f)) return new float[4];
        float pad = PAD * u, gap = BUTTON_GAP * u, bh = BUTTON_H * u;
        float bw = Math.max(0f, (width - 2f * pad - (n - 1) * gap) / n);
        return new float[]{(float) Math.floor(x + pad + index * (bw + gap)),
                (float) Math.floor(y + height - pad - bh), (float) Math.floor(bw), (float) Math.floor(bh)};
    }

    /** Index of the button at {@code (px, py)} in the seated card, or −1. Pure geometry. */
    public int buttonAt(float px, float py) {
        for (int i = 0; i < buttons.size(); i++) {
            float[] r = buttonRect(i);
            if (r[2] > 0f && r[3] > 0f && px >= r[0] && px <= r[0] + r[2] && py >= r[1] && py <= r[1] + r[3]) return i;
        }
        return -1;
    }

    /** Cell {@code {x, y, w, h}} of stat {@code index} in the seated card; zeros for a bad index. */
    public float[] statRect(int index) {
        float u = unit();
        if (index < 0 || index >= stats.size() || u <= 0f || !(width > 0f)) return new float[4];
        float pad = PAD * u;
        float top = y + PAD * 0.5f * u + headerDesignHeight() * u + HEADER_GAP * u;
        float bottom = y + height - pad - (buttons.isEmpty() ? 0f : (BUTTON_H + BUTTONS_GAP_ABOVE) * u);
        int rows = Math.max(1, rowsPerColumn());
        float rowH = Math.max(0f, Math.min(STAT_ROW_H * u, (bottom - top) / rows));
        top += Math.max(0f, (bottom - top - rows * rowH) / 2f);   // a roomy card centres the block
        float colGap = STAT_COL_GAP * u;
        float colW = Math.max(0f, (width - 2f * pad - (columns - 1) * colGap) / columns);
        int col = index / rows, row = index % rows;
        return new float[]{x + pad + col * (colW + colGap), top + row * rowH, colW, rowH};
    }

    /** How far below its seat the card is drawn right now. */
    public float riseOffset() {
        float travel = screenH > 0f ? Math.max(0f, screenH - y) : height * 0.6f;
        return Math.round((1f - rise) * travel);
    }

    // ─────────────────────────────────────────────── Render

    @Override
    public void render(MasonryUI ui) {
        if (ui == null || rise <= 0f) return;
        Canvas canvas = ui.canvas();
        if (canvas == null) return;
        if (scrim) paintScrim(canvas);
        float u = unit();
        if (u <= 0f || !(width > 0f) || Float.isNaN(x) || Float.isNaN(y)) return;

        float dy = riseOffset();
        int saved = canvas.save();
        try {
            canvas.translate(0f, dy);
            if (rise < 1f) {
                // One layer for the whole card, so overlapping parts fade as one surface.
                canvas.saveLayerAlpha(Rect.makeXYWH(x - 8f, y - 8f, width + 20f, height + 20f),
                        Math.round(255f * rise));
            }
            MPainter.panel(canvas, x, y, width, height);
            paintHeader(ui, canvas, u);
            paintStats(ui, u);
            paintButtons(ui, u);
        } finally {
            canvas.restoreToCount(saved);   // pops the fade layer too
        }
    }

    private void paintScrim(Canvas canvas) {
        float w = screenW, h = screenH;
        if (!(w > 0f) || !(h > 0f)) {
            Rect clip = canvas.getLocalClipBounds();
            if (clip == null) return;
            w = clip.getRight();
            h = clip.getBottom();
        }
        MScreenFx.scrim(canvas, w, h, rise);
    }

    private void paintHeader(MasonryUI ui, Canvas canvas, float u) {
        float cx = x + width / 2f;
        float maxW = Math.max(0f, width - 2f * PAD * u);
        float cursor = y + PAD * 0.5f * u;
        if (!title.isEmpty()) {
            float rowH = TITLE_H * u;
            Font font = ui.fonts().fit(title, Math.min(MStyle.FONT_TITLE * u, rowH * 0.72f), maxW, 0.5f);
            if (font != null) {
                float baseline = MPainter.baselineFor(cursor + rowH / 2f, font.getSize());
                if (titleAccent == MStyle.TEXT_ACCENT) {
                    paintExtrudedTitle(canvas, cx, baseline, font, u);
                } else {
                    MPainter.drawText(canvas, title, cx, baseline, font, titleAccent, MPainter.Align.CENTER);
                }
            }
            cursor += rowH;
        }
        if (!subtitle.isEmpty()) {
            float rowH = SUBTITLE_H * u;
            Font font = ui.fonts().fit(subtitle, Math.min(MStyle.FONT_META * u, rowH * 0.8f), maxW, 0.6f);
            if (font != null) {
                MPainter.drawText(canvas, subtitle, cx, MPainter.baselineFor(cursor + rowH / 2f, font.getSize()),
                        font, MStyle.TEXT_SECONDARY, MPainter.Align.CENTER);
            }
            cursor += rowH;
        }
        if (cursor > y + PAD * 0.5f * u) {
            MPainter.fillRect(canvas, x + width * 0.12f, (float) Math.floor(cursor + 2f * u), width * 0.76f,
                    Math.max(1f, Math.round(u)), MColor.fade(titleAccent, 0.7f));
        }
    }

    /**
     * The house title (as on the pause menu): gold face over a darker gold step over a stone-dark
     * extrusion running down-right, every colour derived from the shared tokens.
     */
    private void paintExtrudedTitle(Canvas canvas, float cx, float baseline, Font font, float u) {
        float step = Math.max(1f, EXTRUDE_STEP * u);
        for (int i = EXTRUDE_LAYERS; i >= 0; i--) {
            int color = switch (i) {
                case 0 -> MStyle.TEXT_ACCENT;
                case 1 -> MColor.lerp(MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW, 0.2f);
                default -> MColor.fade(MColor.lerp(MStyle.PANEL_FILL_DEEP, MStyle.TEXT_SHADOW,
                        (i - 2f) / (EXTRUDE_LAYERS - 2f)), 0.78f);
            };
            MPainter.drawCenteredString(canvas, title, cx + i * step, baseline + i * step, font, color);
        }
    }

    private void paintStats(MasonryUI ui, float u) {
        for (int i = 0; i < stats.size(); i++) {
            float[] cell = statRect(i);
            if (cell[2] <= 0f || cell[3] <= 0f) continue;
            // Rows shorter than the design shrink their text with them.
            float rowScale = u * Math.min(1f, cell[3] / (STAT_ROW_H * u));
            MStatRow row = stats.get(i).row;
            row.scale(rowScale);
            row.bounds(cell[0], cell[1], cell[2], cell[3]);
            row.render(ui);
        }
    }

    private void paintButtons(MasonryUI ui, float u) {
        for (int i = 0; i < buttons.size(); i++) {
            float[] r = buttonRect(i);
            if (r[2] <= 0f || r[3] <= 0f) continue;
            MButton b = buttons.get(i);
            b.scale(u);
            b.bounds(r[0], r[1], r[2], r[3]);
            b.render(ui);
        }
    }
}
