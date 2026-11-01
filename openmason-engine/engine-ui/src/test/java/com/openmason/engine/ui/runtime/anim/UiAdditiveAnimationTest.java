package com.openmason.engine.ui.runtime.anim;

import com.openmason.engine.format.omui.UiAnimationClip;
import com.openmason.engine.format.omui.UiAnimationClip.LoopMode;
import com.openmason.engine.format.omui.UiEasing;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.UiDocs;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static com.openmason.engine.ui.runtime.anim.AnimDocs.clip;
import static com.openmason.engine.ui.runtime.anim.AnimDocs.frame;
import static com.openmason.engine.ui.runtime.anim.AnimDocs.key;
import static com.openmason.engine.ui.runtime.anim.AnimDocs.num;
import static com.openmason.engine.ui.runtime.anim.AnimDocs.run;
import static com.openmason.engine.ui.runtime.anim.AnimDocs.track;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Additive composition (#301): {@link UiAnimator.Composite#ADD} animations stack deltas on what a
 * channel shows instead of taking it over, so a HUD shake runs on top of a slide or a fade.
 */
class UiAdditiveAnimationTest {

    private static final UnaryOperator<String> SCREEN = k -> k;
    private static final UiAnimator.PlayOptions ADD = UiAnimator.PlayOptions.DEFAULTS
        .withComposite(UiAnimator.Composite.ADD);
    private static final UiAnimator.TweenOptions ADD_TWEEN = UiAnimator.TweenOptions.DEFAULTS
        .withComposite(UiAnimator.Composite.ADD);

    private static UiDocumentInstance panel() {
        return run(screen("t:ui/p", box("root").kids(box("panel").style("translate-x", 10).style("opacity", 0.5))));
    }

    /** A one-second shake: +8 at 0.25 s, −8 at 0.75 s, back to 0. */
    private static UiAnimationClip shake() {
        return clip("shake", 1, LoopMode.ONCE, track("panel", "style:translate-x",
            key(0, 0), key(0.25, 8), key(0.75, -8), key(1, 0)));
    }

    @Test
    void anAdditiveTweenStacksOnTheCascade() {
        UiDocumentInstance ui = panel();
        ui.animator().tween(null, "panel", Map.of("translate-x", UiValue.of(4)), 1, UiEasing.LINEAR, ADD_TWEEN, null);
        frame(ui, 0.5);
        assertEquals(12, num(ui, "panel", "translate-x"), 1e-9, "cascade 10 + half of +4");
        assertEquals(10, ((UiValue.Num) ui.find("panel").baseStyle().get("translate-x")).value(), 1e-9,
            "the cascade itself is untouched");
    }

    @Test
    void aShakeRunsOnTopOfASlideAndNeitherInterruptsTheOther() {
        UiDocumentInstance ui = panel();
        AnimDocs.Log slideLog = new AnimDocs.Log();
        AnimDocs.Log shakeLog = new AnimDocs.Log();
        ui.animator().play(null, clip("slide", 1, LoopMode.ONCE,
            track("panel", "style:translate-x", key(0, 0), key(1, 100))), SCREEN, UiAnimator.PlayOptions.DEFAULTS, slideLog);
        frame(ui, 0.25);
        ui.animator().play(null, shake(), SCREEN, ADD, shakeLog);
        frame(ui, 0.25);
        assertEquals(50 + 8, num(ui, "panel", "translate-x"), 1e-9, "slide at 0.5 + shake at 0.25");
        frame(ui, 0.5);
        assertEquals(100 - 8, num(ui, "panel", "translate-x"), 1e-9, "slide held at its end + shake at 0.75");
        assertEquals(List.of("done"), slideLog.lines);
        assertTrue(shakeLog.lines.isEmpty(), "the shake keeps running");

        // A replacing tween started now starts from the slide's value, not slide + shake.
        ui.animator().tween("panel", Map.of("translate-x", UiValue.of(0)), 1, UiEasing.LINEAR, 0, null);
        assertTrue(shakeLog.lines.isEmpty(), "a replacing animation never takes an additive layer");
        frame(ui, 0);
        assertEquals(100 - 8, num(ui, "panel", "translate-x"), 1e-9, "from 100 under the shake");
        frame(ui, 0.25);
        assertEquals(75, num(ui, "panel", "translate-x"), 1e-9, "the shake ended at 0 and left nothing");
        assertEquals(List.of("done"), shakeLog.lines);
    }

    @Test
    void additiveLayersSumAndAFinishedZeroDeltaLeavesNoChannel() {
        UiDocumentInstance ui = panel();
        ui.animator().play(null, shake(), SCREEN, ADD, null);
        ui.animator().tween(null, "panel", Map.of("translate-x", UiValue.of(2)), 0.25, UiEasing.LINEAR, ADD_TWEEN
            .withComposite(UiAnimator.Composite.ADD), null);
        frame(ui, 0.25);
        assertEquals(10 + 8 + 2, num(ui, "panel", "translate-x"), 1e-9, "cascade + shake + tween");
        frame(ui, 0.75);
        assertEquals(12, num(ui, "panel", "translate-x"), 1e-9, "the tween's +2 holds; the shake ended on 0");
        ui.animator().release("panel", "translate-x");
        frame(ui, 0);
        assertEquals(10, num(ui, "panel", "translate-x"), 1e-9, "release drops additive layers too");
        assertEquals(0, ui.animator().channelCount());
    }

    @Test
    void aHeldDeltaFollowsTheCascadeUnderneath() {
        UiDocumentInstance ui = panel();
        ui.animator().tween(null, "panel", Map.of("translate-x", UiValue.of(5)), 0, UiEasing.LINEAR, ADD_TWEEN, null);
        frame(ui, 0);
        assertEquals(15, num(ui, "panel", "translate-x"), 1e-9);
        ui.find("panel").setStyle("translate-x", UiValue.of(40));
        ui.resolveStyles();
        assertEquals(45, num(ui, "panel", "translate-x"), 1e-9, "same frame: the delta rides the new cascade");
    }

    @Test
    void replayingAHeldShakeNeverPilesUpLayers() {
        UiDocumentInstance ui = panel();
        UiAnimationClip nudge = clip("nudge", 0.1, LoopMode.ONCE, track("panel", "style:translate-x",
            key(0, 0), key(0.1, 3)));
        for (int i = 0; i < 5; i++) {
            ui.animator().play("owner", nudge, SCREEN, ADD, null);
            frame(ui, 0.2);
        }
        assertEquals(13, num(ui, "panel", "translate-x"), 1e-9, "one held +3, not five");
        ui.animator().releaseHeld("owner", 0);
        frame(ui, 0);
        assertEquals(10, num(ui, "panel", "translate-x"), 1e-9, "the owner's held delta is released");
    }

    @Test
    void unitsColoursAndOpacityAddByTheirOwnRules() {
        AnimProperty tx = AnimProperty.style("translate-x");
        assertEquals(UiValue.of("7dpx"), tx.add(UiValue.of("4dpx"), UiValue.of("3dpx")));
        assertEquals(UiValue.of("30%"), tx.add(UiValue.of("10%"), UiValue.of("20%")));
        assertEquals(UiValue.of("10%"), tx.add(UiValue.of("10%"), UiValue.of(5)), "mixed units: base unchanged");
        assertEquals(UiValue.of(5.0), tx.add(null, UiValue.of(5)), "nothing set: the delta alone");
        AnimProperty opacity = AnimProperty.style("opacity");
        assertEquals(UiValue.of(1.0), opacity.add(UiValue.of(0.8), UiValue.of(0.5)), "clamped to 1");
        AnimProperty color = AnimProperty.style("color");
        assertEquals(UiValue.of("#FF4000FF"), color.add(UiValue.of("#F02000FF"), UiValue.of("#40200000")),
            "per byte, clamped");
        assertTrue(tx.isZero(UiValue.of(-0.0)));
        assertFalse(AnimProperty.style("visibility").additive());
    }

    @Test
    void nonAdditiveChannelsAreRefused() {
        UiDocumentInstance ui = run(screen("t:ui/p", box("root").kids(box("panel"))));
        assertThrows(IllegalArgumentException.class, () -> ui.animator().tween(null, "panel",
            Map.of("visibility", UiValue.of("hidden")), 1, UiEasing.LINEAR, ADD_TWEEN, null));
        ui.animator().play(null, clip("v", 1, LoopMode.ONCE,
            track("panel", "style:visibility", key(0, "hidden")),
            track("panel", "style:opacity", key(0, -0.5))), SCREEN, ADD, null);
        frame(ui, 0);
        assertTrue(UiDocs.has(ui, UiRuntimeDiagnostic.Code.ANIMATION_TRACK), "the visibility track is reported");
        assertEquals(0.5, num(ui, "panel", "opacity"), 1e-9, "the opacity track still adds (1 − 0.5)");
        assertNull(ui.find("panel").animatedStyle("visibility"), "visibility is never animated");
    }

    @Test
    void aRemovedElementTakesItsAdditiveLayersAlong() {
        UiDocumentInstance ui = panel();
        AnimDocs.Log log = new AnimDocs.Log();
        ui.animator().play(null, shake(), SCREEN, ADD, log);
        frame(ui, 0.1);
        ui.find("panel").remove();
        frame(ui, 0.1);
        assertEquals(List.of("stopped"), log.lines);
        assertEquals(0, ui.animator().channelCount());
        assertNull(ui.find("panel"));
    }

    @Test
    void anExternalClockDrivesAnAdditiveLayerDeterministically() {
        UiDocumentInstance ui = panel();
        ui.clocks().set("battle.sim", 0);
        ui.animator().play(null, shake(), SCREEN, new UiAnimator.PlayOptions("battle.sim", 1, null, 0,
            UiAnimator.Fill.HOLD, 0, true, UiAnimator.Composite.ADD), null);
        ui.clocks().set("battle.sim", 0.25);
        frame(ui, 5); // the UI clock's step is irrelevant
        assertEquals(18, num(ui, "panel", "translate-x"), 1e-9);
        ui.clocks().set("battle.sim", 0.5);
        frame(ui, 0);
        assertEquals(10, num(ui, "panel", "translate-x"), 1e-9, "mid-way between +8 and −8");
    }
}
