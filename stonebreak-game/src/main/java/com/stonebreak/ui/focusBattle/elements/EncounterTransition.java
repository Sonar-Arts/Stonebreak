package com.stonebreak.ui.focusBattle.elements;

import com.stonebreak.battle.api.BattlePhase;
import com.stonebreak.battle.api.BattleView;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.focusBattle.BattleHelpText;
import com.stonebreak.ui.focusBattle.BattleHudAnimState;
import com.stonebreak.ui.focusBattle.FlowLayout;
import com.stonebreak.ui.focusBattle.FlowTheme;
import com.stonebreak.ui.focusBattle.FocusBattleTheme;
import com.stonebreak.ui.focusBattle.SkijaFocusBattleRenderer;
import com.stonebreak.ui.startupIntro.tween.EasingFunctions;
import com.stonebreak.ui.startupIntro.tween.EasingType;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import org.joml.Matrix4fc;

import java.util.Locale;

/**
 * E15 — how the encounter opens: a white flash, a radial wipe that irises the arena in, and the
 * boss name card ("ICE ARCHON", letter-spaced over a thin rule and a subtitle) timed to the intro's
 * low-angle hero shot of the Archon. Painted above everything else in the HUD.
 *
 * <p>It belongs to the intro: the moment the battle leaves {@link BattlePhase#INTRO} — the intro
 * ran out or the player skipped it — whatever is still showing fades in {@link #SKIP_FADE_SECONDS}
 * and the element goes inert until the next {@link #reset}.
 */
public final class EncounterTransition implements SkijaFocusBattleRenderer.Layer {

    public static final float FLASH_SECONDS = 0.3f;
    public static final float WIPE_SECONDS = 0.8f;
    /** The card's window, in seconds of intro: the camera's Archon hero shot. */
    public static final float CARD_IN_SECONDS = 1.7f;
    public static final float CARD_OUT_SECONDS = 3.2f;
    public static final float CARD_FADE_IN_SECONDS = 0.3f;
    public static final float CARD_FADE_OUT_SECONDS = 0.4f;
    public static final float SKIP_FADE_SECONDS = 0.12f;
    public static final String SUBTITLE = "Warden of the Frostbound Crucible";

    private float age;
    /** Seconds since the battle left the intro; negative while the intro is still playing. */
    private float sinceIntroEnded = -1f;
    /** False for a battle first seen outside its intro: there is nothing to transition from. */
    private boolean armed;
    private boolean seen;

    public void reset() {
        age = 0f;
        sinceIntroEnded = -1f;
        armed = false;
        seen = false;
    }

    public void update(float dt, BattleView view) {
        if (view == null) return;
        float step = Math.max(0f, dt);
        boolean intro = view.phase() == BattlePhase.INTRO;
        if (!seen) {
            seen = true;
            armed = intro;
        }
        if (!armed) return;
        age += step;
        if (sinceIntroEnded >= 0f) sinceIntroEnded += step;
        else if (!intro) sinceIntroEnded = step;   // the exit starts on the very frame the intro ends
    }

    // ─────────────────────────────────────────────── State (read by tests)

    /** True while anything of the transition can still be on screen. */
    public boolean active() {
        return armed && (sinceIntroEnded < 0f || sinceIntroEnded < SKIP_FADE_SECONDS);
    }

    /** 1 while the intro plays, easing to 0 once it ends or is skipped. */
    private float presence() {
        if (!armed) return 0f;
        return sinceIntroEnded < 0f ? 1f : Math.max(0f, 1f - sinceIntroEnded / SKIP_FADE_SECONDS);
    }

    public float flashAlpha() {
        if (!active() || age >= FLASH_SECONDS) return 0f;
        return (1f - EasingFunctions.apply(age / FLASH_SECONDS, EasingType.EaseOutQuad)) * presence();
    }

    /** 0 = the wipe covers the whole screen, 1 = fully open. */
    public float wipeProgress() {
        return EasingFunctions.apply(Math.min(1f, age / WIPE_SECONDS), EasingType.EaseInOutCubic);
    }

    public boolean wipeVisible() { return active() && age < WIPE_SECONDS; }

    public float cardAlpha() {
        if (!active() || age < CARD_IN_SECONDS || age >= CARD_OUT_SECONDS) return 0f;
        float in = Math.min(1f, (age - CARD_IN_SECONDS) / CARD_FADE_IN_SECONDS);
        float out = Math.min(1f, (CARD_OUT_SECONDS - age) / CARD_FADE_OUT_SECONDS);
        return Math.min(in, out) * presence();
    }

    // ─────────────────────────────────────────────── Paint

