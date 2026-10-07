package com.openmason.engine.ui.runtime.anim;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiAnimationClip;
import com.openmason.engine.format.omui.UiAnimationClip.LoopMode;
import com.openmason.engine.format.omui.UiEasing;
import com.openmason.engine.format.omui.UiStateMachine;
import com.openmason.engine.format.omui.UiStateMachine.Driver;
import com.openmason.engine.format.omui.UiStateMachine.MachineState;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiElement;
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
import static com.openmason.engine.ui.runtime.anim.AnimDocs.withEvents;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Animation state across tree edits and reloads, and event crossing in every loop mode (#282
 * hardening): channels die with their elements, ping-pong and long frame steps fire exactly the
 * crossed events, and state machines follow edited clips.
 */
class UiAnimationLifecycleTest {

    private static final UnaryOperator<String> SCREEN = k -> k;

    private static OmuiArchive list() {
        return screen("t:ui/list", box("root").kids(box("row1").style("opacity", 1), box("row2").style("opacity", 1)));
    }

    // ── channels follow element lifetime ───────────────────────────────────

    @Test
    void removingAnElementReleasesItsChannelsAndInterruptsWhatAnimatedOnlyIt() {
        UiDocumentInstance ui = run(list());
        AnimDocs.Log log = new AnimDocs.Log();
        ui.animator().tween(null, "row1", Map.of("opacity", UiValue.of(0)), 0.2, UiEasing.LINEAR,
            UiAnimator.TweenOptions.DEFAULTS, log);
        frame(ui, 0.5); // finished: held (fill hold)
        assertEquals(List.of("done"), log.lines);
        assertEquals(1, ui.animator().channelCount(), "the held fade keeps its channel");

        long running = ui.animator().tween(null, "row2", Map.of("opacity", UiValue.of(0)), 10, UiEasing.LINEAR,
            UiAnimator.TweenOptions.DEFAULTS, log);
        ui.find("row1").remove();
        ui.find("row2").remove();
        assertEquals(0, ui.animator().channelCount(), "removed elements leave no channels behind");
        assertFalse(ui.animator().isRunning(running));
        assertEquals(List.of("done", "stopped"), log.lines, "the running tween was interrupted");

        // The key comes back: it starts clean, and nothing old writes into it.
        UiElement again = ui.root().insertChild(-1, box("row2").style("opacity", 1).build());
        frame(ui, 1);
        assertEquals(1, num(ui, "row2", "opacity"), 1e-9);
        assertTrue(again.computedStyle().get("opacity") != null);
    }

    @Test
    void aReloadThatDropsAnElementDropsItsChannels() {
        UiDocumentInstance ui = run(list());
        ui.animator().tween(null, "row2", Map.of("opacity", UiValue.of(0.25)), 0.1, UiEasing.LINEAR,
            UiAnimator.TweenOptions.DEFAULTS, UiAnimator.Listener.NONE);
        frame(ui, 1);
        ui.reload(screen("t:ui/list", box("root").kids(box("row1").style("opacity", 1))));
        ui.resolveStyles();
        assertEquals(0, ui.animator().channelCount());
    }

    @Test
    void repeatedRemovalOfAnimatedRowsDoesNotAccumulateChannels() {
        UiDocumentInstance ui = run(screen("t:ui/chat", box("root")));
        for (int i = 0; i < 200; i++) {
            ui.root().insertChild(-1, box("line" + i).style("opacity", 1).build());
            ui.resolveStyles();
            ui.animator().tween(null, "line" + i, Map.of("opacity", UiValue.of(0)), 0.05, UiEasing.LINEAR,
                UiAnimator.TweenOptions.DEFAULTS, UiAnimator.Listener.NONE);
            frame(ui, 0.1);
            if (i >= 5) {
                ui.find("line" + (i - 5)).remove();
            }
        }
        assertEquals(5, ui.animator().channelCount(), "only the five lines still shown hold a fade");
    }

    // ── event crossing ─────────────────────────────────────────────────────

    private static UiAnimationClip slide(LoopMode mode, Object... events) {
        return withEvents(clip("s", 1, mode, track("row1", "style:translate-x", key(0, 0), key(1, 10))), events);
    }

    @Test
    void pingPongFiresEachEventOncePerPassInBothDirections() {
        UiDocumentInstance ui = run(list());
        AnimDocs.Log log = new AnimDocs.Log();
        ui.animator().play(slide(LoopMode.PING_PONG, 0.2, "e"), SCREEN, 1, null, log);
        for (int i = 0; i < 40; i++) {
            frame(ui, 0.05); // 2 s: forward to 1, back to 0
        }
        assertEquals(List.of("event:e", "event:e"), log.lines, "0.2 forward and 1.8 (= 0.2 backward)");
        for (int i = 0; i < 40; i++) {
            frame(ui, 0.05);
        }
        assertEquals(4, log.lines.size(), "one firing per pass, never one per frame of the backward half");
    }

