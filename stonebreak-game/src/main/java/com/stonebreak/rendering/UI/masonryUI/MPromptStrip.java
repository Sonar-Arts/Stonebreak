package com.stonebreak.rendering.UI.masonryUI;

import com.stonebreak.ui.startupIntro.tween.EasingFunctions;
import com.stonebreak.ui.startupIntro.tween.EasingType;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.PaintStrokeCap;
import io.github.humbleui.skija.PaintStrokeJoin;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;
import io.github.humbleui.types.Rect;

import java.util.Arrays;
import java.util.List;

/**
 * A strip of N sequential input prompts in a HUD frame: direction strings, quick-time steps, the
 * order of a recipe, a lock's tumblers. Each {@link Step} is a vector glyph and/or a key legend;
 * each cell is in one {@link State}:
 *
 * <ul>
 *   <li>{@link State#UPCOMING} dimmed, still to come;</li>
 *   <li>{@link State#CURRENT} enlarged and pulsing, underlined by a draining {@link #timer};</li>
 *   <li>{@link State#RESOLVED_BEST} a gold stamp, {@link State#RESOLVED_OK} a pale stamp,
 *       {@link State#RESOLVED_FAIL} dark, red and cracked.</li>
 * </ul>
 *
 * <p>A cell is resolved with {@link #markResolved}, which also starts its stamp "pop"; everything
 * else follows {@link #current}. {@link #caption} and {@link #counter} fill the two side areas,
 * {@link #slide} brings the strip up from below its resting bounds, and {@link #flashText} shows
 * a one-off word above it. The widget holds no vocabulary of its own: every word is a parameter.
 *
 * <p>Deterministic: the pulse, the stamp pops and the flash age advance only in {@link #update}.
 */
public class MPromptStrip extends MWidget {

    /** One prompt: a glyph (may be null), a key legend (may be empty), or both. */
    public record Step(MSymbol glyph, String key) {
        public Step { key = key != null ? key : ""; }
    }

    public enum State { UPCOMING, CURRENT, RESOLVED_BEST, RESOLVED_OK, RESOLVED_FAIL }

    // ─────────────────────────────────────────────── Base metrics (multiplied by textScale())

    private static final float PAD = 8f;
    private static final float CELL = 44f;
    private static final float CELL_GAP = 12f;
    private static final float TIMER_H = 6f;
    private static final float TIMER_GAP = 5f;
    private static final float SIDE_W = 92f;
    private static final float CELL_RADIUS = 5f;
    /** The prompt awaiting input is drawn this much larger than the rest. */
    public static final float CURRENT_SCALE = 1.2f;
    /** How long a freshly resolved cell takes to settle from its pop. */
    public static final float STAMP_SECONDS = 0.16f;
    public static final float DEFAULT_FLASH_SECONDS = 1.2f;

    private static final float PULSE_RATE = 9f;   // rad/s
    private static final float PHASE_WRAP = (float) (Math.PI * 2.0);

    // ─────────────────────────────────────────────── State

    private List<Step> steps = List.of();
    private State[] resolved = new State[0];
    private float[] stampAge = new float[0];
    private int current = -1;
    private int upNext = -1;
    private float timer = 1f;
    private int timerLow = MStyle.VITAL_CRIT, timerMid = MStyle.VITAL_WARN, timerHigh = MStyle.VITAL_OK;
    private String caption = "";
    private String counter = "";
    private float slide = 1f;
    private float slideDistance = Float.NaN;
    private int accent;
    private String flashText = "";
    private float flashAge = -1f;
    private float flashSeconds = DEFAULT_FLASH_SECONDS;
    private int flashColor = MStyle.TEXT_ACCENT;
    private float phase;

    // ─────────────────────────────────────────────── Fluent config

    /** Replaces the steps and clears every result. */
    public MPromptStrip steps(List<Step> value) {
        this.steps = value != null ? value.stream().filter(s -> s != null).toList() : List.of();
        this.resolved = new State[steps.size()];
        this.stampAge = new float[steps.size()];
        Arrays.fill(stampAge, -1f);
        return this;
    }

    /** The step awaiting input, or -1 for none (between prompts, before the start, after the end). */
    public MPromptStrip current(int index) { this.current = index; return this; }

