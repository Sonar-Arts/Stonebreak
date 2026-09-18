package com.stonebreak.ui.focusBattle.timed;

import com.stonebreak.battle.api.BattleStageLayout;
import com.stonebreak.battle.api.BattleView;
import com.stonebreak.battle.api.PromptView;
import com.stonebreak.battle.api.TimedGrade;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.focusBattle.FocusBattleTheme;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import org.joml.Matrix4fc;

/**
 * E9 — the Flurry timing ring. A fixed <b>target circle</b> hugs the Archon's torso; an outer ring
 * shrinks onto it at constant speed and meets it exactly at the middle of the perfect window
 * ("press when the rings meet"). The two press windows are drawn as thickness zones around the
 * target: a pale good band and, inside it, a narrower gold perfect band — the shrinking ring is
 * inside a band exactly while that grade is on offer.
 *
 * <p>Resolution feedback (PERFECT burst / GOOD pop / MISS shake) lasts
 * {@link TimedInputState#RING_FEEDBACK_SECONDS} and is drawn as thin expanding strokes plus a word
 * <em>above</em> the ring, so it never hides the next hit's ring, which opens a moment later.
 */
public final class TimingRingOverlay {

    private TimingRingOverlay() {}

    static final String KEY_LABEL = "SPACE";
    static final String KEY_ACTION = "Confirm";
    private static final float FADE_IN_SECONDS = 0.08f;
    /** The grade word sits this many target radii above the centre (outside the good band). */
    private static final float WORD_CLEARANCE = 1.4f;

    public static void paint(MasonryUI ui, Canvas canvas, int w, int h, float scale, BattleView view,
                             TimedInputState state, BattleStageLayout layout, Matrix4fc viewProjection,
                             boolean gradeWords) {
        if (canvas == null || view == null || state == null) return;
        PromptView.Ring ring = view.prompt() instanceof PromptView.Ring r ? r : null;
        TimedGrade grade = state.ringGrade;
        if (ring == null && grade == null) return;

        TimedLayout.Anchor anchor = TimedLayout.ringAnchor(layout, view, viewProjection, w, h, scale);
        if (ring != null) paintRing(ui, canvas, anchor, ring, scale);
        if (grade != null) paintFeedback(ui, canvas, anchor, grade, state.ringFeedbackAge, scale, gradeWords);
    }

    // ─────────────────────────────────────────────── The ring itself