    @Test
    void pingPongEndpointsFireOncePerTurn() {
        UiDocumentInstance ui = run(list());
        AnimDocs.Log log = new AnimDocs.Log();
        ui.animator().play(slide(LoopMode.PING_PONG, 0.0, "start", 1.0, "far"), SCREEN, 1, null, log);
        for (int i = 0; i < 40; i++) {
            frame(ui, 0.05);
        }
        assertEquals(List.of("event:start", "event:far", "event:start"), log.lines);
    }

    @Test
    void aFrameStepLongerThanTheClipFiresEveryCrossedEventInOrder() {
        UiDocumentInstance ui = run(list());
        AnimDocs.Log log = new AnimDocs.Log();
        ui.animator().play(slide(LoopMode.LOOP, 0.25, "a", 0.75, "b"), SCREEN, 1, null, log);
        frame(ui, 2.5); // crosses 0.25, 0.75, 1.25, 1.75, 2.25
        assertEquals(List.of("event:a", "event:b", "event:a", "event:b", "event:a"), log.lines);
    }

    @Test
    void aClockThatGoesBackwardsFiresNothing() {
        UiDocumentInstance ui = run(list());
        AnimDocs.Log log = new AnimDocs.Log();
        ui.clocks().set("ext", 5);
        ui.animator().play(null, slide(LoopMode.LOOP, 0.5, "e"), SCREEN,
            new UiAnimator.PlayOptions("ext", 1, null, 0, UiAnimator.Fill.HOLD, 0, true), log);
        ui.clocks().set("ext", 5.9);
        frame(ui, 0);
        assertEquals(List.of("event:e"), log.lines);
        ui.clocks().set("ext", 4);
        frame(ui, 0);
        ui.clocks().set("ext", 4.2);
        frame(ui, 0);
        assertEquals(List.of("event:e"), log.lines);
    }

    @Test
    void reducedMotionLoopsHoldTheirFirstFrameAndStopFiring() {
        UiDocumentInstance ui = run(list());
        ui.setPreferences(new com.openmason.engine.ui.runtime.UiPreferences(true, 1f));
        AnimDocs.Log log = new AnimDocs.Log();
        ui.animator().play(slide(LoopMode.LOOP, 0.0, "start", 0.5, "mid"), SCREEN, 1, null, log);
        frame(ui, 3);
        assertEquals(List.of("event:start"), log.lines);
    }

    // ── state machines follow the archive ──────────────────────────────────

    private static OmuiArchive glowing(double opacity, boolean withMachine) {
        OmuiArchive doc = screen("t:ui/glow", box("root").kids(box("lamp").style("opacity", 1)))
            .withAnimation(clip("glow", 1, LoopMode.LOOP, track("lamp", "style:opacity", key(0, opacity))));
        return withMachine ? doc.withStateMachine(new UiStateMachine("mode", Driver.MANUAL, null, "lit", List.of(
            new MachineState("lit", "glow", Map.of())), List.of(), Map.of())) : doc;
    }

    @Test
    void anEditedStateClipReachesARunningMachineOnReload() {
        UiDocumentInstance ui = run(glowing(0.3, true));
        frame(ui, 0.1);
        assertEquals(0.3, num(ui, "lamp", "opacity"), 1e-9);
        ui.reload(glowing(0.7, true));
        frame(ui, 0.1);
        assertEquals("lit", ui.stateMachines().state("", "mode"), "the machine keeps its state");
        assertEquals(0.7, num(ui, "lamp", "opacity"), 1e-9, "and plays the edited clip");
    }

    @Test
    void aRemovedMachineHandsWhatItHeldBackToTheCascade() {
        UiDocumentInstance ui = run(glowing(0.3, true));
        frame(ui, 0.1);
        assertEquals(0.3, num(ui, "lamp", "opacity"), 1e-9);
        ui.reload(glowing(0.3, false));
        frame(ui, 0.1);
        assertEquals(1, num(ui, "lamp", "opacity"), 1e-9, "no orphaned held value");
        assertEquals(0, ui.animator().channelCount());
    }

    @Test
    void colourMixingFormatsHexExactlyLikeTheOldFormatter() {
        java.util.Random r = new java.util.Random(7);
        for (int i = 0; i < 2000; i++) {
            int argb = r.nextInt();
            String expected = String.format("#%02X%02X%02X%02X", (argb >>> 16) & 0xFF, (argb >>> 8) & 0xFF,
                argb & 0xFF, argb >>> 24);
            assertEquals(UiValue.of(expected), AnimProperty.hex(argb));
        }
    }
}