    /** A step to light up without enlarging it: the one about to open. -1 = none. */
    public MPromptStrip upNext(int index) { this.upNext = index; return this; }

    /** Share of the current step's time that is left, 1 → 0; drawn as a draining underline. */
    public MPromptStrip timer(float remainingFraction) {
        this.timer = Float.isNaN(remainingFraction) ? 0f : MColor.clamp01(remainingFraction);
        return this;
    }

    /** Timer colours for empty / half / full; defaults to the shared vital ramp tokens. */
    public MPromptStrip timerColors(int low, int mid, int high) {
        this.timerLow = low; this.timerMid = mid; this.timerHigh = high;
        return this;
    }

    /** Left-hand caption; a newline (else the first space) splits it over two lines. */
    public MPromptStrip caption(String value) { this.caption = value != null ? value : ""; return this; }

    /** Right-hand counter ("3 HITS"): the first part is drawn large, the rest small beneath it. */
    public MPromptStrip counter(String value) { this.counter = value != null ? value : ""; return this; }

    /** 0 = fully below its bounds (nothing drawn), 1 = at rest. */
    public MPromptStrip slide(float amount) {
        this.slide = Float.isNaN(amount) ? 0f : MColor.clamp01(amount);
        return this;
    }

    /** How far below its bounds the strip starts, in screen pixels; NaN = its own height plus a margin. */
    public MPromptStrip slideDistance(float pixels) { this.slideDistance = pixels; return this; }

    /** Accent hairline of the HUD frame; 0 = none. */
    public MPromptStrip accent(int color) { this.accent = color; return this; }

    /**
     * Shows a one-off word above the strip (it pops, holds and fades over {@link #flashDuration}),
     * starting at {@code age} seconds; {@link #update} carries it on. Null/empty text or a negative
     * age clears it.
     */
    public MPromptStrip flashText(String text, float age) {
        boolean on = text != null && !text.isEmpty() && !Float.isNaN(age) && age >= 0f;
        this.flashText = on ? text : "";
        this.flashAge = on ? age : -1f;
        return this;
    }

    public MPromptStrip flashDuration(float seconds) {
        this.flashSeconds = seconds > 0f && !Float.isInfinite(seconds) ? seconds : DEFAULT_FLASH_SECONDS;
        return this;
    }

    public MPromptStrip flashColor(int color) { this.flashColor = color; return this; }

    @Override public MPromptStrip scale(float scale) { super.scale(scale); return this; }
    @Override public MPromptStrip scaleText(boolean v) { super.scaleText(v); return this; }
    @Override public MPromptStrip position(float x, float y) { super.position(x, y); return this; }
    @Override public MPromptStrip size(float w, float h) { super.size(w, h); return this; }
    @Override public MPromptStrip bounds(float x, float y, float w, float h) {
        super.bounds(x, y, w, h); return this;
    }

    // ─────────────────────────────────────────────── Results

    /**
     * Stamps cell {@code index} with a {@code RESOLVED_*} state and starts its pop. Any other state
     * (or null) clears the cell's result. Out-of-range indices are ignored.
     */
    public MPromptStrip markResolved(int index, State state) {
        if (index < 0 || index >= steps.size()) return this;
        boolean isResult = state == State.RESOLVED_BEST || state == State.RESOLVED_OK || state == State.RESOLVED_FAIL;
        resolved[index] = isResult ? state : null;
        stampAge[index] = isResult ? 0f : -1f;
        return this;
    }

    /** Clears every result; the steps stay. */
    public MPromptStrip resetResults() {
        Arrays.fill(resolved, null);
        Arrays.fill(stampAge, -1f);
        return this;
    }

    /** The state cell {@code index} is drawn in; {@link State#UPCOMING} when out of range. */
    public State stateOf(int index) {
        if (index < 0 || index >= steps.size()) return State.UPCOMING;
        if (resolved[index] != null) return resolved[index];
        return index == current ? State.CURRENT : State.UPCOMING;
    }

    /** How many cells are in {@code state}. */
    public int count(State state) {
        int n = 0;
        for (int i = 0; i < steps.size(); i++) if (stateOf(i) == state) n++;
        return n;
    }

