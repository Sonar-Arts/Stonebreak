package com.stonebreak.ui.focusBattle.timed;

import com.stonebreak.battle.api.BattleStageLayout;
import com.stonebreak.battle.api.BattleView;
import com.stonebreak.battle.api.PromptView;
import com.stonebreak.battle.api.TimedGrade;
import com.stonebreak.rendering.UI.masonryUI.MColor;
import com.stonebreak.rendering.UI.masonryUI.MKeyHint;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MPipRow;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.focusBattle.BattlePalette;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import org.joml.Matrix4fc;

/**
 * E9, the Flurry timing ring. A fixed <b>target circle</b> hugs the Archon's torso; an outer ring
 * shrinks onto it at constant speed and meets it exactly at the middle of the perfect window
 * ("press when the rings meet"). The two press windows are drawn as thickness zones around the
 * target: a pale good band and, inside it, a narrower gold perfect band; the shrinking ring is
 * inside a band exactly while that grade is on offer.
 *
 * <p>The geometry is gameplay and comes from {@link TimedLayout}. The drawing goes through the UI
 * library: circles from {@link MPainter} (each bright stroke on a dark under-stroke), the hit counter
 * is an {@link MPipRow}, the confirm prompt an {@link MKeyHint}, the grade word
 * {@link MPainter#drawTextOutlined}, colours from {@link MStyle} and {@link BattlePalette#grade}.
 *
 * <p>Resolution feedback (PERFECT burst / GOOD pop / MISS shake) lasts
 * {@link TimedInputState#RING_FEEDBACK_SECONDS} and is drawn as thin expanding strokes plus a word
 * <em>above</em> the ring, so it never hides the next hit's ring, which opens a moment later.
 */
public final class TimingRingOverlay {

    static final String KEY_LABEL = "SPACE";
    static final String KEY_ACTION = "Confirm";
    private static final float FADE_IN_SECONDS = 0.08f;
    /** The grade word sits this many target radii above the centre (outside the good band). */
    private static final float WORD_CLEARANCE = 1.4f;
    private static final float WORD_SIZE = MStyle.FONT_BUTTON * 1.3f;
    private static final float PIP = 16f, PIP_GAP = 7f;

    // Ring states: what a press would earn right now.
    static final int RING_WAITING = MStyle.TEXT_PRIMARY;
    static final int RING_GOOD = MStyle.VITAL_OK;
    static final int RING_PERFECT = BattlePalette.grade(TimedGrade.PERFECT);
    static final int RING_LATE = BattlePalette.grade(TimedGrade.MISS);
    static final int TARGET = MStyle.TEXT_PRIMARY;
    /** The target circle (and the burst's rays) while it is the moment: gold lit toward white. */
    static final int TARGET_HOT = MColor.lerp(BattlePalette.grade(TimedGrade.PERFECT), MStyle.TEXT_PRIMARY, 0.55f);

    private final MPipRow pips = new MPipRow().shape(MPipRow.Shape.DIAMOND).align(MPipRow.Align.CENTER)
            .pipSize(PIP).gap(PIP_GAP).color(BattlePalette.grade(TimedGrade.PERFECT)).autoAnimate(true);
    private final MKeyHint hint = new MKeyHint(KEY_LABEL, KEY_ACTION).outlined(true)
            .fontSize(MStyle.FONT_CAPTION).align(MPainter.Align.CENTER);

    /** The hit counter under the ring: one lit pip per hit of the flurry reached so far. */
    public MPipRow pips() {
        return pips;
    }

    public void reset() {
        pips.count(0).filled(0).reset();
    }

    /** Once per frame: the pips' pop advances, and a new hit of the flurry lights (and pops) its pip. */
    public void update(BattleView view, float dt) {
        pips.update(dt);
        if (view != null && view.prompt() instanceof PromptView.Ring ring) {
            int lit = ring.hitIndex() + 1;
            // A new flurry starts over: that is not pips being spent, so nothing should flash.
            if (lit < pips.filled()) pips.reset();
            pips.count(Math.max(1, ring.hitCount())).filled(lit);
        }
    }

