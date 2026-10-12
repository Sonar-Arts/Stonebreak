package com.openmason.engine.ui.runtime.anim;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiAnimationClip;
import com.openmason.engine.format.omui.UiAnimationClip.LoopMode;
import com.openmason.engine.format.omui.UiEasing;
import com.openmason.engine.format.omui.UiFeatures;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.DataCell;
import com.openmason.engine.ui.data.DataType;
import com.openmason.engine.ui.data.HostContract;
import com.openmason.engine.ui.data.UiHost;
import com.openmason.engine.ui.runtime.UiDocs;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiRuntimeContext;
import com.openmason.engine.ui.runtime.binding.UiBinder;
import com.openmason.engine.ui.runtime.binding.UiConverters;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static com.openmason.engine.ui.runtime.UiDocs.sheet;
import static com.openmason.engine.ui.runtime.anim.AnimDocs.clip;
import static com.openmason.engine.ui.runtime.anim.AnimDocs.frame;
import static com.openmason.engine.ui.runtime.anim.AnimDocs.key;
import static com.openmason.engine.ui.runtime.anim.AnimDocs.num;
import static com.openmason.engine.ui.runtime.anim.AnimDocs.rule;
import static com.openmason.engine.ui.runtime.anim.AnimDocs.run;
import static com.openmason.engine.ui.runtime.anim.AnimDocs.tr;
import static com.openmason.engine.ui.runtime.anim.AnimDocs.track;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-channel precedence (cascade → transition → explicit), interruption, blending, fill, stop
 * modes, retrigger and release back to bindings (#295).
 */
class UiAnimationPrecedenceTest {

    private static final UnaryOperator<String> SCREEN = k -> k;

    private static UiDocumentInstance panel() {
        return run(screen("t:ui/p", box("root").kids(box("panel").style("opacity", 1))));
    }

    @Test
    void aBindingResumesItsLatestValueWhenTheAnimationIsReleasedOrEnds() {
        OmuiArchive doc = UiDocs.declare(screen("t:ui/hud", box("root").data("hud").kids(
                box("bar").name("bar").bind("style:opacity", ".alpha", UiNode.BindingMode.TO_TARGET, null)),
            sheet("s", rule("#bar", List.of(tr("opacity", 0.2)), "width", 10))),
            List.of(UiFeatures.DATA), "t:hud@1");
        UiHost host = new UiHost();
        DataCell hud = host.data().register("hud", new DataCell(DataType.object("alpha", DataType.number()),
            new UiValue.Obj(Map.of("alpha", UiValue.of(0.8)))), HostContract.of("t:hud", 1));
        UiDocumentInstance ui = UiDocumentInstance.instantiate(doc, UiRuntimeContext.basic());
        try (UiBinder binder = UiBinder.open(ui, host, UiConverters.NONE)) {
            ui.resolveStyles();
            assertEquals(0.8, num(ui, "bar", "opacity"), 1e-9);

            long t = ui.animator().tween("bar", Map.of("opacity", UiValue.of(0)), 0.5, UiEasing.LINEAR, 0, null);
            frame(ui, 0.25);
            assertEquals(0.4, num(ui, "bar", "opacity"), 1e-9, "the tween owns the channel");
            hud.set(new UiValue.Obj(Map.of("alpha", UiValue.of(0.6))));
            frame(ui, 0);
            assertEquals(0.6, ui.find("bar").baseStyle().get("opacity") instanceof UiValue.Num n ? n.value() : -1, 1e-9,
                "the binding keeps updating underneath");
            assertEquals(0.4, num(ui, "bar", "opacity"), 1e-9, "while the animation stays on top");

            frame(ui, 0.25); // ends, holds 0
            assertFalse(ui.animator().isRunning(t));
            assertEquals(0, num(ui, "bar", "opacity"), 1e-9, "a finished tween holds");

            ui.animator().release("bar", "opacity");
            frame(ui, 0.1);
            assertEquals(0.3, num(ui, "bar", "opacity"), 1e-9, "released through the declared 0.2 s transition");
            frame(ui, 0.15);
            assertEquals(0.6, num(ui, "bar", "opacity"), 1e-9, "the binding's latest value");
            assertNull(ui.find("bar").animatedStyle("opacity"), "the channel is gone");
        }
    }

    @Test
    void fillReleaseHandsBackWhenTheClipEnds() {
        UiDocumentInstance ui = panel();
        ui.animator().play(null, clip("f", 0.5, LoopMode.ONCE, track("panel", "style:opacity", key(0, 0), key(0.5, 0.2))),
            SCREEN, new UiAnimator.PlayOptions(UiClocks.UI, 1, null, 0, UiAnimator.Fill.RELEASE, 0, true), null);
        frame(ui, 0.25);
        assertEquals(0.1, num(ui, "panel", "opacity"), 1e-9);
        frame(ui, 0.25);
        assertEquals(1, num(ui, "panel", "opacity"), 1e-9, "inline 1 again: no transition declared, so at once");
    }

    @Test
    void aNewerAnimationTakesOverOnlyTheChannelsItAnimates() {
        UiDocumentInstance ui = panel();
        AnimDocs.Log clipLog = new AnimDocs.Log();
        ui.animator().play(clip("c", 1, LoopMode.ONCE,
                track("panel", "style:opacity", key(0, 0), key(1, 1)),
                track("panel", "style:translate-x", key(0, 0), key(1, 100))),
            SCREEN, 1, null, clipLog);
        frame(ui, 0.5);
        AnimDocs.Log tweenLog = new AnimDocs.Log();
        ui.animator().tween("panel", Map.of("opacity", UiValue.of(0)), 0.5, UiEasing.LINEAR, 0, tweenLog);
        frame(ui, 0.25);
        assertEquals(0.25, num(ui, "panel", "opacity"), 1e-9, "the tween, from what was shown (0.5)");
        assertEquals(75, num(ui, "panel", "translate-x"), 1e-9, "the clip keeps its other track");
        assertTrue(clipLog.lines.isEmpty(), "partly overridden, not interrupted");

        ui.animator().tween("panel", Map.of("translate-x", UiValue.of(0)), 1, UiEasing.LINEAR, 0, null);
        assertEquals(List.of("stopped"), clipLog.lines, "left with no channel: interrupted");
    }

    @Test
    void blendCrossFadesFromWhatIsShown() {
        UiDocumentInstance ui = panel();
        ui.animator().tween("panel", Map.of("translate-x", UiValue.of(40)), 0, UiEasing.LINEAR, 0, null);
        frame(ui, 0);
        UiAnimationClip c = clip("b", 1, LoopMode.ONCE, track("panel", "style:translate-x", key(0, 0), key(1, 0)));
        ui.animator().play(null, c, SCREEN, UiAnimator.PlayOptions.DEFAULTS.withBlend(0.4), null);
        ui.resolveStyles();
        assertEquals(40, num(ui, "panel", "translate-x"), 1e-9, "no pop on the first frame");
        frame(ui, 0.2);
        assertEquals(20, num(ui, "panel", "translate-x"), 1e-4, "half way (ease-in-out midpoint)");
        frame(ui, 0.2);
        assertEquals(0, num(ui, "panel", "translate-x"), 1e-9, "the clip alone after the blend");
    }

    @Test
    void stopModesHoldJumpOrRelease() {
        UiAnimationClip c = clip("s", 1, LoopMode.ONCE, track("panel", "style:translate-y", key(0, 0), key(1, 10)));
        for (UiAnimator.StopMode mode : UiAnimator.StopMode.values()) {
            UiDocumentInstance ui = panel();
            AnimDocs.Log log = new AnimDocs.Log();
            long t = ui.animator().play(c, SCREEN, 1, null, log);
            frame(ui, 0.3);
            assertTrue(ui.animator().stop(t, mode));
            frame(ui, 0.5);
            double expected = switch (mode) {
                case HOLD -> 3;
                case END -> 10;
                case RELEASE -> Double.NaN; // back to the cascade: unset
            };
            assertEquals(expected, num(ui, "panel", "translate-y"), 1e-6, mode.toString());
            assertEquals(List.of("stopped"), log.lines);
            assertFalse(ui.animator().stop(t, mode), "already ended");
        }
    }

    @Test
    void retriggerRestartsUnlessAskedToKeepTheRunningPlayback() {
        UiAnimationClip c = clip("r", 1, LoopMode.ONCE, track("panel", "style:translate-x", key(0, 0), key(1, 10)));
        UiDocumentInstance ui = panel();
        Object owner = new Object();
        AnimDocs.Log first = new AnimDocs.Log();
        long a = ui.animator().play(owner, c, SCREEN, UiAnimator.PlayOptions.DEFAULTS, first);
        frame(ui, 0.5);
        long kept = ui.animator().play(owner, c, SCREEN,
            new UiAnimator.PlayOptions(UiClocks.UI, 1, null, 0, UiAnimator.Fill.HOLD, 0, false), null);
        assertEquals(a, kept, "restart = false returns the running playback");
        long b = ui.animator().play(owner, c, SCREEN, UiAnimator.PlayOptions.DEFAULTS, null);
        ui.resolveStyles();
        assertTrue(b != a);
        assertEquals(List.of("stopped"), first.lines, "the retriggered playback interrupts the old one");
        assertEquals(0, num(ui, "panel", "translate-x"), 1e-9, "from the start again");
    }

    @Test
    void transitionsKeepTrackingTheCascadeUnderAHeldValue() {
        UiDocumentInstance ui = run(screen("t:ui/h", box("root").kids(box("panel").name("panel").style("opacity", 1)),
            sheet("s", rule("#panel.dim", List.of(tr("opacity", 0.4)), "opacity", 0.2))));
        ui.animator().tween("panel", Map.of("opacity", UiValue.of(0.9)), 0, UiEasing.LINEAR, 0, null);
        frame(ui, 0);
        ui.find("panel").addClass("dim"); // matches the rule that declares the transition
        ui.find("panel").setStyle("opacity", UiValue.of(0.5)); // the local write wins the value: 1 → 0.5
        ui.resolveStyles(); // the transition starts now (t = 0)
        frame(ui, 0.1);
        assertEquals(0.9, num(ui, "panel", "opacity"), 1e-9, "the held explicit value stays on top");
        ui.animator().release("panel", "opacity");
        frame(ui, 0);
        assertEquals(0.875, num(ui, "panel", "opacity"), 1e-9, "the transition ran underneath: 0.1 of 0.4 s");
        frame(ui, 0.3);
        assertEquals(0.5, num(ui, "panel", "opacity"), 1e-9);
    }

    @Test
    void clearingAnOwnerLeavesOthersRunning() {
        UiDocumentInstance ui = panel();
        Object script = new Object();
        Object machine = new Object();
        AnimDocs.Log a = new AnimDocs.Log();
        AnimDocs.Log b = new AnimDocs.Log();
        ui.animator().tween(script, "panel", Map.of("translate-x", UiValue.of(10)), 1, UiEasing.LINEAR,
            UiAnimator.TweenOptions.DEFAULTS, a);
        ui.animator().tween(machine, "panel", Map.of("translate-y", UiValue.of(10)), 1, UiEasing.LINEAR,
            UiAnimator.TweenOptions.DEFAULTS, b);
        frame(ui, 0.5);
        ui.animator().clear(script);
        frame(ui, 0.5);
        assertEquals(5, num(ui, "panel", "translate-x"), 1e-9, "cleared: frozen where it was, never notified");
        assertEquals(10, num(ui, "panel", "translate-y"), 1e-9);
        assertTrue(a.lines.isEmpty());
        assertEquals(List.of("done"), b.lines);
    }

    @Test
    void propTracksAnimateWidgetPropertiesWithoutWritingTheSource() {
        UiDocumentInstance ui = run(screen("t:ui/l", box("root").kids(UiDocs.label("title", "Hello"))));
        ui.animator().tween("title", Map.of("prop:text", UiValue.of("Bye")), 0.5, UiEasing.LINEAR, 0, null);
        frame(ui, 0.25);
        assertEquals("Hello", ui.find("title").text("text"), "discrete: switches at the end");
        frame(ui, 0.25);
        assertEquals("Bye", ui.find("title").text("text"));
        assertNull(ui.find("title").localProp("text"), "the local (script) layer is untouched");
        ui.animator().release("title", "prop:text");
        assertEquals("Hello", ui.find("title").text("text"));
    }
}