    public List<Step> steps() { return steps; }
    public int current() { return current; }
    public float timer() { return timer; }
    public float slide() { return slide; }
    /** Seconds the flash word has been showing, or -1 when none is. */
    public float flashAge() { return flashAge; }

    /** Pop scale of cell {@code index}'s stamp right now: 1.3 when it lands, settling to 1. */
    public float stampPop(int index) {
        if (index < 0 || index >= steps.size() || stampAge[index] < 0f) return 1f;
        float t = MColor.clamp01(stampAge[index] / STAMP_SECONDS);
        return 1f + 0.3f * (1f - EasingFunctions.apply(t, EasingType.EaseOutCubic));
    }

    // ─────────────────────────────────────────────── Animation

    /** Advances the pulse, the stamp pops and the flash word. Bad steps (NaN, ≤ 0, infinite) are ignored. */
    public void update(float dt) {
        if (Float.isNaN(dt) || Float.isInfinite(dt) || dt <= 0f) return;
        phase = (phase + dt % PHASE_WRAP) % PHASE_WRAP;
        for (int i = 0; i < stampAge.length; i++) {
            if (stampAge[i] >= 0f) stampAge[i] = Math.min(STAMP_SECONDS, stampAge[i] + dt);
        }
        if (flashAge >= 0f) {
            flashAge += dt;
            if (flashAge >= flashSeconds) {
                flashAge = -1f;
                flashText = "";
            }
        }
    }

    private float pulse() {
        return 0.5f + 0.5f * (float) Math.sin(phase * PULSE_RATE);
    }

    /** 0 → 1 → 0 over the flash: fast attack in the first fifth, eased release. */
    private float flashEnvelope() {
        if (flashAge < 0f) return 0f;
        float k = MColor.clamp01(flashAge / flashSeconds);
        if (k <= 0f || k >= 1f) return 0f;
        return k < 0.2f ? k / 0.2f : 1f - smoothstep((k - 0.2f) / 0.8f);
    }

    private static float smoothstep(float t) {
        float k = MColor.clamp01(t);
        return k * k * (3f - 2f * k);
    }

    // ─────────────────────────────────────────────── Geometry

    /** Pixels the strip is currently drawn below its resting bounds. */
    public float slideOffset() {
        float s = textScale();
        float distance = Float.isNaN(slideDistance) || Float.isInfinite(slideDistance)
                ? Math.max(0f, height) + 12f * s : Math.max(0f, slideDistance);
        return (1f - EasingFunctions.apply(slide, EasingType.EaseOutCubic)) * distance;
    }

    /**
     * Rest-size square of cell {@code index} as drawn (slide included): the row of cells is centred
     * between the two side areas and shrinks to fit. Zeros when out of range or the bounds are unusable.
     */
    public float[] cellRect(int index) {
        int n = steps.size();
        if (index < 0 || index >= n || !boundsUsable()) return new float[4];
        float s = textScale();
        float gap = CELL_GAP * s;
        float avail = Math.max(0f, width - 2f * (sideWidth(s) + PAD * s));
        float byHeight = height - (2f * PAD + TIMER_H + TIMER_GAP) * s;
        float byWidth = (avail - gap * (n - 1)) / n;
        if (byWidth < 2f * gap) {
            // Crowded: give up the design gap (keep a quarter-cell) before the row outgrows the frame.
            byWidth = avail / (n + (n - 1) * 0.25f);
            gap = byWidth * 0.25f;
        }
        float cell = Math.max(1f, Math.min(CELL * s, Math.min(byHeight, byWidth)));
        float rowW = cell * n + gap * (n - 1);
        float cx = x + (width - rowW) / 2f + index * (cell + gap);
        float cy = y + slideOffset() + (height - (cell + (TIMER_H + TIMER_GAP) * s)) / 2f;
        return new float[]{cx, cy, cell, cell};
    }

    /** The timer underline track beneath cell {@code index}. */
    public float[] timerRect(int index) {
        float[] c = cellRect(index);
        if (c[2] <= 0f) return new float[4];
        float s = textScale();
        float grow = c[2] * (CURRENT_SCALE - 1f) / 2f;
        return new float[]{c[0] - grow, c[1] + c[3] + grow + TIMER_GAP * s * 0.6f, c[2] + 2f * grow, TIMER_H * s};
    }

