package com.stonebreak.ui.focusBattle.timed;

import com.stonebreak.battle.api.BattleStageLayout;
import com.stonebreak.battle.api.BattleView;
import com.stonebreak.battle.api.PromptView;
import com.stonebreak.rendering.UI.masonryUI.MColor;
import com.stonebreak.rendering.UI.masonryUI.MKeyHint;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MScreenFx;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.focusBattle.BattlePalette;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import org.joml.Matrix4fc;

/**
 * E12, block/parry timing prompt and feedback, anchored on the monk.
 *
 * <p>While a parry prompt is open, two brackets close on the monk at constant speed and <b>snap
 * shut exactly at the parry window start</b>, then glow gold for as long as the window is open: the
 * same read as the timing ring ("press when they meet"), the same clock and the same
 * {@link BattlePalette#REACT_WINDOW} gold as the enemy cast bar's parry marker. Afterwards: a landed
 * parry throws a flash and an expanding ring across the whole screen ({@link MScreenFx}) with a big
 * "PARRY!"; a blocked hit shows a small shield and "BLOCK"; a wasted press shows a grey "TOO EARLY".
 *
 * <p>Bracket travel is gameplay and comes from {@link TimedLayout}; words, the key hint, the flash
 * and the ring are the UI library's.
 */
public final class ParryOverlay {

    /** Peak opacity of the full-screen parry wash. */
    static final float FLASH_PEAK_ALPHA = 0.32f;

    static final int BRACKET = MStyle.TEXT_PRIMARY;
    static final int BRACKET_LIVE = BattlePalette.REACT_WINDOW;
    /** The monk's own ice-blue, lit most of the way to white: a parry is his moment. */
    static final int FLASH = MColor.lerp(BattlePalette.ACCENT_MONK, MStyle.TEXT_PRIMARY, 0.6f);
    static final int PARRY_WORD = MStyle.TEXT_PRIMARY;
    /** The shield is the monk's ice-blue; its word stays warm white, since ice-blue on snow is no contrast at all. */
    static final int BLOCK_COLOR = BattlePalette.ACCENT_MONK;
    static final int BLOCK_WORD = MStyle.TEXT_PRIMARY;
    static final int TOO_EARLY_COLOR = MStyle.TEXT_SECONDARY;

    private final MKeyHint hint = new MKeyHint(TimingRingOverlay.KEY_LABEL, TimingRingOverlay.KEY_ACTION)
            .outlined(true).fontSize(MStyle.FONT_CAPTION).align(MPainter.Align.CENTER);

    public void paint(MasonryUI ui, Canvas canvas, int w, int h, float scale, BattleView view,
                      TimedInputState state, BattleStageLayout layout, Matrix4fc viewProjection,
                      boolean guardWords) {
        if (canvas == null || view == null || state == null) return;
        PromptView.Parry prompt = view.prompt() instanceof PromptView.Parry p ? p : null;
        TimedInputState.ParryFeedback feedback = state.parryFeedback;
        if (prompt == null && feedback == TimedInputState.ParryFeedback.NONE) return;

        TimedLayout.Anchor anchor = TimedLayout.monkAnchor(layout, view, viewProjection, w, h, scale);
        if (prompt != null) {
            paintBrackets(ui, canvas, anchor, TimedLayout.parryTimeline(prompt, view.telegraph()), state.time, scale, prompt.canParry());
        }
        switch (feedback) {
            case PARRY -> paintParry(ui, canvas, w, h, anchor, state.parryFeedbackAge, scale, guardWords);
            case BLOCK -> paintBlock(ui, canvas, anchor, state.parryFeedbackAge, scale, guardWords);
            // Not behind the guard-words switch: see paintTooEarly.
            case TOO_EARLY -> paintTooEarly(ui, canvas, anchor, state.parryFeedbackAge, scale);
            case NONE -> { }
        }
    }

    // ─────────────────────────────────────────────── Closing brackets

    /** Height of each bracket for a shut half-gap. */
    static float bracketHeight(float closedHalfGap) {
        return closedHalfGap * 2.0f;
    }

    private void paintBrackets(MasonryUI ui, Canvas canvas, TimedLayout.Anchor a, TimedLayout.ParryTimeline t,
                               float time, float s, boolean canParry) {
        float closure = TimedLayout.bracketClosure(t);
        boolean live = t.inWindow();
        float half = TimedLayout.bracketHalfGap(a.radius(), closure);
        float height = bracketHeight(a.radius());
        float top = a.y() - height / 2f, bottom = a.y() + height / 2f;
        float arm = Math.max(8f * s, a.radius() * 0.42f);
        float width = Math.max(3f, (live ? 5.5f : 4f) * s);
        float pulse = live ? 0.5f + 0.5f * (float) Math.sin(time * 30.0) : 0f;

        if (!live) {
            // Ghost of the shut position: the mark the brackets are travelling to.
            float ghostW = Math.max(1.5f, 2f * s);
            bracket(canvas, a.x() - a.radius(), top, bottom, arm, 1f, ghostW, BRACKET, 0.35f, s);
            bracket(canvas, a.x() + a.radius(), top, bottom, arm, -1f, ghostW, BRACKET, 0.35f, s);
        } else {
            // Glow through the window: a gold wash between the shut brackets.
            MPainter.fillRoundedRect(canvas, a.x() - half, top, 2f * half, height, 6f * s,
                    MColor.withAlpha(BRACKET_LIVE, 0.16f + 0.12f * pulse));
        }
        int color = live ? MColor.lerp(BRACKET_LIVE, MStyle.TEXT_PRIMARY, pulse * 0.6f) : BRACKET;
        bracket(canvas, a.x() - half, top, bottom, arm, 1f, width, color, 1f, s);
        bracket(canvas, a.x() + half, top, bottom, arm, -1f, width, color, 1f, s);

        if (ui == null) return;
        Font font = ui.fonts().get(MStyle.FONT_ITEM * (live ? 1.2f : 1f), s);
        if (font != null) {
            String label = canParry ? "PARRY" : "BLOCK";
            MPainter.drawTextOutlined(canvas, live ? label + "!" : label, a.x(), top - 10f * s, font,
                    live ? BRACKET_LIVE : BRACKET, MPainter.Align.CENTER, smallOutline(s));
        }
        hint.scale(s);
        float hintW = Math.max(hint.preferredWidth(ui), 1f), hintH = hint.preferredHeight();
        hint.bounds(a.x() - hintW / 2f, bottom + 16f * s - hintH / 2f, hintW, hintH).render(ui);
    }

