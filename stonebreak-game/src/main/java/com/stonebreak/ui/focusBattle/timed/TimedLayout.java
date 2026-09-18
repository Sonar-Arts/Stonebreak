package com.stonebreak.ui.focusBattle.timed;

import com.stonebreak.battle.api.ActorPose;
import com.stonebreak.battle.api.BattleStageLayout;
import com.stonebreak.battle.api.BattleView;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.CombatantView;
import com.stonebreak.battle.api.PromptView;
import com.stonebreak.battle.api.TelegraphView;
import com.stonebreak.ui.focusBattle.WorldProjection;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/**
 * Pure geometry for the timed-input overlays: where the timing ring sits and how big each of its
 * radii is, how far the parry brackets have closed, the combo strip's cells, and the vignette alpha
 * curve. Painters draw exactly what these functions return, so the tests that pin the timing math
 * (ring meets target at the perfect-window midpoint, brackets shut at the window start) pin the
 * picture too.
 */
public final class TimedLayout {

    private TimedLayout() {}

    // ─────────────────────────────────────────────── E9 timing ring

    /** Height fraction of the Archon the ring centres on (its chest). */
    public static final float RING_BODY_FRACTION = 0.6f;
    /** Target-circle radius in blocks at the Archon's depth: hugs the torso and shoulders. */
    public static final float RING_RADIUS_BLOCKS = 0.8f;
    /** The shrinking ring opens at this multiple of the target radius. */
    public static final float RING_START_SCALE = 2.2f;
    /** It never collapses to a dot, so a very late ring is still visible while it times out. */
    public static final float RING_MIN_SCALE = 0.15f;
    private static final float RING_MIN_RADIUS = 40f;       // × HUD scale
    private static final float RING_FALLBACK_RADIUS = 64f;  // × HUD scale
    private static final float RING_MAX_RADIUS_FRACTION = 0.2f; // of the window's short side

    /**
     * Where a world-anchored overlay sits this frame.
     *
     * @param radius   the body-sized radius in pixels (ring target radius / bracket half-gap basis)
     * @param anchored false when the world anchor could not be projected and the fallback is used
     */
    public record Anchor(float x, float y, float radius, boolean anchored) {}

    /**
     * The timing ring's centre and target radius: the projected Archon chest, sized from the pixels
     * one block covers at that depth. Falls back to the screen centre when there is no camera matrix
     * or the chest is off-screen, so a ring prompt is never invisible.
     */
    public static Anchor ringAnchor(BattleStageLayout layout, BattleView view, Matrix4fc viewProjection,
                                    int w, int h, float scale) {
        float maxR = Math.min(w, h) * RING_MAX_RADIUS_FRACTION;
        float minR = Math.min(RING_MIN_RADIUS * scale, maxR);
        Vector3f world = bodyPoint(layout, view, CombatantId.ARCHON, RING_BODY_FRACTION);
        float[] screen = world == null ? null : WorldProjection.toScreen(viewProjection, world, w, h, 0f);
        float ppb = world == null ? 0f : WorldProjection.pixelsPerBlock(viewProjection, world, h);
        if (screen == null || ppb <= 0f) {
            return new Anchor(w / 2f, h / 2f, Math.max(minR, Math.min(maxR, RING_FALLBACK_RADIUS * scale)), false);
        }
        float r = Math.max(minR, Math.min(maxR, ppb * RING_RADIUS_BLOCKS));
        // Keep the whole target circle on screen even when the chest is near an edge.
        float x = clamp(screen[0], r, w - r), y = clamp(screen[1], r, h - r);
        return new Anchor(x, y, r, true);
    }

    /**
     * Radius multiplier of the shrinking ring {@code t} seconds after the ring opened. Linear in time
     * (a constant closing speed is what makes the meeting point predictable) and exactly 1 — ring on
     * target — at the middle of the perfect window.
     */
    public static float ringScaleAt(PromptView.Ring ring, float t) {
        float mid = (ring.perfectStart() + ring.perfectEnd()) / 2f;
        if (mid <= 0f) return 1f;
        float scale = 1f + (RING_START_SCALE - 1f) * (mid - t) / mid;
        return Math.max(RING_MIN_SCALE, scale);
    }

    /** Current radius of the shrinking ring in pixels. */
    public static float outerRadius(PromptView.Ring ring, float targetRadius) {
        return targetRadius * ringScaleAt(ring, ring.elapsed());
    }

    /**
     * {@code {innerRadius, outerRadius}} of the thickness zone for a press window: the shrinking ring
     * is inside this annulus exactly while {@code start <= elapsed <= end}.
     */
    public static float[] bandRadii(PromptView.Ring ring, float targetRadius, float start, float end) {
        float a = targetRadius * ringScaleAt(ring, start), b = targetRadius * ringScaleAt(ring, end);
        return new float[]{Math.min(a, b), Math.max(a, b)};
    }