    /** Where the flash word is centred: a band just above the strip. */
    public float[] flashRect() {
        float s = textScale();
        float h = MStyle.FONT_TITLE * s * 1.5f;
        return new float[]{x, y + slideOffset() - h - 6f * s, Math.max(0f, width), h};
    }

    private float sideWidth(float s) {
        return caption.isEmpty() && counter.isEmpty() ? 0f : SIDE_W * s;
    }

    private boolean boundsUsable() {
        return finite(x) && finite(y) && finite(width) && finite(height) && width > 0f && height > 0f;
    }

    private static boolean finite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    private static float[] scaled(float[] r, float k) {
        float w = r[2] * k, h = r[3] * k;
        return new float[]{r[0] + (r[2] - w) / 2f, r[1] + (r[3] - h) / 2f, w, h};
    }

    // ─────────────────────────────────────────────── Render

    @Override
    public void render(MasonryUI ui) {
        if (ui == null) return;
        Canvas canvas = ui.canvas();
        if (canvas == null || slide <= 0f || !boundsUsable()) return;
        float s = textScale();
        float top = y + slideOffset();
        float flash = flashEnvelope();

        MPainter.hudFrame(canvas, x, top, width, height, accent, 1f);
        if (flash > 0f) {
            MPainter.strokeRoundedRect(canvas, x - 2f * s, top - 2f * s, width + 4f * s, height + 4f * s,
                    MStyle.PANEL_RADIUS + 2f * s, MColor.fade(flashColor, flash), Math.max(2f, 3f * s));
        }

        int n = steps.size();
        int save = canvas.save();
        try {
            // A frame too small for even the minimum cells must not leak them over the scene.
            canvas.clipRect(Rect.makeXYWH(x, top, width, height));
            for (int i = 0; i < n; i++) {
                if (stateOf(i) != State.CURRENT) paintCell(ui, canvas, i, s);
            }
            // Current prompt last: it is enlarged and must overlap its neighbours, not the reverse.
            if (current >= 0 && current < n && stateOf(current) == State.CURRENT) paintCell(ui, canvas, current, s);
        } finally {
            canvas.restoreToCount(save);
        }

        paintSides(ui, canvas, top, s);

        if (flash > 0f) {
            MPainter.fillRoundedRect(canvas, x, top, width, height, MStyle.PANEL_RADIUS,
                    MColor.withAlpha(flashColor, 0.26f * flash));
            paintFlashWord(ui, canvas, s);
        }
    }

    private void paintCell(MasonryUI ui, Canvas canvas, int i, float s) {
        float[] base = cellRect(i);
        if (base[2] <= 0f) return;
        State state = stateOf(i);
        boolean isCurrent = state == State.CURRENT;
        boolean lit = state == State.UPCOMING && i == upNext;
        float pulse = pulse();

        float k = isCurrent ? CURRENT_SCALE + 0.04f * pulse : stampPop(i);
        float[] c = scaled(base, k);
        float r = Math.min(CELL_RADIUS * s, c[2] / 2f);
        float border = Math.max(1.5f, 2f * s);

        int fill, edge, ink;
        boolean shadowed;
        switch (state) {
            case RESOLVED_BEST -> { fill = MStyle.TEXT_ACCENT; edge = MStyle.BUTTON_BORDER; ink = MStyle.TEXT_SHADOW; shadowed = false; }
            case RESOLVED_OK -> { fill = MStyle.TEXT_PRIMARY; edge = MStyle.BUTTON_BORDER; ink = MStyle.TEXT_SHADOW; shadowed = false; }
            case RESOLVED_FAIL -> { fill = MStyle.DROPDOWN_FILL; edge = MStyle.TEXT_ERROR; ink = MStyle.TEXT_ERROR; shadowed = true; }
            case CURRENT -> {
                fill = MStyle.BUTTON_FILL_HI;
                edge = MColor.lerp(MStyle.TEXT_ACCENT, MStyle.TEXT_PRIMARY, pulse);
                ink = MStyle.TEXT_PRIMARY; shadowed = true;
            }
            default -> {
                fill = lit ? MStyle.BUTTON_FILL : MStyle.BUTTON_FILL_DIS;
                edge = MStyle.BUTTON_BORDER;
                ink = lit ? MStyle.TEXT_SECONDARY : MStyle.TEXT_DISABLED; shadowed = true;
            }
        }

        if (isCurrent) {
            MPainter.fillRoundedRect(canvas, c[0] - 3f * s, c[1] - 3f * s, c[2] + 6f * s, c[3] + 6f * s, r + 3f * s,
                    MColor.withAlpha(MStyle.TEXT_ACCENT, 0.22f + 0.2f * pulse));
        }
        MPainter.fillRoundedRect(canvas, c[0], c[1], c[2], c[3], r, fill);
        MPainter.strokeRoundedRect(canvas, c[0], c[1], c[2], c[3], r, edge, isCurrent ? border * 1.4f : border);

        Step step = steps.get(i);
        if (step.glyph() != null) {
            // Nudged up-left of centre: the key legend owns the bottom-right corner.
            float g = c[2] * 0.72f;
            float gx = c[0] + c[2] * 0.44f - g / 2f, gy = c[1] + c[3] * 0.46f - g / 2f;
            if (shadowed) step.glyph().drawWithShadow(canvas, gx, gy, g, g, ink, MStyle.TEXT_SHADOW);
            else step.glyph().draw(canvas, gx, gy, g, g, ink);
        }
        if (!step.key().isEmpty()) paintKey(ui, canvas, c, step, k, ink, shadowed, s);
        if (state == State.RESOLVED_FAIL) crack(canvas, c, s);

        if (isCurrent) paintTimer(canvas, i);
    }

