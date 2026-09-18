package com.stonebreak.ui.focusBattle.timed;

import com.stonebreak.battle.api.BattleStageLayout;
import com.stonebreak.battle.api.BattleView;
import com.stonebreak.battle.api.PromptView;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.focusBattle.FocusBattleTheme;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import org.joml.Matrix4fc;

/**
 * E12 — parry prompt and feedback, anchored on the monk.
 *
 * <p>While a parry prompt is open, two brackets close on the monk at constant speed and <b>snap
 * shut exactly at the parry window start</b>, then glow gold for as long as the window is open: the
 * same read as the timing ring ("press when they meet"), and the same clock the enemy cast bar's
 * parry marker uses. Afterwards: a landed parry throws a white-blue flash ring across the whole
 * screen with a big "PARRY!"; a guarded hit shows a small shield and "BLOCK"; a wasted press shows a
 * grey "TOO EARLY".
 */
public final class ParryOverlay {

    private ParryOverlay() {}

    /** Peak opacity of the full-screen parry wash. */
    static final float FLASH_PEAK_ALPHA = 0.32f;

    public static void paint(MasonryUI ui, Canvas canvas, int w, int h, float scale, BattleView view,
                             TimedInputState state, BattleStageLayout layout, Matrix4fc viewProjection,
                             boolean gradeWords) {
        if (canvas == null || view == null || state == null) return;
        PromptView.Parry prompt = view.prompt() instanceof PromptView.Parry p ? p : null;
        TimedInputState.ParryFeedback feedback = state.parryFeedback;
        if (prompt == null && feedback == TimedInputState.ParryFeedback.NONE) return;

        TimedLayout.Anchor anchor = TimedLayout.monkAnchor(layout, view, viewProjection, w, h, scale);
        if (prompt != null) {
            paintBrackets(ui, canvas, anchor, TimedLayout.parryTimeline(prompt, view.telegraph()), state.time, scale);
        }
        switch (feedback) {
            case PARRY -> paintParry(ui, canvas, w, h, anchor, state.parryFeedbackAge, scale, gradeWords);
            case BLOCK -> paintBlock(ui, canvas, anchor, state.parryFeedbackAge, scale, gradeWords);
            case TOO_EARLY -> paintTooEarly(ui, canvas, anchor, state.parryFeedbackAge, scale, gradeWords);
            case NONE -> { }
        }
    }

    // ─────────────────────────────────────────────── Closing brackets

    /** Height of each bracket for a shut half-gap. */
    static float bracketHeight(float closedHalfGap) {
        return closedHalfGap * 2.0f;
    }

    private static void paintBrackets(MasonryUI ui, Canvas canvas, TimedLayout.Anchor a,
                                      TimedLayout.ParryTimeline t, float time, float s) {
        float closure = TimedLayout.bracketClosure(t);
        boolean live = t.inWindow();
        float half = TimedLayout.bracketHalfGap(a.radius(), closure);
        float height = bracketHeight(a.radius());
        float top = a.y() - height / 2f, bottom = a.y() + height / 2f;
        float arm = Math.max(8f * s, a.radius() * 0.42f);
        float width = Math.max(3f, (live ? 5.5f : 4f) * s);
        float pulse = live ? 0.5f + 0.5f * (float) Math.sin(time * 30.0) : 0f;

        // Ghost of the shut position: the mark the brackets are travelling to.
        if (!live) {
            float ghostW = Math.max(1.5f, 2f * s);
            bracket(canvas, a.x() - a.radius(), top, bottom, arm, 1f, ghostW, TimedTheme.PARRY_BRACKET, 0.35f, s);
            bracket(canvas, a.x() + a.radius(), top, bottom, arm, -1f, ghostW, TimedTheme.PARRY_BRACKET, 0.35f, s);
        } else {
            // Glow through the window: a gold wash between the shut brackets.
            FocusBattleTheme.roundedFill(canvas, a.x() - half, top, 2f * half, height, 6f * s,
                    FocusBattleTheme.fade(TimedTheme.PARRY_GLOW, 0.45f + 0.35f * pulse));
        }
        int color = live ? FocusBattleTheme.lerpColor(TimedTheme.PARRY_BRACKET_LIVE, 0xFFFFFFFF, pulse * 0.6f)
                : TimedTheme.PARRY_BRACKET;
        bracket(canvas, a.x() - half, top, bottom, arm, 1f, width, color, 1f, s);
        bracket(canvas, a.x() + half, top, bottom, arm, -1f, width, color, 1f, s);

        if (ui == null) return;
        Font font = FocusBattleTheme.font(ui, TimedTheme.FS_PROMPT * (live ? 1.2f : 1f), s);
        if (font == null) return;
        TimedTheme.outlinedTextCentered(canvas, live ? "PARRY!" : "PARRY", a.x(), top - 10f * s, font,
                live ? TimedTheme.PARRY_BRACKET_LIVE : TimedTheme.PARRY_BRACKET, 1f, s);
        Font hint = FocusBattleTheme.font(ui, TimedTheme.FS_HINT, s);
        TimedTheme.keyHint(canvas, hint, TimingRingOverlay.KEY_LABEL, TimingRingOverlay.KEY_ACTION, a.x(),
                bottom + 16f * s, 1f, s);
    }

    /** One square bracket whose spine is at {@code x}; {@code dir} +1 opens right ("["), −1 left. */
    private static void bracket(Canvas canvas, float x, float top, float bottom, float arm, float dir,
                                float width, int color, float alpha, float s) {
        TimedTheme.haloPolyline(canvas, new float[]{x + dir * arm, top, x, top, x, bottom, x + dir * arm, bottom},
                width, color, alpha, s);
    }