    public static float[] goodBand(PromptView.Ring ring, float targetRadius) {
        return bandRadii(ring, targetRadius, ring.goodStart(), ring.goodEnd());
    }

    public static float[] perfectBand(PromptView.Ring ring, float targetRadius) {
        return bandRadii(ring, targetRadius, ring.perfectStart(), ring.perfectEnd());
    }

    // ─────────────────────────────────────────────── E12 parry

    public static final float PARRY_BODY_FRACTION = 0.55f;
    /** Bracket half-gap when shut, in blocks at the monk's depth (just outside the shoulders). */
    public static final float PARRY_CLOSED_BLOCKS = 0.5f;
    /** Open half-gap as a multiple of the shut one. */
    public static final float PARRY_OPEN_SCALE = 3.0f;
    private static final float PARRY_MIN_HALF_GAP = 26f;      // × HUD scale
    private static final float PARRY_FALLBACK_HALF_GAP = 40f; // × HUD scale

    /** The parry timeline in seconds from windup start; the prompt and the telegraph agree by construction. */
    public record ParryTimeline(float elapsed, float windowStart, float windowEnd, float impactTime) {
        public boolean inWindow() { return elapsed >= windowStart && elapsed <= windowEnd; }
    }

    /**
     * Timeline for the open parry prompt, synced to the telegraph when one is up (it is the clock the
     * enemy cast bar draws from, so the brackets and the bar's parry marker can never disagree).
     */
    public static ParryTimeline parryTimeline(PromptView.Parry prompt, TelegraphView telegraph) {
        if (telegraph != null && !telegraph.cancelled()) {
            return new ParryTimeline(telegraph.elapsed(), telegraph.parryWindowStart(), telegraph.parryWindowEnd(),
                    telegraph.impactTime());
        }
        return new ParryTimeline(prompt.elapsed(), prompt.windowStart(), prompt.windowEnd(), prompt.impactTime());
    }

    /** 0 = brackets fully open, 1 = shut. Linear in time, reaching 1 exactly at the window start. */
    public static float bracketClosure(ParryTimeline t) {
        if (t.windowStart() <= 0f) return 1f;
        return clamp(t.elapsed() / t.windowStart(), 0f, 1f);
    }

    /** Distance from the anchor to each bracket for a closure 0..1. */
    public static float bracketHalfGap(float closedHalfGap, float closure) {
        float c = clamp(closure, 0f, 1f);
        return closedHalfGap * (PARRY_OPEN_SCALE - (PARRY_OPEN_SCALE - 1f) * c);
    }

    /**
     * The monk's projected torso; {@link Anchor#radius} is the shut bracket half-gap. Falls back to a
     * fixed spot in the lower middle of the screen (where the guard shot frames him).
     */
    public static Anchor monkAnchor(BattleStageLayout layout, BattleView view, Matrix4fc viewProjection,
                                    int w, int h, float scale) {
        float maxHalf = Math.min(w, h) * 0.14f;
        float minHalf = Math.min(PARRY_MIN_HALF_GAP * scale, maxHalf);
        Vector3f world = bodyPoint(layout, view, CombatantId.MONK, PARRY_BODY_FRACTION);
        float[] screen = world == null ? null : WorldProjection.toScreen(viewProjection, world, w, h, 0f);
        float ppb = world == null ? 0f : WorldProjection.pixelsPerBlock(viewProjection, world, h);
        if (screen == null || ppb <= 0f) {
            return new Anchor(w / 2f, h * 0.58f,
                    Math.max(minHalf, Math.min(maxHalf, PARRY_FALLBACK_HALF_GAP * scale)), false);
        }
        float half = Math.max(minHalf, Math.min(maxHalf, ppb * PARRY_CLOSED_BLOCKS));
        return new Anchor(screen[0], screen[1], half, true);
    }

    // ─────────────────────────────────────────────── E10 combo strip

    private static final float COMBO_PAD = 8f;
    private static final float COMBO_CELL = 44f;
    private static final float COMBO_CELL_GAP = 12f;
    private static final float COMBO_TIMER_H = 6f;
    private static final float COMBO_TIMER_GAP = 5f;
    private static final float COMBO_SIDE_W = 92f;
    /** The prompt awaiting input is drawn this much larger than the rest. */
    public static final float COMBO_CURRENT_SCALE = 1.2f;