    private void paintKey(MasonryUI ui, Canvas canvas, float[] c, Step step, float k, int ink, boolean shadowed,
                          float s) {
        boolean alone = step.glyph() == null;
        float size = (alone ? MStyle.FONT_ITEM : MStyle.FONT_CAPTION) * k * s;
        Font font = ui.fonts().fit(step.key(), Math.min(size, c[3] * 0.62f), c[2] - 6f * s, 0.5f);
        if (font == null) return;
        float tx, ty;
        MPainter.Align align;
        if (alone) {
            tx = c[0] + c[2] / 2f;
            ty = MPainter.baselineFor(c[1] + c[3] / 2f, font.getSize());
            align = MPainter.Align.CENTER;
        } else {
            tx = c[0] + c[2] - 4f * s;
            ty = c[1] + c[3] - 4f * s;
            align = MPainter.Align.RIGHT;
        }
        if (shadowed) {
            MPainter.drawText(canvas, step.key(), tx, ty, font, ink, align);
        } else {
            float w = MPainter.measureWidth(font, step.key());
            float left = align == MPainter.Align.CENTER ? tx - w / 2f : tx - w;
            MPainter.drawString(canvas, step.key(), left, ty, font, ink);
        }
    }

    private void paintTimer(Canvas canvas, int index) {
        float[] track = timerRect(index);
        if (track[2] <= 0f || track[3] <= 0f) return;
        MPainter.fillRect(canvas, track[0], track[1], track[2], track[3], MStyle.GAUGE_TRACK);
        MPainter.fillRect(canvas, track[0], track[1], track[2] * timer, track[3],
                MColor.ramp(timer, timerLow, timerMid, timerHigh, 0f, 1f));
        MPainter.strokeRect(canvas, track[0] + 0.5f, track[1] + 0.5f, track[2] - 1f, track[3] - 1f,
                MStyle.GAUGE_OUTLINE, 1f);
    }

    /** A fixed fracture across a failed cell (deterministic). */
    private static void crack(Canvas canvas, float[] c, float s) {
        try (PathBuilder pb = new PathBuilder()) {
            pb.moveTo(c[0] + c[2] * 0.62f, c[1]);
            pb.lineTo(c[0] + c[2] * 0.42f, c[1] + c[3] * 0.34f);
            pb.lineTo(c[0] + c[2] * 0.60f, c[1] + c[3] * 0.52f);
            pb.lineTo(c[0] + c[2] * 0.36f, c[1] + c[3] * 0.78f);
            pb.lineTo(c[0] + c[2] * 0.44f, c[1] + c[3]);
            try (Path path = pb.build();
                 Paint paint = new Paint().setColor(MColor.lerp(MStyle.TEXT_ERROR, MStyle.TEXT_PRIMARY, 0.45f))
                         .setAntiAlias(true).setMode(PaintMode.STROKE).setStrokeWidth(Math.max(1f, 1.5f * s))
                         .setStrokeCap(PaintStrokeCap.ROUND).setStrokeJoin(PaintStrokeJoin.ROUND)) {
                canvas.drawPath(path, paint);
            }
        }
    }

