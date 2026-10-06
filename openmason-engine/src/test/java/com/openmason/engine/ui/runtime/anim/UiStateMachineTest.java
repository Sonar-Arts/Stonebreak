package com.openmason.engine.ui.runtime.anim;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiAnimationClip.LoopMode;
import com.openmason.engine.format.omui.UiStateMachine;
import com.openmason.engine.format.omui.UiStateMachine.Driver;
import com.openmason.engine.format.omui.UiStateMachine.MachineState;
import com.openmason.engine.format.omui.UiStateMachine.MachineTransition;
import com.openmason.engine.ui.runtime.UiDocs;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiDocumentSource;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiPreferences;
import com.openmason.engine.ui.runtime.UiRuntimeContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.inst;
import static com.openmason.engine.ui.runtime.UiDocs.node;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static com.openmason.engine.ui.runtime.anim.AnimDocs.clip;
import static com.openmason.engine.ui.runtime.anim.AnimDocs.frame;
import static com.openmason.engine.ui.runtime.anim.AnimDocs.key;
import static com.openmason.engine.ui.runtime.anim.AnimDocs.num;
import static com.openmason.engine.ui.runtime.anim.AnimDocs.track;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Named UI state machines (#295): interaction states per component instance and manual screen states. */
class UiStateMachineTest {

    static final String BUTTON = "t:ui/fancy_button";

    /** A component whose frame grows on hover, shrinks when pressed and fades when disabled. */
    static OmuiArchive fancyButton() {
        UiStateMachine m = new UiStateMachine("look", Driver.INTERACTION, "frame", "normal", List.of(
            new MachineState("normal", null, Map.of()),
            new MachineState("hover", "hovered", Map.of()),
            new MachineState("pressed", "pressed", Map.of()),
            new MachineState("disabled", "off", Map.of())), List.of(
            new MachineTransition("normal", "hover", "hover_in", 0, null, Map.of())), Map.of());
        return UiDocs.component(BUTTON, node("frame", "Button"), UiDocs.contract(List.of(), List.of()))
            .withAnimation(clip("hover_in", 0.2, LoopMode.ONCE, track("frame", "style:scale", key(0, 1), key(0.2, 1.1))))
            .withAnimation(clip("hovered", 1, LoopMode.LOOP, track("frame", "style:scale", key(0, 1.1), key(1, 1.1))))
            .withAnimation(clip("pressed", 0.1, LoopMode.ONCE, track("frame", "style:scale", key(0, 0.95))))
            .withAnimation(clip("off", 0.1, LoopMode.ONCE, track("frame", "style:opacity", key(0, 0.5))))
            .withStateMachine(m);
    }

    static UiRuntimeContext withButton() {
        return UiRuntimeContext.basic().withSource(UiDocumentSource.of(Map.of(BUTTON, fancyButton()), Map.of()));
    }

    private static UiDocumentInstance twoButtons() {
        UiDocumentInstance ui = UiDocumentInstance.instantiate(screen("t:ui/menu", box("root").kids(
            inst("a", BUTTON, Map.of()), inst("b", BUTTON, Map.of()))), withButton());
        frame(ui, 0);
        return ui;
    }

    @Test
    void interactionMachinesFollowPseudoStatesPerComponentInstance() {
        UiDocumentInstance ui = twoButtons();
        assertEquals("normal", ui.stateMachines().state("a", "look"));
        UiElement a = ui.find("a/frame");
        a.setState(UiElement.HOVER, true);
        frame(ui, 0.1);
        assertEquals("hover", ui.stateMachines().state("a", "look"));
        assertEquals("normal", ui.stateMachines().state("b", "look"), "instances are independent");
        frame(ui, 0.1);
        assertEquals(1.05, num(ui, "a/frame", "scale"), 1e-6, "the transition clip started at the frame's poll");
        frame(ui, 0.1);
        assertEquals(1.1, num(ui, "a/frame", "scale"), 1e-6, "transition clip, then the state's loop");
        assertTrue(Double.isNaN(num(ui, "b/frame", "scale")));

        a.setState(UiElement.ACTIVE, true);
        frame(ui, 0.05);
        assertEquals("pressed", ui.stateMachines().state("a", "look"), "pressed outranks hover");
        assertEquals(0.95, num(ui, "a/frame", "scale"), 1e-6);
        a.setEnabled(false);
        frame(ui, 0.05);
        assertEquals("disabled", ui.stateMachines().state("a", "look"), "disabled outranks everything");
        assertEquals(0.5, num(ui, "a/frame", "opacity"), 1e-6);
        assertTrue(Double.isNaN(num(ui, "a/frame", "scale")), "what the new clip does not pose goes back");

        a.setEnabled(true);
        a.setState(UiElement.ACTIVE, false);
        a.setState(UiElement.HOVER, false);
        frame(ui, 0.05);
        assertEquals("normal", ui.stateMachines().state("a", "look"));
        assertTrue(Double.isNaN(num(ui, "a/frame", "opacity")), "normal has no clip: the cascade again");
    }

    /** A screen with a manual open/closed machine and a reduced-motion alternate. */
    static OmuiArchive menuScreen() {
        UiStateMachine m = new UiStateMachine("screen", Driver.MANUAL, null, "closed", List.of(
            new MachineState("closed", "closed_pose", Map.of()),
            new MachineState("open", null, Map.of())), List.of(
            new MachineTransition(UiStateMachine.ANY, "open", "open_in", 0, "open_fade", Map.of()),
            new MachineTransition("open", "closed", null, 0.2, null, Map.of())), Map.of());
        return screen("t:ui/menu2", box("root").kids(box("panel")))
            .withAnimation(clip("closed_pose", 0.1, LoopMode.ONCE,
                track("panel", "style:opacity", key(0, 0)), track("panel", "style:translate-y", key(0, 40))))
            .withAnimation(clip("open_in", 0.4, LoopMode.ONCE,
                track("panel", "style:opacity", key(0, 0), key(0.4, 1)),
                track("panel", "style:translate-y", key(0, 40), key(0.4, 0))))
            .withAnimation(clip("open_fade", 0.1, LoopMode.ONCE, track("panel", "style:opacity", key(0, 0), key(0.1, 1))))
            .withStateMachine(m);
    }

    @Test
    void manualMachinesPlayTheirTransitionThenTheirStateAndReportArrival() {
        UiDocumentInstance ui = UiDocumentInstance.instantiate(menuScreen(), UiRuntimeContext.basic());
        frame(ui, 0);
        assertEquals("closed", ui.stateMachines().state("", "screen"));
        assertEquals(0, num(ui, "panel", "opacity"), 1e-9, "the initial state's clip poses at once");
        AnimDocs.Log log = new AnimDocs.Log();
        long t = ui.stateMachines().set("", "screen", "open", log);
        assertTrue(t != 0);
        frame(ui, 0.2);
        assertEquals(0.5, num(ui, "panel", "opacity"), 1e-6);
        assertEquals(20, num(ui, "panel", "translate-y"), 1e-6);
        frame(ui, 0.2);
        assertEquals(List.of("done"), log.lines, "arrived");
        frame(ui, 0);
        assertTrue(Double.isNaN(num(ui, "panel", "opacity")), "'open' has no clip: back to the cascade");

        assertEquals(0, ui.stateMachines().set("", "screen", "open", null), "already there");
        assertThrows(IllegalArgumentException.class, () -> ui.stateMachines().set("", "screen", "ajar", null));
        assertThrows(IllegalArgumentException.class, () -> ui.stateMachines().set("", "nope", "open", null));

        ui.stateMachines().set("", "screen", "closed", null); // blend 0.2 into the closed pose
        frame(ui, 0.1);
        double mid = num(ui, "panel", "translate-y");
        assertTrue(mid > 0 && mid < 40, "cross-fading into the pose: " + mid);
    }

    @Test
    void reducedMotionTakesTheDeclaredAlternateAndNeverSlides() {
        UiDocumentInstance ui = UiDocumentInstance.instantiate(menuScreen(), UiRuntimeContext.basic());
        ui.setPreferences(new UiPreferences(true, 1f));
        frame(ui, 0);
        AnimDocs.Log log = new AnimDocs.Log();
        ui.stateMachines().set("", "screen", "open", log);
        frame(ui, 0);
        assertEquals(List.of("done"), log.lines, "the fade alternate ends at once under reduced motion");
        assertTrue(Double.isNaN(num(ui, "panel", "translate-y")), "no slide ever shows");
        assertEquals("open", ui.stateMachines().state("", "screen"));
    }

    @Test
    void closingTheInstanceStopsItsMachines() {
        UiDocumentInstance ui = twoButtons();
        ui.find("a/frame").setState(UiElement.HOVER, true);
        frame(ui, 0.05);
        ui.close();
        assertEquals(0, ui.animator().active());
        assertNull(ui.stateMachines().state("a", "look"));
    }
}