    /** One square bracket whose spine is at {@code x}; {@code dir} +1 opens right ("["), −1 left. */
    private static void bracket(Canvas canvas, float x, float top, float bottom, float arm, float dir,
                                float width, int color, float alpha, float s) {
        TimedStrokes.haloPolyline(canvas, new float[]{x + dir * arm, top, x, top, x, bottom, x + dir * arm, bottom},
                width, color, alpha, s);
    }

    // ─────────────────────────────────────────────── Feedback

    private static void paintParry(MasonryUI ui, Canvas canvas, int w, int h, TimedLayout.Anchor a, float age,
                                   float s, boolean words) {
        float t = MColor.clamp01(age / TimedInputState.PARRY_FLASH_SECONDS);
        if (t < 1f) {
            // Whole-screen wash, then the ring that carries the eye outward from the monk.
            MScreenFx.flash(canvas, w, h, FLASH, FLASH_PEAK_ALPHA * TimedMotion.flash(t));
            float reach = (float) Math.hypot(Math.max(a.x(), w - a.x()), Math.max(a.y(), h - a.y()));
            float radius = a.radius() + (reach - a.radius()) * TimedMotion.settle(t);
            float fade = 1f - TimedMotion.smooth(t);
            float core = Math.max(2f, 6f * s * (1f - 0.5f * t));
            MScreenFx.expandingRing(canvas, a.x(), a.y(), radius, FLASH, 0.55f * fade, Math.max(6f, 26f * s * (1f - 0.7f * t)));
            MScreenFx.expandingRing(canvas, a.x(), a.y(), radius, MStyle.OUTLINE_DARK, 0.45f * fade,
                    core + TimedStrokes.underGrowth(s * 0.5f));
            MScreenFx.expandingRing(canvas, a.x(), a.y(), radius, PARRY_WORD, 0.95f * fade, core);
        }
        if (!words || ui == null) return;
        float tt = MColor.clamp01(age / TimedInputState.PARRY_TEXT_SECONDS);
        // Pops in oversized and settles; holds, then fades over the last third.
        Font font = ui.fonts().get(MStyle.FONT_TITLE * TimedMotion.pop(tt, 0.35f, 0.25f), s);
        if (font == null) return;
        float cx = Math.max(w * 0.15f, Math.min(w * 0.85f, a.x()));
        MPainter.drawTextOutlined(canvas, "PARRY!", cx, wordBaseline(a, font, s), font,
                MColor.fade(PARRY_WORD, TimedMotion.holdThenFade(tt, 0.65f)), MPainter.Align.CENTER, Math.max(2f, 4f * s));
    }

    private static void paintBlock(MasonryUI ui, Canvas canvas, TimedLayout.Anchor a, float age, float s,
                                   boolean words) {
        float t = MColor.clamp01(age / TimedInputState.BLOCK_SECONDS);
        float fade = TimedMotion.holdThenFade(t, 0.4f);
        float size = a.radius() * (1.1f + 0.25f * TimedMotion.settle(t));
        shield(canvas, a.x(), a.y(), size, fade, s);
        if (!words || ui == null) return;
        Font font = ui.fonts().get(MStyle.FONT_ITEM * 1.15f, s);
        if (font == null) return;
        MPainter.drawTextOutlined(canvas, "BLOCK", a.x(), wordBaseline(a, font, s), font,
                MColor.fade(BLOCK_WORD, fade), MPainter.Align.CENTER, smallOutline(s));
    }

    private static void paintTooEarly(MasonryUI ui, Canvas canvas, TimedLayout.Anchor a, float age, float s) {
        // Always drawn, whatever the guard-words switch says: that switch only exists to avoid
        // duplicating PARRY!/BLOCK with the floating numbers, and nothing else ever explains to the
        // player why the brackets vanished and the blow was merely blocked.
        if (ui == null) return;
        float t = MColor.clamp01(age / TimedInputState.TOO_EARLY_SECONDS);
        Font font = ui.fonts().get(MStyle.FONT_ITEM, s);
        if (font == null) return;
        // Sags a few pixels as it fades: a deflated word, not a celebration.
        MPainter.drawTextOutlined(canvas, "TOO EARLY", a.x(), wordBaseline(a, font, s) + 6f * s * t, font,
                MColor.fade(TOO_EARLY_COLOR, TimedMotion.holdThenFade(t, 0.5f)), MPainter.Align.CENTER,
                smallOutline(s));
    }

    /** Outline for the item-sized words: thin enough that the pixel font's counters stay open. */
    private static float smallOutline(float s) {
        return Math.max(1.5f, 2f * s);
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
        TimedStrokes.fillPolygon(canvas, xy, MColor.withAlpha(BLOCK_COLOR, 0.30f * alpha));
        TimedStrokes.haloPolygon(canvas, xy, Math.max(1.5f, 2.5f * s), MStyle.TEXT_PRIMARY, alpha, s * 0.7f);
    }
}
