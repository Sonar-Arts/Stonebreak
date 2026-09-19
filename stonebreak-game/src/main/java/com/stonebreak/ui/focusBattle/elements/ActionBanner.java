package com.stonebreak.ui.focusBattle.elements;

import com.stonebreak.battle.api.ActionView;
import com.stonebreak.battle.api.BattleCommand;
import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattlePhase;
import com.stonebreak.battle.api.BattleView;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.rendering.UI.masonryUI.MBanner;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.focusBattle.BattleHudAnimState;
import com.stonebreak.ui.focusBattle.BattlePalette;
import com.stonebreak.ui.focusBattle.FocusBattleLayout;
import com.stonebreak.ui.focusBattle.SkijaFocusBattleRenderer;
import io.github.humbleui.skija.Canvas;
import org.joml.Matrix4fc;

import java.util.List;

/**
 * E7: the name plate of the action being executed, for either side. A thin adapter over one
 * {@link MBanner}: {@link BattleEvent.ActionStarted} shows it, it is held for as long as that action
 * is the view's current one (and never less than the banner's minimum hold, so a free action still
 * reads), then it fades.
 *
 * <p>The plate's hairline is the acting side's accent, gold for the Focus Combo, so the side reads
 * from the colour before the name does. Nothing shows during the intro.
 */
public final class ActionBanner implements SkijaFocusBattleRenderer.Layer {

    public static final float DROP_SECONDS = MBanner.DEFAULT_IN_SECONDS;
    public static final float MIN_HOLD_SECONDS = MBanner.DEFAULT_MIN_HOLD_SECONDS;
    public static final float FADE_SECONDS = MBanner.DEFAULT_OUT_SECONDS;

    private final MBanner plate = new MBanner();
    private CombatantId actor;
    private boolean suppressed;

    public void reset() {
        plate.hide();
        actor = null;
        suppressed = false;
    }

    /** Advances the plate and picks up this frame's {@link BattleEvent.ActionStarted}. */
    public void update(float dt, BattleView view, List<BattleEvent> events) {
        if (view == null) return;
        suppressed = view.phase() == BattlePhase.INTRO;
        plate.update(dt);
        if (events != null) {
            for (BattleEvent event : events) {
                if (event instanceof BattleEvent.ActionStarted started) {
                    actor = started.actor();
                    plate.show(started.displayName(), accentOf(started.actor(), started.command()));
                }
            }
        }
        ActionView running = view.currentAction();
        plate.hold(plate.active() && running != null && running.actor() == actor);
    }

    /** The side's accent, or the Focus gold for the monk's ultimate. */
    static int accentOf(CombatantId actor, BattleCommand command) {
        return command == BattleCommand.FOCUS_COMBO ? BattlePalette.FOCUS : BattlePalette.accent(actor);
    }

    // ─────────────────────────────────────────────── State (read by tests)

    public boolean visible() { return !suppressed && plate.visible(); }
    public String text() { return plate.text(); }
    /** 0 = above its slot, 1 = seated. */
    public float dropProgress() { return plate.dropProgress(); }
    public float alpha() { return plate.alpha(); }
    /** The plate's accent hairline: the acting side's colour, gold for the Focus Combo. */
    public int tint() { return plate.accent(); }

    // ─────────────────────────────────────────────── Paint

    @Override
    public void paint(MasonryUI ui, Canvas canvas, int windowWidth, int windowHeight, float uiScale,
                      float rawUiScale, BattleView view, BattleHudAnimState anim, Matrix4fc viewProjection) {
        if (canvas == null || !visible()) return;
        float[] slot = FocusBattleLayout.actionBannerRect(windowWidth, windowHeight, rawUiScale);
        plate.bounds(slot[0], slot[1], slot[2], slot[3]).scale(uiScale).render(ui);
    }
}