    @Override
    public void paint(MasonryUI ui, Canvas canvas, int windowWidth, int windowHeight, float uiScale,
                      float rawUiScale, BattleView view, BattleHudAnimState anim, Matrix4fc viewProjection) {
        if (canvas == null || view == null || !active()) return;
        int w = windowWidth, h = windowHeight;

        float card = cardAlpha();
        if (card > 0f) {
            String name = view.archon() == null || view.archon().displayName() == null ? ""
                    : view.archon().displayName().toUpperCase(Locale.ROOT);
            paintCard(ui, canvas, FlowLayout.nameCardRect(w, h, rawUiScale), name, uiScale, card,
                    Math.min(1f, (age - CARD_IN_SECONDS) / 0.5f));
        }
        if (age >= WIPE_SECONDS) paintSkipHint(ui, canvas, w, h, uiScale, rawUiScale, presence());
        if (wipeVisible()) paintWipe(canvas, w, h, wipeProgress(), presence(), uiScale);
        float flash = flashAlpha();
        if (flash > 0f) {
            FocusBattleTheme.fillRect(canvas, 0f, 0f, w, h, FocusBattleTheme.fade(FlowTheme.FLASH_WHITE, flash));
        }
    }

    /**
     * The iris: everything outside a growing circle is covered. Drawn as one very thick stroked
     * circle, which needs neither a clip nor a path and costs a single draw.
     */
    private static void paintWipe(Canvas canvas, int w, int h, float progress, float presence, float uiScale) {
        float reach = (float) Math.hypot(w, h) / 2f;
        float radius = progress * (reach + 2f);
        float thickness = reach * 2f;
        // The cover thins as it opens, so its trailing edge dissolves instead of sliding off the corners.
        int cover = FocusBattleTheme.fade(FlowTheme.WIPE_FROST, (1f - 0.6f * progress) * presence);
        try (Paint p = new Paint().setColor(cover).setAntiAlias(true).setMode(PaintMode.STROKE)
                .setStrokeWidth(thickness)) {
            canvas.drawCircle(w / 2f, h / 2f, radius + thickness / 2f, p);
        }
        FlowTheme.ring(canvas, w / 2f, h / 2f, radius, Math.max(2f, 3f * uiScale),
                FocusBattleTheme.fade(FocusBattleTheme.FROST, (1f - progress) * presence));
    }

    /** {@code unfold} 0..1 opens the rule from its centre. */
    static void paintCard(MasonryUI ui, Canvas canvas, float[] rect, String name, float uiScale, float alpha,
                          float unfold) {
        FlowTheme.fadedBand(canvas, rect[0], rect[1], rect[2], rect[3], FlowTheme.CARD_BAND, alpha);
        float cx = rect[0] + rect[2] / 2f;
        float tracking = 9f * uiScale;

        // Fit the spaced title by hand: fitFont measures unspaced text.
        float size = FlowTheme.FS_CARD_TITLE * uiScale;
        Font title = ui.fonts().get(size);
        while (title != null && size > 12f && FlowTheme.spacedWidth(title, name, tracking) > rect[2] * 0.86f) {
            size -= 2f;
            title = ui.fonts().get(size);
        }
        if (title == null) return;
        float titleCy = rect[1] + rect[3] * 0.36f;
        FlowTheme.spacedTextCentered(canvas, name, cx, FocusBattleTheme.baseline(titleCy, title.getSize()), title,
                tracking, FlowTheme.CARD_TITLE, alpha);

        float ruleY = rect[1] + rect[3] * 0.64f;
        float ruleW = rect[2] * 0.62f * EasingFunctions.apply(FocusBattleTheme.clamp01(unfold), EasingType.EaseOutCubic);
        float ruleH = Math.max(1f, Math.round(1.5f * uiScale));
        FocusBattleTheme.fillRect(canvas, cx - ruleW / 2f, ruleY + 1f, ruleW, ruleH,
                FocusBattleTheme.fade(FlowTheme.OUTLINE, alpha * 0.8f));
        FocusBattleTheme.fillRect(canvas, cx - ruleW / 2f, ruleY, ruleW, ruleH,
                FocusBattleTheme.fade(FocusBattleTheme.FROST, alpha));

        Font sub = FocusBattleTheme.fitFont(ui, SUBTITLE, FlowTheme.FS_CARD_SUB, uiScale, rect[2] * 0.86f);
        if (sub == null) return;
        FocusBattleTheme.textCentered(canvas, SUBTITLE, cx,
                FocusBattleTheme.baseline(rect[1] + rect[3] * 0.82f, sub.getSize()), sub,
                FocusBattleTheme.fade(FlowTheme.CARD_SUBTITLE, alpha));
    }

    // The help strip slides out with the bottom HUD during the intro, so the skip hint lives here.
    private static void paintSkipHint(MasonryUI ui, Canvas canvas, int w, int h, float uiScale, float rawUiScale,
                                      float alpha) {
        Font font = FocusBattleTheme.font(ui, FlowTheme.FS_HINT, uiScale);
        if (font == null || alpha <= 0f) return;
        float[] anchor = FlowLayout.skipHintAnchor(w, h, rawUiScale);
        String hint = BattleHelpText.INTRO_HINT;
        FocusBattleTheme.text(canvas, hint, anchor[0] - MPainter.measureWidth(font, hint),
                FocusBattleTheme.baseline(anchor[1], font.getSize()), font,
                FocusBattleTheme.fade(FocusBattleTheme.TEXT_LABEL, alpha));
    }
}