    private static void paintRing(MasonryUI ui, Canvas canvas, TimedLayout.Anchor a, PromptView.Ring ring,
                                  float s) {
        float alpha = FocusBattleTheme.clamp01(ring.elapsed() / FADE_IN_SECONDS);
        float cx = a.x(), cy = a.y(), rt = a.radius();
        float[] good = TimedLayout.goodBand(ring, rt);
        float[] perfect = TimedLayout.perfectBand(ring, rt);
        float outer = TimedLayout.outerRadius(ring, rt);
        boolean inPerfect = ring.elapsed() >= ring.perfectStart() && ring.elapsed() <= ring.perfectEnd();
        boolean inGood = ring.elapsed() >= ring.goodStart() && ring.elapsed() <= ring.goodEnd();
        boolean late = ring.elapsed() > ring.goodEnd();

        // Thickness zones. A faint dark wash under the whole good band keeps the pale band visible
        // on snow; the band edges get hairlines so the zones read as drawn, not as a smudge.
        TimedTheme.annulus(canvas, cx, cy, good[0], good[1], FocusBattleTheme.fade(0x40060A10, alpha));
        TimedTheme.annulus(canvas, cx, cy, good[0], good[1], FocusBattleTheme.fade(TimedTheme.RING_GOOD_BAND, alpha));
        int perfectFill = inPerfect ? FocusBattleTheme.lerpColor(TimedTheme.RING_PERFECT_BAND, 0xB3FFE08A, 0.7f)
                : TimedTheme.RING_PERFECT_BAND;
        TimedTheme.annulus(canvas, cx, cy, perfect[0], perfect[1], FocusBattleTheme.fade(perfectFill, alpha));
        float hair = Math.max(1f, 1.2f * s);
        TimedTheme.strokeCircle(canvas, cx, cy, good[0], hair, FocusBattleTheme.fade(0xB3060A10, alpha));
        TimedTheme.strokeCircle(canvas, cx, cy, good[1], hair, FocusBattleTheme.fade(0xB3060A10, alpha));
        TimedTheme.strokeCircle(canvas, cx, cy, perfect[0], hair, FocusBattleTheme.fade(0xD9FFC83D, alpha));
        TimedTheme.strokeCircle(canvas, cx, cy, perfect[1], hair, FocusBattleTheme.fade(0xD9FFC83D, alpha));

        // Target circle + four notches so it reads as a reticle even before the outer ring arrives.
        float targetW = Math.max(2f, 3f * s);
        int targetColor = inPerfect ? TimedTheme.PERFECT_HOT : TimedTheme.RING_TARGET;
        TimedTheme.haloCircle(canvas, cx, cy, rt, targetW, targetColor, alpha, s);
        float notch = Math.max(5f, 0.16f * rt);
        for (int i = 0; i < 4; i++) {
            float dx = i == 0 ? 1f : i == 2 ? -1f : 0f, dy = i == 1 ? 1f : i == 3 ? -1f : 0f;
            TimedTheme.haloLine(canvas, cx + dx * (perfect[1] + notch * 0.3f), cy + dy * (perfect[1] + notch * 0.3f),
                    cx + dx * (perfect[1] + notch * 1.3f), cy + dy * (perfect[1] + notch * 1.3f),
                    targetW, targetColor, alpha, s);
        }

        // The shrinking ring, coloured by what a press would earn right now.
        int outerColor = inPerfect ? TimedTheme.PERFECT : inGood ? TimedTheme.RING_OUTER_GOOD
                : late ? TimedTheme.RING_OUTER_LATE : TimedTheme.RING_OUTER;
        float outerW = Math.max(3f, (inPerfect ? 5.5f : 4.5f) * s);
        TimedTheme.haloCircle(canvas, cx, cy, outer, outerW, outerColor, alpha, s);

        paintPipsAndHint(ui, canvas, a, ring, good[1], alpha, s);
    }

    /** Bottom edge of everything under the ring; public so tests can bound the element. */
    public static float[] pipRowRect(TimedLayout.Anchor a, PromptView.Ring ring, float s) {
        float pip = 16f * s, gap = 7f * s;
        int n = Math.max(1, ring.hitCount());
        float rowW = n * pip + (n - 1) * gap;
        float top = a.y() + TimedLayout.goodBand(ring, a.radius())[1] + 12f * s;
        return new float[]{a.x() - rowW / 2f, top, rowW, pip};
    }

    private static void paintPipsAndHint(MasonryUI ui, Canvas canvas, TimedLayout.Anchor a, PromptView.Ring ring,
                                         float goodOuter, float alpha, float s) {
        float[] row = pipRowRect(a, ring, s);
        float pip = row[3], gap = 7f * s;
        int n = Math.max(1, ring.hitCount());
        for (int i = 0; i < n; i++) {
            float x = row[0] + i * (pip + gap);
            boolean done = i < ring.hitIndex(), current = i == ring.hitIndex();
            float size = current ? pip * 1.25f : pip;
            float off = (size - pip) / 2f;
            FocusBattleTheme.pip(canvas, x - off, row[1] - off, size, done || current,
                    current ? TimedTheme.GOOD : TimedTheme.PERFECT);
        }
        if (ui == null) return;
        Font font = FocusBattleTheme.font(ui, TimedTheme.FS_HINT, s);
        TimedTheme.keyHint(canvas, font, KEY_LABEL, KEY_ACTION, a.x(), row[1] + pip + 16f * s, alpha, s);
    }

