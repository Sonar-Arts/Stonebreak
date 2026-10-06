package com.openmason.engine.ui.script;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiAnimationClip;
import com.openmason.engine.format.omui.UiDocument;
import com.openmason.engine.format.omui.UiAnimationClip.AnimKey;
import com.openmason.engine.format.omui.UiAnimationClip.AnimTrack;
import com.openmason.engine.format.omui.UiAnimationClip.LoopMode;
import com.openmason.engine.format.omui.UiEasing;
import com.openmason.engine.format.omui.UiFeatures;
import com.openmason.engine.format.omui.UiStateMachine;
import com.openmason.engine.format.omui.UiStateMachine.Driver;
import com.openmason.engine.format.omui.UiStateMachine.MachineState;
import com.openmason.engine.format.omui.UiStateMachine.MachineTransition;
import com.openmason.engine.format.omui.UiStyleSheet;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.graph.GraphBuilder;
import com.openmason.engine.ui.runtime.UiDocs;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.anim.UiClocks;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.label;
import static com.openmason.engine.ui.runtime.UiDocs.node;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lua and compiled graphs drive the shared sampler (#295): start, cancel and configure clips and
 * tweens, set UI states, and never see completions from a closed or reloaded screen.
 */
class ScriptAnimationTest {

    private static AnimKey key(double t, double v) {
        return new AnimKey(t, UiValue.of(v), UiEasing.LINEAR, Map.of());
    }

    /** {@code slide}: translate-x 0 → 100 over 1 s; {@code fade}: opacity 1 → 0 over 1 s. */
    static OmuiArchive stage() {
        UiStateMachine m = new UiStateMachine("screen", Driver.MANUAL, null, "shown", List.of(
            new MachineState("hidden", "fade", Map.of()), new MachineState("shown", null, Map.of())), List.of(), Map.of());
        OmuiArchive doc = screen("t:ui/stage", box("root").style("width", 400).style("height", 300).kids(
                box("panel").name("panel").style("width", 50).style("height", 20).style("opacity", 1),
                node("go", "Button").name("go").style("width", 100).style("height", 30),
                label("out", "-").name("out")))
            .withAnimation(new UiAnimationClip("slide", 1, LoopMode.ONCE, List.of(new AnimTrack("panel",
                "style:translate-x", List.of(key(0, 0), key(1, 100)), Map.of())), List.of(), Map.of()))
            .withAnimation(new UiAnimationClip("fade", 1, LoopMode.ONCE, List.of(new AnimTrack("panel",
                "style:opacity", List.of(key(0, 1), key(1, 0)), Map.of())), List.of(), Map.of()))
            .withStateMachine(m);
        return UiDocs.declare(doc, List.of(UiFeatures.STATES));
    }

    private static double num(ScriptRig rig, String key, String property) {
        UiValue v = rig.el(key).computedStyle().get(property);
        return v instanceof UiValue.Num n ? n.value() : Double.NaN;
    }

    @Test
    void luaStartsConfiguresAndCancelsClipsAndTweens() {
        OmuiArchive doc = ScriptRig.withCode(stage(), """
            function on_open(ui)
              ui.root:q("#go"):on("click", function()
                local h = ui.play("slide", { speed = 2 })
                ui.await(ui.sleep(0.25))
                ui.speed(h, 1)                 -- configure while it runs
                ui.await(ui.sleep(0.25))
                ui.seek("slide", 0.9)          -- by clip id
                ui.stop("slide", "end")        -- jump to the end and hold
                ui.q("#out"):setText(string.format("%.0f", ui.clock()))
              end)
            end
            """);
        try (ScriptRig rig = new ScriptRig(doc)) {
            rig.click("go");
            rig.frame(0.25);
            assertEquals(50, num(rig, "panel", "translate-x"), 1e-6, "speed 2");
            rig.frame(0.125);
            assertEquals(62.5, num(rig, "panel", "translate-x"), 1e-6, "then speed 1, no jump");
            rig.frame(0.125); // at 0.5 s: seek to 0.9, then stop at the end
            assertEquals(100, num(rig, "panel", "translate-x"), 1e-6, "stopped at its end");
            assertEquals(0, rig.ui.animator().active());
            assertTrue(rig.rt.diagnostics().isEmpty(), rig.rt.diagnostics().toString());
        }
    }

    @Test
    void tweensTakeFromFillAndClockOptions() {
        OmuiArchive doc = ScriptRig.withCode(stage(), """
            function on_open(ui)
              ui.tween(ui.q("#panel"), { ["translate-y"] = 10 }, 1, "linear", { from = { ["translate-y"] = 30 } })
              ui.tween(ui.q("#panel"), { opacity = 0.2 }, 1, "linear", { clock = "game", fill = "release" })
            end
            """);
        try (ScriptRig rig = new ScriptRig(doc)) {
            rig.frame(0.5);
            assertEquals(20, num(rig, "panel", "translate-y"), 1e-6, "from the given start");
            assertEquals(1, num(rig, "panel", "opacity"), 1e-6, "game time has not moved");
            rig.ui.clocks().advance(UiClocks.GAME, 0.5);
            rig.frame(0);
            assertEquals(0.6, num(rig, "panel", "opacity"), 1e-6);
            rig.ui.clocks().advance(UiClocks.GAME, 0.5);
            rig.frame(0);
            assertEquals(1, num(rig, "panel", "opacity"), 1e-6, "fill = release: the inline value again");
        }
    }

    @Test
    void luaTweensTakeCssCubicBezierEasing() {
        OmuiArchive doc = ScriptRig.withCode(stage(), """
            function on_open(ui)
              ui.tween(ui.q("#panel"), { ["translate-x"] = 100 }, 1, "cubic-bezier(0.25, 0.1, 0.25, 1)")
              local ok, err = pcall(ui.tween, ui.q("#panel"), { opacity = 0 }, 1, "cubic-bezier(2, 0, 1, 1)")
              ui.q("#out"):setText(ok and "accepted" or "refused")
            end
            """);
        try (ScriptRig rig = new ScriptRig(doc)) {
            rig.frame(0.5);
            assertEquals(80.24033877, num(rig, "panel", "translate-x"), 1e-4);
            assertEquals("refused", rig.text("out"), "x outside [0, 1] is an error");
        }
    }

    @Test
    void luaSetsUiStatesAndWaitsForArrival() {
        OmuiArchive doc = ScriptRig.withCode(stage(), """
            function on_open(ui)
              ui.await(ui.setState("screen", "hidden"))
              ui.q("#out"):setText(ui.machineState("screen"))
            end
            """);
        try (ScriptRig rig = new ScriptRig(doc)) {
            rig.frame(0.5);
            assertEquals(0.5, num(rig, "panel", "opacity"), 1e-6);
            assertEquals("-", rig.text("out"));
            rig.frame(0.6);
            rig.frame(0);
            assertEquals("hidden", rig.text("out"));
        }
    }

    /** The same behaviour written as a graph: play with blend, set the speed, stop at the end, set a state. */
    static OmuiArchive graphStage() {
        return stage().withGraph(new GraphBuilder("anim")
            .node("on_go", "ui:event.click", "target", "go")
            .node("play", "ui:anim.play", "clip", "slide", "=speed", 2)
            .node("wait", "ui:flow.wait", "=seconds", 0.25)
            .node("speed", "ui:anim.set-speed", "clip", "slide", "=speed", 1)
            .node("wait2", "ui:flow.wait", "=seconds", 0.25)
            .node("seek", "ui:anim.seek", "clip", "slide", "=time", 0.9)
            .node("stop", "ui:anim.stop", "clip", "slide", "how", "end")
            .node("hide", "ui:anim.set-state", "machine", "screen", "state", "hidden", "wait", true)
            .node("show", "ui:element.set-text", "target", "out", "=text", "hidden")
            .link("on_go.then", "play.exec").link("play.then", "wait.exec").link("wait.then", "speed.exec")
            .link("speed.then", "wait2.exec").link("wait2.then", "seek.exec").link("seek.then", "stop.exec")
            .link("stop.then", "hide.exec").link("hide.then", "show.exec")
            .build());
    }

    @Test
    void aGraphDrivesTheSameAnimationAsLua() {
        try (ScriptRig rig = new ScriptRig(graphStage())) {
            assertTrue(rig.rt.diagnostics().isEmpty(), rig.rt.diagnostics().toString());
            rig.click("go");
            rig.frame(0.25);
            assertEquals(50, num(rig, "panel", "translate-x"), 1e-6, "speed 2, as in Lua");
            rig.frame(0.125);
            assertEquals(62.5, num(rig, "panel", "translate-x"), 1e-6);
            rig.frame(0.125);
            assertEquals(100, num(rig, "panel", "translate-x"), 1e-6);
            rig.frame(1.1);
            rig.frame(0);
            assertEquals("hidden", rig.text("out"), "the awaited state change completed");
            assertEquals(0, num(rig, "panel", "opacity"), 1e-6);
        }
    }

    @Test
    void completionsFromAReloadedOrClosedScreenAreDiscarded() {
        String code = """
            function on_open(ui)
              ui.await(ui.play("slide"))
              ui.q("#out"):setText("finished")
            end
            """;
        OmuiArchive doc = ScriptRig.withCode(stage(), code);
        try (ScriptRig rig = new ScriptRig(doc)) {
            rig.frame(0.5);
            rig.reload(doc);
            rig.frame(0.6);
            rig.frame(0);
            assertEquals(1, rig.ui.animator().active(), "on_open ran again after the reload and replays");
            assertEquals("-", rig.text("out"), "the first playback's completion never reached the old task");
            rig.frame(1.0);
            rig.frame(0);
            assertEquals("finished", rig.text("out"), "only the new task resumes");
        }
        ScriptRig closing = new ScriptRig(doc);
        closing.frame(0.5);
        closing.close();
        assertEquals(0, closing.ui.animator().active(), "closing stops every animation");
    }

    @Test
    void styleTransitionsOutliveScriptReloads() {
        OmuiArchive base = stage().withStyle(new UiStyleSheet("s", Map.of(), List.of(), List.of(
            new UiStyleSheet.StyleRule("#panel.dim", Map.of("translate-y", UiValue.of(40)), List.of(
                new UiStyleSheet.StyleTransition("translate-y", 1, UiEasing.LINEAR, 0, Map.of())), Map.of())),
            Map.of()));
        base = base.withDocument(new UiDocument(base.document().root(), List.of("s"), null, null, Map.of()));
        try (ScriptRig rig = new ScriptRig(ScriptRig.withCode(base, "function on_open(ui) end"))) {
            UiElement panel = rig.el("panel");
            panel.addClass("dim");
            rig.layout(); // the transition starts at UI time 0
            rig.frame(0.5);
            rig.rt.reload();
            rig.frame(0.25);
            assertEquals(30, num(rig, "panel", "translate-y"), 1e-6, "a script reload does not touch transitions");
        }
    }
}
