package com.stonebreak.ui.focusBattle.elements;

import com.stonebreak.battle.api.BattlePhase;
import com.stonebreak.battle.api.BattleView;
import com.stonebreak.rendering.UI.masonryUI.MColor;
import com.stonebreak.rendering.UI.masonryUI.MKeyHint;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MScreenFx;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.focusBattle.BattleHudAnimState;
import com.stonebreak.ui.focusBattle.BattlePalette;
import com.stonebreak.ui.focusBattle.SkijaFocusBattleRenderer;
import com.stonebreak.ui.startupIntro.tween.EasingFunctions;
import com.stonebreak.ui.startupIntro.tween.EasingType;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import org.joml.Matrix4fc;

import java.util.Locale;

/**
 * E15: how the encounter opens: a white {@link MScreenFx#flash flash}, a radial wipe that irises the
 * arena in, and the boss name card ("ICE ARCHON", letter-spaced over a thin rule and a subtitle, in
 * a {@link MPainter#hudFrame HUD frame}) timed to the intro's low-angle hero shot of the Archon.
 * Painted above everything else in the HUD.
 *
 * <p>It belongs to the intro: the moment the battle leaves {@link BattlePhase#INTRO} (the intro
 * ran out or the player skipped it) whatever is still showing fades in {@link #SKIP_FADE_SECONDS}
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
    public static final String SKIP_KEY = "SPACE";
    public static final String SKIP_LABEL = "Skip";

    /** The wash the encounter opens on, and the frost-tinted cover the iris opens through. */
    public static final int FLASH_COLOR = MStyle.TEXT_PRIMARY;
    public static final int WIPE_COLOR = MColor.lerp(MStyle.TEXT_PRIMARY, BattlePalette.FROST, 0.3f);

    private static final float CARD_W = 760f;
    private static final float CARD_H = 104f;
    private static final float TITLE_SIZE = 44f;
    private static final float TITLE_TRACKING = 9f;

    // The help strip slides out with the bottom HUD during the intro, so the skip hint lives here.
    private final MKeyHint skipHint = new MKeyHint(SKIP_KEY, SKIP_LABEL).align(MPainter.Align.RIGHT);

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

    // ─────────────────────────────────────────────── Layout

    /**
     * The encounter name card: a wide plate in the lower third, above the bottom letterbox bar, so
     * the Archon's hero shot stays clear above it.
     */
    public static float[] nameCardRect(int w, int h, float scale) {
        float cardW = Math.min(CARD_W * scale, w * 0.9f);
        float cardH = CARD_H * scale;
        float y = Math.max(0f, h - bottomBarHeight(h) - 28f * scale - cardH);
        return new float[]{(w - cardW) / 2f, y, cardW, cardH};
    }

    /** The "skip" hint's row: the whole bottom letterbox bar, inset from the right edge. */
    public static float[] skipHintRect(int w, int h, float scale) {
        float bar = bottomBarHeight(h);
        float inset = 24f * scale;
        return new float[]{inset, h - bar, Math.max(0f, w - 2f * inset), bar};
    }

    private static float bottomBarHeight(int h) {
        return MScreenFx.letterboxHeight(h, 1f, MScreenFx.LETTERBOX_FRACTION);
    }

    // ─────────────────────────────────────────────── Paint

    @Override
    public void paint(MasonryUI ui, Canvas canvas, int windowWidth, int windowHeight, float uiScale,
                      float rawUiScale, BattleView view, BattleHudAnimState anim, Matrix4fc viewProjection) {
        if (ui == null || canvas == null || view == null || !active()) return;
        int w = windowWidth, h = windowHeight;

        float card = cardAlpha();
        if (card > 0f) {
            String name = view.archon() == null || view.archon().displayName() == null ? ""
                    : view.archon().displayName().toUpperCase(Locale.ROOT);
            paintCard(ui, canvas, nameCardRect(w, h, uiScale), name, uiScale, card,
                    Math.min(1f, (age - CARD_IN_SECONDS) / 0.5f));
        }
        if (age >= WIPE_SECONDS) {
            float[] row = skipHintRect(w, h, uiScale);
            skipHint.alpha(presence()).scale(uiScale).bounds(row[0], row[1], row[2], row[3]).render(ui);
        }
        if (wipeVisible()) paintWipe(canvas, w, h, wipeProgress(), presence(), uiScale);
        MScreenFx.flash(canvas, w, h, FLASH_COLOR, flashAlpha());
    }

    /**
     * The iris: everything outside a growing circle is covered. One very thick stroked circle, which
     * needs neither a clip nor a path and costs a single draw.
     */
    private static void paintWipe(Canvas canvas, int w, int h, float progress, float presence, float uiScale) {
        float reach = (float) Math.hypot(w, h) / 2f;
        float radius = progress * (reach + 2f);
        float thickness = reach * 2f;
        // The cover thins as it opens, so its trailing edge dissolves instead of sliding off the corners.
        MPainter.strokeCircle(canvas, w / 2f, h / 2f, radius + thickness / 2f,
                MColor.fade(WIPE_COLOR, (1f - 0.6f * progress) * presence), thickness);
        MScreenFx.expandingRing(canvas, w / 2f, h / 2f, radius, BattlePalette.FROST, (1f - progress) * presence,
                Math.max(2f, 3f * uiScale));
    }

    /** {@code unfold} 0..1 opens the rule from its centre. */
    static void paintCard(MasonryUI ui, Canvas canvas, float[] rect, String name, float uiScale, float alpha,
                          float unfold) {
        MPainter.hudFrame(canvas, rect[0], rect[1], rect[2], rect[3], BattlePalette.ACCENT_ARCHON, alpha);
        float cx = rect[0] + rect[2] / 2f;
        float maxW = rect[2] * 0.86f;

        // The fit measures unspaced text, so the tracking comes off the width it may use.
        float tracking = TITLE_TRACKING * uiScale;
        Font title = ui.fonts().fit(name, TITLE_SIZE * uiScale,
                maxW - tracking * Math.max(0, name.length() - 1), 0.3f);
        if (title == null) return;
        drawSpaced(canvas, name, cx, MPainter.baselineFor(rect[1] + rect[3] * 0.38f, title.getSize()), title,
                tracking, MColor.fade(MStyle.TEXT_ACCENT, alpha));

        float ruleW = rect[2] * 0.62f
                * EasingFunctions.apply(MColor.clamp01(unfold), EasingType.EaseOutCubic);
        MPainter.fillRect(canvas, cx - ruleW / 2f, rect[1] + rect[3] * 0.64f, ruleW,
                Math.max(1f, Math.round(1.5f * uiScale)), MColor.fade(BattlePalette.ACCENT_ARCHON, alpha));

        Font sub = ui.fonts().fit(SUBTITLE, MStyle.FONT_META * uiScale, maxW, 0.6f);
        if (sub == null) return;
        MPainter.drawText(canvas, SUBTITLE, cx, MPainter.baselineFor(rect[1] + rect[3] * 0.82f, sub.getSize()), sub,
                MColor.fade(MStyle.TEXT_SECONDARY, alpha), MPainter.Align.CENTER);
    }

    /** Letter-spaced shadowed text centred on {@code cx}: the library draws whole strings only. */
    private static void drawSpaced(Canvas canvas, String text, float cx, float baseline, Font font, float tracking,
                                   int color) {
        float total = tracking * Math.max(0, text.length() - 1);
        for (int i = 0; i < text.length(); i++) total += MPainter.measureWidth(font, text.substring(i, i + 1));
        float x = cx - total / 2f;
        for (int i = 0; i < text.length(); i++) {
            String glyph = text.substring(i, i + 1);
            MPainter.drawText(canvas, glyph, x, baseline, font, color, MPainter.Align.LEFT);
            x += MPainter.measureWidth(font, glyph) + tracking;
        }
    }
}