    // ─────────────────────────────────────────────── Feedback

    static String word(TimedGrade grade) {
        return switch (grade) {
            case PERFECT -> "PERFECT";
            case GOOD -> "GOOD";
            case MISS -> "MISS";
        };
    }

    private static void paintFeedback(MasonryUI ui, Canvas canvas, TimedLayout.Anchor a,
                                      TimedGrade grade, float age, float s, boolean gradeWords) {
        float t = FocusBattleTheme.clamp01(age / TimedInputState.RING_FEEDBACK_SECONDS);
        float fade = 1f - TimedTheme.smoothstep((t - 0.55f) / 0.45f);
        float cx = a.x(), cy = a.y(), rt = a.radius();
        float grow = TimedTheme.easeOutCubic(t);
        switch (grade) {
            case PERFECT -> {
                // Gold burst: two expanding rings and eight rays thrown outward from the target.
                TimedTheme.haloCircle(canvas, cx, cy, rt * (1f + 0.55f * grow), Math.max(2f, 5f * s * (1f - t)),
                        TimedTheme.PERFECT, fade, s);
                TimedTheme.strokeCircle(canvas, cx, cy, rt * (1f + 0.9f * grow), Math.max(1.5f, 2.5f * s),
                        FocusBattleTheme.fade(TimedTheme.PERFECT_HOT, fade * 0.8f));
                for (int i = 0; i < 8; i++) {
                    double ang = Math.PI / 8.0 + i * Math.PI / 4.0;
                    float c = (float) Math.cos(ang), sn = (float) Math.sin(ang);
                    float r0 = rt * (1.05f + 0.5f * grow), r1 = r0 + rt * 0.28f * (1f - 0.5f * t);
                    TimedTheme.haloLine(canvas, cx + c * r0, cy + sn * r0, cx + c * r1, cy + sn * r1,
                            Math.max(2f, 3f * s), TimedTheme.PERFECT_HOT, fade, s);
                }
            }
            case GOOD -> TimedTheme.haloCircle(canvas, cx, cy, rt * (1f + 0.3f * grow),
                    Math.max(2f, 4f * s * (1f - 0.6f * t)), TimedTheme.GOOD, fade, s);
            case MISS -> {
                // The target circle judders sideways in red and decays; nothing expands.
                float shake = (float) Math.sin(age * 70.0) * 7f * s * (1f - t);
                TimedTheme.haloCircle(canvas, cx + shake, cy, rt, Math.max(2f, 3.5f * s), TimedTheme.MISS, fade, s);
                float k = rt * 0.22f;
                TimedTheme.haloLine(canvas, cx + shake - k, cy - k, cx + shake + k, cy + k, Math.max(2f, 3.5f * s),
                        TimedTheme.MISS, fade, s);
                TimedTheme.haloLine(canvas, cx + shake - k, cy + k, cx + shake + k, cy - k, Math.max(2f, 3.5f * s),
                        TimedTheme.MISS, fade, s);
            }
        }
        if (!gradeWords || ui == null) return;
        Font font = FocusBattleTheme.font(ui, TimedTheme.FS_GRADE, s);
        if (font == null) return;
        // Just above the band zone: close enough to read with the ring, and only the first instants
        // of the next hit's (still huge) ring sweep past it while it is already fading.
        float top = cy - rt * WORD_CLEARANCE - 10f * s;
        float baseline = Math.max(font.getSize() + 6f * s, top - 8f * s * grow);
        float shakeX = grade == TimedGrade.MISS ? (float) Math.sin(age * 70.0) * 5f * s * (1f - t) : 0f;
        int color = switch (grade) {
            case PERFECT -> TimedTheme.PERFECT;
            case GOOD -> TimedTheme.GOOD;
            case MISS -> TimedTheme.MISS;
        };
        TimedTheme.outlinedTextCentered(canvas, word(grade), cx + shakeX, baseline, font, color, fade, s);
    }
}
