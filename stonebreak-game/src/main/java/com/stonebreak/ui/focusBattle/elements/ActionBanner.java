package com.stonebreak.ui.focusBattle.elements;

import com.stonebreak.battle.api.ActionView;
import com.stonebreak.battle.api.BattleCommand;
import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattlePhase;
import com.stonebreak.battle.api.BattleView;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.focusBattle.BattleHudAnimState;
import com.stonebreak.ui.focusBattle.FlowTheme;
import com.stonebreak.ui.focusBattle.FocusBattleLayout;
import com.stonebreak.ui.focusBattle.FocusBattleTheme;
import com.stonebreak.ui.focusBattle.SkijaFocusBattleRenderer;
import com.stonebreak.ui.startupIntro.tween.EasingFunctions;
import com.stonebreak.ui.startupIntro.tween.EasingType;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import org.joml.Matrix4fc;

import java.util.List;

/**
 * E7 — the FF-style name plate of the action being executed, for either side. Driven by
 * {@link BattleEvent.ActionStarted}: the plate drops in, holds for as long as the action runs (and
 * never less than {@link #MIN_HOLD_SECONDS}, so a free action still reads), then fades.
 *
 * <p>Archon actions are tinted frost-red, monk actions ice-blue, the Focus Combo gold — the side is
 * legible from the colour before the name is read. Nothing shows during the intro.
 */
public final class ActionBanner implements SkijaFocusBattleRenderer.Layer {

    public static final float DROP_SECONDS = 0.12f;
    public static final float MIN_HOLD_SECONDS = 0.9f;
    public static final float FADE_SECONDS = 0.25f;

    private String name;
    private CombatantId actor;
    private BattleCommand command;
    private float age;
    /** Seconds into the fade; negative while dropping or holding. */
    private float fadeAge = -1f;
    private boolean suppressed;

    public void reset() {
        name = null;
        actor = null;
        command = null;
        age = 0f;
        fadeAge = -1f;
        suppressed = false;
    }

    /** Advances the plate and picks up this frame's {@link BattleEvent.ActionStarted}. */
    public void update(float dt, BattleView view, List<BattleEvent> events) {
        if (view == null) return;
        float step = Math.max(0f, dt);
        suppressed = view.phase() == BattlePhase.INTRO;
        if (name != null) {
            age += step;
            if (fadeAge >= 0f) fadeAge += step;
        }
        boolean finished = false;
        if (events != null) {
            for (BattleEvent event : events) {
                if (event instanceof BattleEvent.ActionStarted started) {
                    show(started);
                    finished = false;
                } else if (event instanceof BattleEvent.ActionFinished done && done.actor() == actor) {
                    finished = true;
                }
            }
        }
        if (name == null) return;
        if (fadeAge < 0f && age >= MIN_HOLD_SECONDS && (finished || !stillRunning(view))) fadeAge = 0f;
        if (fadeAge >= FADE_SECONDS) reset();
    }

    private void show(BattleEvent.ActionStarted started) {
        name = started.displayName() == null ? "" : started.displayName();
        actor = started.actor();
        command = started.command();
        age = 0f;
        fadeAge = -1f;
        if (name.isEmpty()) name = null;
    }

    private boolean stillRunning(BattleView view) {
        ActionView action = view.currentAction();
        return action != null && action.actor() == actor;
    }

    // ─────────────────────────────────────────────── State (read by tests)

    public boolean visible() { return name != null && !suppressed && alpha() > 0f; }
    public String text() { return name; }
    /** 0 = above its slot, 1 = seated. */
    public float dropProgress() {
        return name == null ? 0f : EasingFunctions.apply(Math.min(1f, age / DROP_SECONDS), EasingType.EaseOutCubic);
    }
    public float alpha() {
        if (name == null) return 0f;
        float in = Math.min(1f, age / DROP_SECONDS);
        float out = fadeAge < 0f ? 1f : 1f - Math.min(1f, fadeAge / FADE_SECONDS);
        return in * out;
    }
    /** The plate's accent: frost-red for the Archon, gold for the Focus Combo, else ice-blue. */
    public int tint() {
        if (actor == CombatantId.ARCHON) return FlowTheme.FROST_RED;
        return command == BattleCommand.FOCUS_COMBO ? FlowTheme.GOLD : FlowTheme.MONK_TINT;
    }

    // ─────────────────────────────────────────────── Paint

    @Override
    public void paint(MasonryUI ui, Canvas canvas, int windowWidth, int windowHeight, float uiScale,
                      float rawUiScale, BattleView view, BattleHudAnimState anim, Matrix4fc viewProjection) {
        if (canvas == null || !visible()) return;
        float[] slot = FocusBattleLayout.actionBannerRect(windowWidth, windowHeight, rawUiScale);
        float alpha = alpha();
        float[] r = FocusBattleLayout.offset(slot, 0f, -(1f - dropProgress()) * (slot[3] + 8f * uiScale));
        int tint = tint();
        int fill = actor == CombatantId.ARCHON ? FlowTheme.FROST_RED_FILL
                : command == BattleCommand.FOCUS_COMBO ? FlowTheme.GOLD_FILL : FocusBattleTheme.WINDOW_FILL;
        FlowTheme.tintedWindow(canvas, r[0], r[1], r[2], r[3], fill, tint, alpha);

        float pad = 30f * uiScale;
        Font font = FocusBattleTheme.fitFont(ui, name, FlowTheme.FS_BANNER, uiScale, r[2] - 2f * pad);
        float cy = r[1] + r[3] / 2f;
        if (font != null) {
            FocusBattleTheme.textCentered(canvas, name, r[0] + r[2] / 2f,
                    FocusBattleTheme.baseline(cy, font.getSize()), font,
                    FocusBattleTheme.fade(FocusBattleTheme.TEXT, alpha));
        }
        // Accent bars at both ends, in the side's colour, so the side reads before the name does.
        float barW = Math.max(2f, 3f * uiScale);
        float barH = r[3] * 0.5f;
        int accent = FocusBattleTheme.fade(tint, alpha);
        FocusBattleTheme.roundedFill(canvas, r[0] + 10f * uiScale, cy - barH / 2f, barW, barH, barW / 2f, accent);
        FocusBattleTheme.roundedFill(canvas, r[0] + r[2] - 10f * uiScale - barW, cy - barH / 2f, barW, barH,
                barW / 2f, accent);
    }
}
