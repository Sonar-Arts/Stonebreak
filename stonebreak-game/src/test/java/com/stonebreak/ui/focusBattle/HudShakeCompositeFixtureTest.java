package com.stonebreak.ui.focusBattle;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiAnimationClip;
import com.openmason.engine.format.omui.UiAnimationClip.AnimKey;
import com.openmason.engine.format.omui.UiAnimationClip.AnimTrack;
import com.openmason.engine.format.omui.UiAnimationClip.LoopMode;
import com.openmason.engine.format.omui.UiDocument;
import com.openmason.engine.format.omui.UiEasing;
import com.openmason.engine.format.omui.UiManifest;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiRuntimeContext;
import com.openmason.engine.ui.runtime.anim.UiAnimator;
import com.openmason.engine.util.easing.EasingFunctions;
import com.openmason.engine.util.easing.EasingType;
import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattlePhase;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.FakeBattleView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #301 additive-layer fixture against {@link HudAnimator}: the battle HUD's party window slides out
 * for the intro cinematic while a hit shakes it, and the {@code stonebreak:battle} contract places it
 * at {@code (shakeX, shakeY + slide)}. A document reproduces that with a replacing slide clip plus an
 * <b>additive</b> shake clip, both sampled from the {@code battle} clock (C1) the host sets each
 * frame, never interpolated from the frame clock. The clips are dense key tables of
 * {@code HudAnimator}'s own curves, so this pins the composition and the clock wiring, not the
 * curve shapes.
 */
class HudShakeCompositeFixtureTest {

    private static final float DT = 1f / 60f;
    private static final double KEY_STEP = 1.0 / 240.0;
    /** The bottom HUD's slide-out distance at scale 1 for this fixture. */
    private static final double SLIDE_PX = 100;
    private static final String BATTLE = "battle";

    /** HitShake for a 30 HP blow on the 180 HP monk: amplitude 3 + 7 × min(1, 30 / 36). */
    private static final double AMPLITUDE = 3 + 7 * Math.min(1.0, 30.0 / (180.0 * 0.2));

    private static double decay(double age) {
        double t = Math.min(1, age / HudAnimator.SHAKE_SECONDS);
        return (1 - t) * (1 - t);
    }

    private static List<AnimKey> table(double duration, DoubleUnaryOperator f) {
        List<AnimKey> keys = new ArrayList<>();
        int n = (int) Math.round(duration / KEY_STEP);
        for (int i = 0; i <= n; i++) {
            double t = i * KEY_STEP;
            keys.add(new AnimKey(t, UiValue.of(f.applyAsDouble(t)), UiEasing.LINEAR, null, Map.of()));
        }
        return keys;
    }

    private static OmuiArchive hud() {
        UiNode party = new UiNode("party", "party", "Box", 1, List.of(), Map.of(),
            Map.of("width", UiValue.of(200), "height", UiValue.of(80)), null, List.of(), null, List.of(), Map.of());
        UiNode root = UiNode.of("root", "Box", List.of(party));
        double shake = HudAnimator.SHAKE_SECONDS;
        UiAnimationClip slide = new UiAnimationClip("slide-out", HudAnimator.CINEMATIC_SLIDE_SECONDS, LoopMode.ONCE,
            List.of(new AnimTrack("party", "style:translate-y", table(HudAnimator.CINEMATIC_SLIDE_SECONDS,
                t -> SLIDE_PX * EasingFunctions.apply((float) Math.min(1, t / HudAnimator.CINEMATIC_SLIDE_SECONDS),
                    EasingType.EaseInOutCubic)), Map.of())), List.of(), Map.of());
        UiAnimationClip hit = new UiAnimationClip("party-shake", shake, LoopMode.ONCE, List.of(
            new AnimTrack("party", "style:translate-x",
                table(shake, t -> t >= shake ? 0 : AMPLITUDE * decay(t) * Math.sin(t * 88.0)), Map.of()),
            new AnimTrack("party", "style:translate-y",
                table(shake, t -> t >= shake ? 0 : AMPLITUDE * 0.6 * decay(t) * Math.cos(t * 67.0)), Map.of())),
            List.of(), Map.of());
        return OmuiArchive.of(UiManifest.create("t:ui/battle-hud", UiManifest.DocumentKind.SCREEN, "battle-hud"),
                new UiDocument(root, List.of(), null, null, Map.of()))
            .withAnimation(slide).withAnimation(hit);
    }

    private static double num(UiDocumentInstance ui, String property) {
        return ui.find("party").computedStyle().get(property) instanceof UiValue.Num n ? n.value() : 0;
    }

    /** Runs the scenario; {@code composite} is how the shake is played. Returns the worst mismatch in px. */
    private static double run(UiAnimator.Composite composite) {
        FakeBattleView view = new FakeBattleView();
        BattleMenuState menu = new BattleMenuState();
        BattleHudAnimState anim = new BattleHudAnimState();
        HudAnimator animator = new HudAnimator(anim);
        animator.reset(view, menu);

        UiDocumentInstance ui = UiDocumentInstance.instantiate(hud(), UiRuntimeContext.basic());
        ui.clocks().set(BATTLE, 0);
        ui.resolveStyles();

        UiAnimator.PlayOptions onBattle = new UiAnimator.PlayOptions(BATTLE, 1, null, 0, UiAnimator.Fill.HOLD, 0, true);
        double battleTime = 0;
        double worst = 0;
        for (int frame = 0; frame < 40; frame++) {
            List<BattleEvent> events = List.of();
            if (frame == 3) {
                // The intro starts during this step: the slide runs from the previous reading.
                view.phase = BattlePhase.INTRO;
                ui.animator().play(null, ui.document().animations().get("slide-out"), k -> k, onBattle, null);
            }
            battleTime += DT;
            ui.clocks().set(BATTLE, battleTime); // C1, set by the host; the UI clock below is irrelevant
            if (frame == 5) {
                events = List.of(new BattleEvent.DamageDealt(CombatantId.MONK, 30f, BattleEvent.DamageFlavor.NORMAL));
                ui.animator().play(null, ui.document().animations().get("party-shake"), k -> k,
                    onBattle.withComposite(composite), null);
            }
            animator.update(DT, view, menu, events);
            ui.advanceClock(0.5); // a wildly different frame step must not matter
            ui.resolveStyles();

            double wantX = anim.partyShakeX;
            double wantY = anim.partyShakeY + anim.bottomHudSlideOut * SLIDE_PX;
            worst = Math.max(worst, Math.abs(num(ui, "translate-x") - wantX));
            worst = Math.max(worst, Math.abs(num(ui, "translate-y") - wantY));
        }
        return worst;
    }

    @Test
    void anAdditiveShakeOverASlideMatchesHudAnimatorFrameForFrame() {
        assertEquals(0, run(UiAnimator.Composite.ADD), 2e-3, "document = HudAnimator within float noise");
    }

    @Test
    void lastWriterWinsWouldDropTheSlideUnderTheShake() {
        assertTrue(run(UiAnimator.Composite.REPLACE) > 50, "a replacing shake takes translate-y off the slide");
    }
}