    // ─────────────────────────────────────────────── Feedback

    private static void paintParry(MasonryUI ui, Canvas canvas, int w, int h, TimedLayout.Anchor a, float age,
                                   float s, boolean words) {
        float t = FocusBattleTheme.clamp01(age / TimedInputState.PARRY_FLASH_SECONDS);
        if (t < 1f) {
            // Whole-screen wash, then the ring that carries the eye outward from the monk.
            FocusBattleTheme.fillRect(canvas, 0f, 0f, w, h,
                    FocusBattleTheme.fade(TimedTheme.PARRY_FLASH, FLASH_PEAK_ALPHA * TimedTheme.flashEnvelope(t)));
            float reach = (float) Math.hypot(Math.max(a.x(), w - a.x()), Math.max(a.y(), h - a.y()));
            float radius = a.radius() + (reach - a.radius()) * TimedTheme.easeOutCubic(t);
            float fade = 1f - TimedTheme.smoothstep(t);
            TimedTheme.strokeCircle(canvas, a.x(), a.y(), radius, Math.max(6f, 26f * s * (1f - 0.7f * t)),
                    FocusBattleTheme.fade(TimedTheme.PARRY_FLASH, 0.55f * fade));
            TimedTheme.strokeCircle(canvas, a.x(), a.y(), radius, Math.max(2f, 6f * s * (1f - 0.5f * t)),
                    FocusBattleTheme.fade(TimedTheme.PARRY_FLASH_RING, 0.95f * fade));
        }
        if (!words || ui == null) return;
        float tt = FocusBattleTheme.clamp01(age / TimedInputState.PARRY_TEXT_SECONDS);
        // Pops in oversized and settles; holds, then fades over the last third.
        float pop = 1f + 0.35f * (1f - TimedTheme.easeOutCubic(FocusBattleTheme.clamp01(tt / 0.25f)));
        float fade = 1f - TimedTheme.smoothstep((tt - 0.65f) / 0.35f);
        Font font = FocusBattleTheme.font(ui, TimedTheme.FS_BIG * pop, s);
        if (font == null) return;
        float baseline = wordBaseline(a, font, s);
        float cx = TimedLayout.clamp(a.x(), w * 0.15f, w * 0.85f);
        TimedTheme.outlinedTextCentered(canvas, "PARRY!", cx, baseline, font, 0xFFFFFFFF, fade, s);
    }

    private static void paintBlock(MasonryUI ui, Canvas canvas, TimedLayout.Anchor a, float age, float s,
                                   boolean words) {
        float t = FocusBattleTheme.clamp01(age / TimedInputState.BLOCK_SECONDS);
        float fade = 1f - TimedTheme.smoothstep((t - 0.4f) / 0.6f);
        float size = a.radius() * (1.1f + 0.25f * TimedTheme.easeOutCubic(t));
        shield(canvas, a.x(), a.y(), size, fade, s);
        if (!words || ui == null) return;
        Font font = FocusBattleTheme.font(ui, TimedTheme.FS_PROMPT * 1.15f, s);
        if (font == null) return;
        TimedTheme.outlinedTextCentered(canvas, "BLOCK", a.x(), wordBaseline(a, font, s), font,
                TimedTheme.BLOCK_SHIELD, fade, s);
    }

    private static void paintTooEarly(MasonryUI ui, Canvas canvas, TimedLayout.Anchor a, float age, float s,
                                      boolean words) {
        if (!words || ui == null) return;
        float t = FocusBattleTheme.clamp01(age / TimedInputState.TOO_EARLY_SECONDS);
        float fade = 1f - TimedTheme.smoothstep((t - 0.5f) / 0.5f);
        Font font = FocusBattleTheme.font(ui, TimedTheme.FS_PROMPT, s);
        if (font == null) return;
        // Sags a few pixels as it fades: a deflated word, not a celebration.
        TimedTheme.outlinedTextCentered(canvas, "TOO EARLY", a.x(), wordBaseline(a, font, s) + 6f * s * t, font,
                TimedTheme.NEUTRAL_GREY, fade, s);
    }

    /** Feedback words sit above the bracket zone (and therefore above the monk's head). */
    private static float wordBaseline(TimedLayout.Anchor a, Font font, float s) {
        return Math.max(font.getSize() + 4f * s, a.y() - bracketHeight(a.radius()) / 2f - 12f * s);
    }

    /** A heater shield centred on {@code (cx, cy)}, {@code size} wide: translucent face, bright rim. */
    private static void shield(Canvas canvas, float cx, float cy, float size, float alpha, float s) {
        float hw = size / 2f, hh = size * 0.6f;
        float[] xy = {
                cx - hw, cy - hh,
                cx + hw, cy - hh,
                cx + hw, cy + hh * 0.1f,
                cx + hw * 0.55f, cy + hh * 0.7f,
                cx, cy + hh,
                cx - hw * 0.55f, cy + hh * 0.7f,
                cx - hw, cy + hh * 0.1f};
        TimedTheme.fillPolygon(canvas, xy, FocusBattleTheme.fade(TimedTheme.BLOCK_SHIELD, 0.30f * alpha));
        TimedTheme.strokePolygon(canvas, xy, Math.max(3f, 5f * s), FocusBattleTheme.fade(TimedTheme.UNDER, alpha));
        TimedTheme.strokePolygon(canvas, xy, Math.max(1.5f, 2.5f * s), FocusBattleTheme.fade(0xFFFFFFFF, alpha));
    }
}
