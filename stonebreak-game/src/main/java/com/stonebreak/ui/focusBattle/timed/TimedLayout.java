package com.stonebreak.ui.focusBattle.timed;

import com.stonebreak.battle.api.ActorPose;
import com.stonebreak.battle.api.BattleStageLayout;
import com.stonebreak.battle.api.BattleView;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.CombatantView;
import com.stonebreak.battle.api.PromptView;
import com.stonebreak.battle.api.TelegraphView;
import com.stonebreak.rendering.UI.masonryUI.MColor;
import com.stonebreak.rendering.UI.masonryUI.MWorldMarker;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/**
 * Pure gameplay geometry for the world-anchored timed-input overlays: where the timing ring sits and
 * how big each of its radii is, and how far the parry brackets have closed. Painters draw exactly
 * what these functions return, so the tests that pin the timing math (ring meets target at the
 * perfect-window midpoint, brackets shut at the window start) pin the picture too.
 *
 * <p>Projection and pinning go through the UI library's {@link MWorldMarker}; the combo strip's
 * cells and the vignette curve belong to the library widgets that draw them ({@code MPromptStrip},
 * {@code MScreenFx}).
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
        MWorldMarker.Anchor chest = MWorldMarker.project(viewProjection,
                bodyPoint(layout, view, CombatantId.ARCHON, RING_BODY_FRACTION), w, h);
        if (!usable(chest)) {
            MWorldMarker.Anchor pinned = MWorldMarker.orFallback(MWorldMarker.Anchor.NONE, w / 2f, h / 2f);
            return new Anchor(pinned.x(), pinned.y(), Math.max(minR, Math.min(maxR, RING_FALLBACK_RADIUS * scale)), false);
        }
        float r = MWorldMarker.radiusFor(chest, RING_RADIUS_BLOCKS, minR, maxR);
        // Keep the whole target circle on screen even when the chest is near an edge.
        MWorldMarker.Anchor kept = MWorldMarker.clampToBand(chest, r, r, w - r, h - r);
        return new Anchor(kept.x(), kept.y(), r, true);
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
        return MColor.clamp01(t.elapsed() / t.windowStart());
    }

    /** Distance from the anchor to each bracket for a closure 0..1. */
    public static float bracketHalfGap(float closedHalfGap, float closure) {
        float c = MColor.clamp01(closure);
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
        MWorldMarker.Anchor torso = MWorldMarker.project(viewProjection,
                bodyPoint(layout, view, CombatantId.MONK, PARRY_BODY_FRACTION), w, h);
        if (!usable(torso)) {
            MWorldMarker.Anchor pinned = MWorldMarker.orFallback(MWorldMarker.Anchor.NONE, w / 2f, h * 0.58f);
            return new Anchor(pinned.x(), pinned.y(),
                    Math.max(minHalf, Math.min(maxHalf, PARRY_FALLBACK_HALF_GAP * scale)), false);
        }
        return new Anchor(torso.x(), torso.y(), MWorldMarker.radiusFor(torso, PARRY_CLOSED_BLOCKS, minHalf, maxHalf), true);
    }

    // ─────────────────────────────────────────────── Shared

    private static Vector3f bodyPoint(BattleStageLayout layout, BattleView view, CombatantId id, float fraction) {
        if (layout == null) return null;
        CombatantView c = view == null ? null : view.combatant(id);
        ActorPose pose = c == null || c.pose() == null ? ActorPose.IDLE : c.pose();
        return layout.bodyPoint(id, pose, fraction);
    }

    /** On screen AND sized: a point whose depth cannot be measured is as good as unprojectable. */
    private static boolean usable(MWorldMarker.Anchor anchor) {
        return anchor.onScreen() && anchor.inFront();
    }
}