    // ─────────────────────────────────────────────── Side areas

    private void paintSides(MasonryUI ui, Canvas canvas, float top, float s) {
        float side = sideWidth(s);
        if (side <= 0f) return;
        float cy = top + height / 2f;
        if (!caption.isEmpty()) {
            String[] lines = twoLines(caption);
            float cx = x + PAD * s + side / 2f;
            Font font = ui.fonts().fit(longer(lines), MStyle.FONT_META * s, side, 0.6f);
            if (font != null) {
                if (lines[1].isEmpty()) {
                    MPainter.drawText(canvas, lines[0], cx, MPainter.baselineFor(cy, font.getSize()), font,
                            MStyle.TEXT_ACCENT, MPainter.Align.CENTER);
                } else {
                    float off = font.getSize() * 0.62f;
                    MPainter.drawText(canvas, lines[0], cx, MPainter.baselineFor(cy - off, font.getSize()), font,
                            MStyle.TEXT_ACCENT, MPainter.Align.CENTER);
                    MPainter.drawText(canvas, lines[1], cx, MPainter.baselineFor(cy + off, font.getSize()), font,
                            MStyle.TEXT_ACCENT, MPainter.Align.CENTER);
                }
            }
        }
        if (!counter.isEmpty()) {
            String[] lines = twoLines(counter);
            float cx = x + width - PAD * s - side / 2f;
            Font big = ui.fonts().fit(lines[0], Math.min(MStyle.FONT_BUTTON * s, height * 0.5f), side, 0.5f);
            if (big == null) return;
            if (lines[1].isEmpty()) {
                MPainter.drawText(canvas, lines[0], cx, MPainter.baselineFor(cy, big.getSize()), big,
                        MStyle.TEXT_PRIMARY, MPainter.Align.CENTER);
                return;
            }
            Font small = ui.fonts().fit(lines[1], MStyle.FONT_META * s, side, 0.6f);
            if (small == null) return;
            MPainter.drawText(canvas, lines[0], cx, MPainter.baselineFor(cy - small.getSize() * 0.55f, big.getSize()),
                    big, MStyle.TEXT_PRIMARY, MPainter.Align.CENTER);
            MPainter.drawText(canvas, lines[1], cx, MPainter.baselineFor(cy + big.getSize() * 0.55f, small.getSize()),
                    small, MStyle.TEXT_SECONDARY, MPainter.Align.CENTER);
        }
    }

    /** Splits at the first newline, else at the first space; the second element is "" for one line. */
    private static String[] twoLines(String text) {
        int cut = text.indexOf('\n');
        if (cut < 0) cut = text.indexOf(' ');
        if (cut < 0) return new String[]{text, ""};
        return new String[]{text.substring(0, cut).trim(), text.substring(cut + 1).trim()};
    }

    private static String longer(String[] lines) {
        return lines[0].length() >= lines[1].length() ? lines[0] : lines[1];
    }

    private void paintFlashWord(MasonryUI ui, Canvas canvas, float s) {
        float t = MColor.clamp01(flashAge / flashSeconds);
        float pop = 1f + 0.4f * (1f - EasingFunctions.apply(MColor.clamp01(t / 0.18f), EasingType.EaseOutCubic));
        float fade = 1f - smoothstep((t - 0.7f) / 0.3f);
        Font font = ui.fonts().get(MStyle.FONT_TITLE * pop, s);
        if (font == null) return;
        float[] rect = flashRect();
        MPainter.drawTextOutlined(canvas, flashText, rect[0] + rect[2] / 2f,
                MPainter.baselineFor(rect[1] + rect[3] / 2f, font.getSize()), font, MColor.fade(flashColor, fade),
                MPainter.Align.CENTER, Math.max(2f, 3f * s));
    }
}
