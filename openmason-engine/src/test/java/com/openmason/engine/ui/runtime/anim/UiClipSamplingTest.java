package com.openmason.engine.ui.runtime.anim;

import com.openmason.engine.format.oma.Easing;
import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiAnimationClip;
import com.openmason.engine.format.omui.UiAnimationClip.LoopMode;
import com.openmason.engine.format.omui.UiEasing;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiPreferences;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.has;
import static com.openmason.engine.ui.runtime.UiDocs.label;
import static com.openmason.engine.ui.runtime.UiDocs.node;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static com.openmason.engine.ui.runtime.anim.AnimDocs.clip;
import static com.openmason.engine.ui.runtime.anim.AnimDocs.frame;
import static com.openmason.engine.ui.runtime.anim.AnimDocs.key;
import static com.openmason.engine.ui.runtime.anim.AnimDocs.num;
import static com.openmason.engine.ui.runtime.anim.AnimDocs.run;
import static com.openmason.engine.ui.runtime.anim.AnimDocs.shown;
import static com.openmason.engine.ui.runtime.anim.AnimDocs.track;
import static com.openmason.engine.ui.runtime.anim.AnimDocs.withEvents;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deterministic keyframe sampling, easing, loop modes, logical property types and events (#295). */
class UiClipSamplingTest {

    private static final UnaryOperator<String> SCREEN = k -> k;

    static OmuiArchive panelDoc() {
        return screen("t:ui/anim", box("root").kids(
            box("panel").style("opacity", 1).style("width", "50%"),
            label("title", "A"),
            node("icon", "Image").prop("source", "sheet#a")));
    }

    /** opacity 0 → 1 (ease-out) over 1 s, translate-y 20 → 0, a colour, a percentage width and a flipbook. */
    static UiAnimationClip intro() {
        return clip("intro", 1, LoopMode.ONCE,
            track("panel", "style:opacity", key(0, 0, UiEasing.EASE_OUT), key(1, 1)),
            track("panel", "style:translate-y", key(0, 20), key(1, 0)),
            track("panel", "style:background-color", key(0, "#000000FF"), key(1, "#FF8000FF")),
            track("panel", "style:width", key(0, "0%"), key(1, "100%")),
            track("icon", "prop:source", key(0, "sheet#a"), key(0.5, "sheet#b"), key(0.75, "sheet#c")),
            track("title", "style:visibility", key(0, "hidden"), key(0.5, "visible")));
    }

    @Test
    void keysInterpolateByLogicalTypeAtFixedTimes() {
        UiDocumentInstance ui = run(panelDoc());
        ui.animator().play(intro(), SCREEN, 1, null, UiAnimator.Listener.NONE);
        ui.resolveStyles();
        assertEquals(0.0, num(ui, "panel", "opacity"), "first frame shows at once");
        assertEquals(20.0, num(ui, "panel", "translate-y"));

        frame(ui, 0.25);
        assertEquals(Easing.EASE_OUT.apply(0.25f), num(ui, "panel", "opacity"), 1e-6);
        assertEquals(15.0, num(ui, "panel", "translate-y"), 1e-9);
        assertEquals(UiValue.of("#402000FF"), shown(ui, "panel", "background-color"), "per-channel lerp, rounded");
        assertEquals(UiValue.of("25%"), shown(ui, "panel", "width"), "percent lengths lerp as percent");
        assertEquals(UiValue.of("sheet#a"), ui.find("icon").prop("source"), "sprite frames switch at their key");
        assertEquals(UiValue.of("visible"), shown(ui, "title", "visibility"), "visible for the whole transition");

        frame(ui, 0.25);
        assertEquals(UiValue.of("sheet#b"), ui.find("icon").prop("source"));
        frame(ui, 0.3);
        assertEquals(UiValue.of("sheet#c"), ui.find("icon").prop("source"));
        frame(ui, 0.3);
        assertEquals(1.0, num(ui, "panel", "opacity"));
        assertEquals(UiValue.of("#FF8000FF"), shown(ui, "panel", "background-color"));
        assertEquals(0, ui.animator().active(), "a one-shot clip ends");
        assertEquals(1.0, num(ui, "panel", "opacity"), "and holds its last values");
    }

    @Test
    void samplingDependsOnlyOnTheClockReadingNotOnFrameSteps() {
        // The preview and the game step their clocks differently; the same reading shows the same frame.
        // (Binary-fraction steps, so every host reaches exactly 0.625 s.)
        double[] fine = sample(1 / 128.0, 0.625);
        double[] coarse = sample(1 / 32.0, 0.625);
        double[] once = sample(0.625, 0.625);
        assertEquals(0.625, fine[2], 0);
        assertEquals(fine[0], coarse[0], 0);
        assertEquals(fine[0], once[0], 0);
        assertEquals(fine[1], coarse[1], 0);
        assertEquals(fine[1], once[1], 0);
    }

    private static double[] sample(double step, double until) {
        UiDocumentInstance ui = run(panelDoc());
        ui.animator().play(intro(), SCREEN, 1, null, UiAnimator.Listener.NONE);
        int n = (int) Math.round(until / step);
        for (int i = 0; i < n; i++) {
            ui.clocks().advance(UiClocks.UI, step);
        }
        frame(ui, 0); // one sample at the final reading
        double t = ui.clock();
        return new double[]{num(ui, "panel", "opacity"), num(ui, "panel", "translate-y"), t};
    }

    @Test
    void everyEasingFollowsTheEngineCurve() {
        for (UiEasing e : UiEasing.values()) {
            UiDocumentInstance ui = run(panelDoc());
            ui.animator().play(clip("e", 1, LoopMode.ONCE, track("panel", "style:translate-x", key(0, 0, e), key(1, 100))),
                SCREEN, 1, null, UiAnimator.Listener.NONE);
            for (double t : new double[]{0.1, 0.4, 0.7}) {
                frame(ui, t - ui.clock());
                assertEquals(100 * e.curve().apply((float) t), num(ui, "panel", "translate-x"), 1e-4, e + " at " + t);
            }
        }
    }

    @Test
    void loopAndPingPongWrapClipTime() {
        UiAnimationClip c = clip("l", 1, LoopMode.LOOP, track("panel", "style:translate-x", key(0, 0), key(1, 10)));
        UiDocumentInstance ui = run(panelDoc());
        ui.animator().play(c, SCREEN, 1, null, UiAnimator.Listener.NONE);
        frame(ui, 1.25);
        assertEquals(2.5, num(ui, "panel", "translate-x"), 1e-9);
        UiDocumentInstance pp = run(panelDoc());
        pp.animator().play(c, SCREEN, 1, LoopMode.PING_PONG, UiAnimator.Listener.NONE);
        frame(pp, 1.25);
        assertEquals(7.5, num(pp, "panel", "translate-x"), 1e-9, "reflects on the way back");
        assertEquals(1, pp.animator().active(), "loops never end on their own");
    }

    @Test
    void eventsFireOnceWhenCrossedIncludingAcrossALoopWrap() {
        UiAnimationClip c = withEvents(clip("ev", 1, LoopMode.LOOP,
            track("panel", "style:translate-x", key(0, 0), key(1, 10))), 0.0, "start", 0.5, "mid");
        UiDocumentInstance ui = run(panelDoc());
        AnimDocs.Log log = new AnimDocs.Log();
        ui.animator().play(c, SCREEN, 1, null, log);
        assertEquals(List.of("event:start"), log.lines, "time-0 events fire on the first sample");
        frame(ui, 0.6);
        frame(ui, 0.6); // wraps to 0.2: "start" is crossed again, "mid" not yet
        assertEquals(List.of("event:start", "event:mid", "event:start"), log.lines);
    }

    @Test
    void speedChangesAndSeeksRebaseWithoutJumps() {
        UiDocumentInstance ui = run(panelDoc());
        long t = ui.animator().play(clip("s", 4, LoopMode.ONCE,
            track("panel", "style:translate-x", key(0, 0), key(4, 40))), SCREEN, 1, null, UiAnimator.Listener.NONE);
        frame(ui, 1);
        ui.animator().setSpeed(t, 2);
        frame(ui, 0);
        assertEquals(10, num(ui, "panel", "translate-x"), 1e-9, "no jump at the speed change");
        frame(ui, 0.5);
        assertEquals(20, num(ui, "panel", "translate-x"), 1e-9);
        ui.animator().setSpeed(t, 0);
        frame(ui, 5);
        assertEquals(20, num(ui, "panel", "translate-x"), 1e-9, "speed 0 pauses");
        ui.animator().seek(t, 3);
        ui.resolveStyles();
        assertEquals(30, num(ui, "panel", "translate-x"), 1e-9);
    }

    @Test
    void tracksThatCannotPlayAreReportedAndSkipped() {
        UiDocumentInstance ui = run(panelDoc());
        UiAnimationClip c = clip("bad", 1, LoopMode.ONCE,
            track("ghost", "style:opacity", key(0, 0), key(1, 1)),
            track("panel", "style:opacity", key(0, 0), key(1, 7)),
            track("title", "prop:nonsense", key(0, 1)),
            track("panel", "style:translate-x", key(0, 0), key(1, 10)));
        ui.animator().play(c, SCREEN, 1, null, UiAnimator.Listener.NONE);
        frame(ui, 0.5);
        assertTrue(has(ui, UiRuntimeDiagnostic.Code.ANIMATION_TRACK));
        assertEquals(3, ui.diagnostics().stream().filter(d -> d.code() == UiRuntimeDiagnostic.Code.ANIMATION_TRACK)
            .count(), ui.diagnostics().toString());
        assertEquals(5, num(ui, "panel", "translate-x"), 1e-9, "the valid track still plays");
        assertEquals(1, num(ui, "panel", "opacity"), "the unfit one leaves the cascade alone");
    }

    @Test
    void reducedMotionJumpsOneShotsToTheEndAndHoldsLoopsOnTheFirstFrame() {
        UiDocumentInstance ui = run(panelDoc());
        ui.setPreferences(new UiPreferences(true, 1f));
        AnimDocs.Log log = new AnimDocs.Log();
        ui.animator().play(withEvents(intro(), 1.0, "end"), SCREEN, 1, null, log);
        ui.resolveStyles();
        assertEquals(1.0, num(ui, "panel", "opacity"));
        assertEquals(List.of("event:end", "done"), log.lines, "events still fire");
        UiDocumentInstance loop = run(panelDoc());
        loop.setPreferences(new UiPreferences(true, 1f));
        loop.animator().play(clip("l", 1, LoopMode.LOOP, track("panel", "style:translate-x", key(0, 3), key(1, 10))),
            SCREEN, 1, null, UiAnimator.Listener.NONE);
        frame(loop, 0.5);
        assertEquals(3, num(loop, "panel", "translate-x"));
    }

    @Test
    void varKeysResolveAgainstTheElement() {
        UiDocumentInstance ui = run(screen("t:ui/v", box("root").style("--accent", "#00FF00FF").kids(box("panel"))));
        ui.animator().play(clip("v", 1, LoopMode.ONCE,
                track("panel", "style:color", key(0, "#000000FF"), key(1, "var(--accent)"))),
            SCREEN, 1, null, UiAnimator.Listener.NONE);
        frame(ui, 1);
        assertEquals(UiValue.of("#00FF00FF"), shown(ui, "panel", "color"));
        List<String> none = new ArrayList<>();
        ui.diagnostics().forEach(d -> none.add(d.toString()));
        assertTrue(none.isEmpty(), none.toString());
    }
}