    /** Square cell {@code index} of {@code count}, at rest size; the row is centred in the strip. */
    public static float[] comboCellRect(float[] strip, int index, int count, float scale) {
        int n = Math.max(1, count);
        float gap = COMBO_CELL_GAP * scale;
        float avail = strip[2] - 2f * (COMBO_SIDE_W + COMBO_PAD) * scale;
        float byHeight = strip[3] - (2f * COMBO_PAD + COMBO_TIMER_H + COMBO_TIMER_GAP) * scale;
        float cell = Math.max(4f, Math.min(COMBO_CELL * scale, Math.min(byHeight, (avail - gap * (n - 1)) / n)));
        float rowW = cell * n + gap * (n - 1);
        float x = strip[0] + (strip[2] - rowW) / 2f + index * (cell + gap);
        float y = strip[1] + (strip[3] - (cell + (COMBO_TIMER_H + COMBO_TIMER_GAP) * scale)) / 2f;
        return new float[]{x, y, cell, cell};
    }

    /** {@code rect} scaled about its centre. */
    public static float[] scaled(float[] rect, float k) {
        float w = rect[2] * k, h = rect[3] * k;
        return new float[]{rect[0] + (rect[2] - w) / 2f, rect[1] + (rect[3] - h) / 2f, w, h};
    }

    /** The timer underline track beneath a (rest-size) cell. */
    public static float[] comboTimerTrack(float[] cell, float scale) {
        float grow = cell[2] * (COMBO_CURRENT_SCALE - 1f) / 2f;
        return new float[]{cell[0] - grow, cell[1] + cell[3] + grow + COMBO_TIMER_GAP * scale * 0.6f,
                cell[2] + 2f * grow, COMBO_TIMER_H * scale};
    }

    /** Share of the step's time that is left, 1 → 0. */
    public static float comboTimeLeft(float stepElapsed, float stepDuration) {
        if (stepDuration <= 0f) return 0f;
        return clamp(1f - stepElapsed / stepDuration, 0f, 1f);
    }

    /** The draining part of {@code track}: anchored left, shrinking toward it. */
    public static float[] comboTimerFill(float[] track, float timeLeft) {
        return new float[]{track[0], track[1], track[2] * clamp(timeLeft, 0f, 1f), track[3]};
    }

    /** Left caption area ("FOCUS COMBO"). */
    public static float[] comboLabelRect(float[] strip, float scale) {
        return new float[]{strip[0] + COMBO_PAD * scale, strip[1], COMBO_SIDE_W * scale, strip[3]};
    }

    /** Right hit-counter area. */
    public static float[] comboCounterRect(float[] strip, float scale) {
        float w = COMBO_SIDE_W * scale;
        return new float[]{strip[0] + strip[2] - COMBO_PAD * scale - w, strip[1], w, strip[3]};
    }

    /** How far below its resting place the strip is drawn for a slide amount 0 (off-screen) .. 1 (in). */
    public static float comboSlideOffset(float[] strip, int windowHeight, float slide, float scale) {
        return (1f - TimedTheme.easeOutCubic(slide)) * (windowHeight - strip[1] + 12f * scale);
    }

    // ─────────────────────────────────────────────── E14 vignettes

    /** No full-screen tint is ever stronger than this, anywhere. */
    public static final float VIGNETTE_MAX_ALPHA = 0.45f;
    /** Inside this normalised radius the scene is untouched: the centre 50% of the screen. */
    public static final float VIGNETTE_CLEAR_RADIUS = 0.5f;
    /** Normalised radius of the screen corners. */
    public static final float VIGNETTE_CORNER_RADIUS = (float) Math.sqrt(2.0);

    /**
     * Elliptical distance of a pixel from the screen centre: 0 at the centre, 1 at the middle of each
     * edge, √2 in the corners.
     */
    public static float normalisedRadius(float x, float y, int w, int h) {
        float dx = (x - w / 2f) / (w / 2f), dy = (y - h / 2f) / (h / 2f);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * Vignette opacity at normalised radius {@code r}: exactly 0 out to {@code innerRadius} (never
     * less than {@link #VIGNETTE_CLEAR_RADIUS}), then a smooth rise to {@code peakAlpha} at the
     * corners, capped at {@link #VIGNETTE_MAX_ALPHA}.
     */
    public static float vignetteAlpha(float r, float peakAlpha, float innerRadius) {
        float inner = Math.max(VIGNETTE_CLEAR_RADIUS, innerRadius);
        float peak = clamp(peakAlpha, 0f, VIGNETTE_MAX_ALPHA);
        if (r <= inner) return 0f;
        float outer = Math.max(inner + 0.05f, VIGNETTE_CORNER_RADIUS - 0.06f);
        return peak * TimedTheme.smoothstep((r - inner) / (outer - inner));
    }

    // ─────────────────────────────────────────────── Shared

    private static Vector3f bodyPoint(BattleStageLayout layout, BattleView view, CombatantId id, float fraction) {
        if (layout == null) return null;
        CombatantView c = view == null ? null : view.combatant(id);
        ActorPose pose = c == null || c.pose() == null ? ActorPose.IDLE : c.pose();
        return layout.bodyPoint(id, pose, fraction);
    }

    static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : Math.min(hi, v);
    }
}