    public void paint(MasonryUI ui, Canvas canvas, int w, int h, float scale, BattleView view,
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

    private void paintRing(MasonryUI ui, Canvas canvas, TimedLayout.Anchor a, PromptView.Ring ring, float s) {
        float alpha = MColor.clamp01(ring.elapsed() / FADE_IN_SECONDS);
        float cx = a.x(), cy = a.y(), rt = a.radius();
        float[] good = TimedLayout.goodBand(ring, rt);
        float[] perfect = TimedLayout.perfectBand(ring, rt);
        float outer = TimedLayout.outerRadius(ring, rt);
        boolean inPerfect = ring.elapsed() >= ring.perfectStart() && ring.elapsed() <= ring.perfectEnd();
        boolean inGood = ring.elapsed() >= ring.goodStart() && ring.elapsed() <= ring.goodEnd();
        boolean late = ring.elapsed() > ring.goodEnd();

        // Thickness zones. A faint dark wash under the whole good band keeps the pale band visible
        // on snow; the band edges get hairlines so the zones read as drawn, not as a smudge.
        TimedStrokes.annulus(canvas, cx, cy, good[0], good[1], MColor.withAlpha(MStyle.OUTLINE_DARK, 0.25f * alpha));
        TimedStrokes.annulus(canvas, cx, cy, good[0], good[1], MColor.withAlpha(MStyle.TEXT_PRIMARY, 0.24f * alpha));
        TimedStrokes.annulus(canvas, cx, cy, perfect[0], perfect[1],
                MColor.withAlpha(inPerfect ? TARGET_HOT : RING_PERFECT, (inPerfect ? 0.62f : 0.44f) * alpha));
        float hair = Math.max(1f, 1.2f * s);
        int bandEdge = MColor.withAlpha(MStyle.OUTLINE_DARK, 0.7f * alpha);
        int perfectEdge = MColor.withAlpha(RING_PERFECT, 0.85f * alpha);
        MPainter.strokeCircle(canvas, cx, cy, good[0], bandEdge, hair);
        MPainter.strokeCircle(canvas, cx, cy, good[1], bandEdge, hair);
        MPainter.strokeCircle(canvas, cx, cy, perfect[0], perfectEdge, hair);
        MPainter.strokeCircle(canvas, cx, cy, perfect[1], perfectEdge, hair);

        // Target circle + four notches so it reads as a reticle even before the outer ring arrives.
        float targetW = Math.max(2f, 3f * s);
        int targetColor = inPerfect ? TARGET_HOT : TARGET;
        TimedStrokes.haloCircle(canvas, cx, cy, rt, targetW, targetColor, alpha, s);
        float notch = Math.max(5f, 0.16f * rt);
        for (int i = 0; i < 4; i++) {
            float dx = i == 0 ? 1f : i == 2 ? -1f : 0f, dy = i == 1 ? 1f : i == 3 ? -1f : 0f;
            TimedStrokes.haloLine(canvas, cx + dx * (perfect[1] + notch * 0.3f), cy + dy * (perfect[1] + notch * 0.3f),
                    cx + dx * (perfect[1] + notch * 1.3f), cy + dy * (perfect[1] + notch * 1.3f),
                    targetW, targetColor, alpha, s);
        }

        // The shrinking ring, coloured by what a press would earn right now.
        int outerColor = inPerfect ? RING_PERFECT : inGood ? RING_GOOD : late ? RING_LATE : RING_WAITING;
        float outerW = Math.max(3f, (inPerfect ? 5.5f : 4.5f) * s);
        TimedStrokes.haloCircle(canvas, cx, cy, outer, outerW, outerColor, alpha, s);

        paintPipsAndHint(ui, a, ring, alpha, s);
    }

    /** Where the hit pips sit under the ring; public so tests can bound the element. */
    public static float[] pipRowRect(TimedLayout.Anchor a, PromptView.Ring ring, float s) {
        float pip = PIP * s, gap = PIP_GAP * s;
        int n = Math.max(1, ring.hitCount());
        float rowW = n * pip + (n - 1) * gap;
        float top = a.y() + TimedLayout.goodBand(ring, a.radius())[1] + 12f * s;
        // A little air around the row so a popping pip is not squeezed by its own bounds.
        float air = pip * MPipRow.POP_GROWTH;
        return new float[]{a.x() - rowW / 2f - air, top - air / 2f, rowW + 2f * air, pip + air};
    }

    private void paintPipsAndHint(MasonryUI ui, TimedLayout.Anchor a, PromptView.Ring ring, float alpha, float s) {
        if (ui == null) return;
        float[] row = pipRowRect(a, ring, s);
        pips.scale(s).bounds(row[0], row[1], row[2], row[3]).render(ui);
        hint.scale(s).alpha(alpha);
        float hintH = hint.preferredHeight();
        float hintW = Math.max(hint.preferredWidth(ui), 1f);
        hint.bounds(a.x() - hintW / 2f, row[1] + row[3] + 8f * s, hintW, hintH).render(ui);
    }

    // ─────────────────────────────────────────────── Feedback

    static String word(TimedGrade grade) {
        return switch (grade) {
            case PERFECT -> "PERFECT";
            case GOOD -> "GOOD";
            case MISS -> "MISS";
        };
    }

    private static void paintFeedback(MasonryUI ui, Canvas canvas, TimedLayout.Anchor a, TimedGrade grade,
                                      float age, float s, boolean gradeWords) {
        float t = MColor.clamp01(age / TimedInputState.RING_FEEDBACK_SECONDS);
        float fade = TimedMotion.holdThenFade(t, 0.55f);
        float cx = a.x(), cy = a.y(), rt = a.radius();
        float grow = TimedMotion.settle(t);
        int color = BattlePalette.grade(grade);
        switch (grade) {
            case PERFECT -> {
                // Gold burst: two expanding rings and eight rays thrown outward from the target.
                TimedStrokes.haloCircle(canvas, cx, cy, rt * (1f + 0.55f * grow), Math.max(2f, 5f * s * (1f - t)),
                        color, fade, s);
                TimedStrokes.haloCircle(canvas, cx, cy, rt * (1f + 0.9f * grow), Math.max(1.5f, 2.5f * s),
                        TARGET_HOT, fade * 0.8f, s * 0.5f);
                for (int i = 0; i < 8; i++) {
                    double ang = Math.PI / 8.0 + i * Math.PI / 4.0;
                    float c = (float) Math.cos(ang), sn = (float) Math.sin(ang);
                    float r0 = rt * (1.05f + 0.5f * grow), r1 = r0 + rt * 0.28f * (1f - 0.5f * t);
                    TimedStrokes.haloLine(canvas, cx + c * r0, cy + sn * r0, cx + c * r1, cy + sn * r1,
                            Math.max(2f, 3f * s), TARGET_HOT, fade, s);
                }
            }
            case GOOD -> TimedStrokes.haloCircle(canvas, cx, cy, rt * (1f + 0.3f * grow),
                    Math.max(2f, 4f * s * (1f - 0.6f * t)), color, fade, s);
            case MISS -> {
                // The target circle judders sideways in red and decays; nothing expands.
                float shake = (float) Math.sin(age * 70.0) * 7f * s * (1f - t);
                TimedStrokes.haloCircle(canvas, cx + shake, cy, rt, Math.max(2f, 3.5f * s), color, fade, s);
                float k = rt * 0.22f;
                TimedStrokes.haloLine(canvas, cx + shake - k, cy - k, cx + shake + k, cy + k, Math.max(2f, 3.5f * s),
                        color, fade, s);
                TimedStrokes.haloLine(canvas, cx + shake - k, cy + k, cx + shake + k, cy - k, Math.max(2f, 3.5f * s),
                        color, fade, s);
            }
        }
        if (!gradeWords || ui == null) return;
        Font font = ui.fonts().get(WORD_SIZE, s);
        if (font == null) return;
        // Just above the band zone: close enough to read with the ring, and only the first instants
        // of the next hit's (still huge) ring sweep past it while it is already fading.
        float top = cy - rt * WORD_CLEARANCE - 10f * s;
        float baseline = Math.max(font.getSize() + 6f * s, top - 8f * s * grow);
        float shakeX = grade == TimedGrade.MISS ? (float) Math.sin(age * 70.0) * 5f * s * (1f - t) : 0f;
        MPainter.drawTextOutlined(canvas, word(grade), cx + shakeX, baseline, font, MColor.fade(color, fade),
                MPainter.Align.CENTER, Math.max(2f, 2.5f * s));
    }
}
