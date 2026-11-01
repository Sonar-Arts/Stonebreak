package com.openmason.engine.ui.runtime.anim;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiEasing;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiPreferences;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.node;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static com.openmason.engine.ui.runtime.UiDocs.sheet;
import static com.openmason.engine.ui.runtime.anim.AnimDocs.frame;
import static com.openmason.engine.ui.runtime.anim.AnimDocs.num;
import static com.openmason.engine.ui.runtime.anim.AnimDocs.rule;
import static com.openmason.engine.ui.runtime.anim.AnimDocs.run;
import static com.openmason.engine.ui.runtime.anim.AnimDocs.shown;
import static com.openmason.engine.ui.runtime.anim.AnimDocs.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Style transitions declared in sheets (#295): any style change triggers them, no script involved. */
class UiStyleTransitionTest {

    /** A button that scales and brightens on hover (transform + colour, no relayout). */
    static OmuiArchive button() {
        return screen("t:ui/btn", box("root").kids(node("play", "Button").cls("btn")),
            sheet("s",
                rule(".btn", List.of(tr("scale", 0.2), tr("background-color", 0.2)),
                    "scale", 1, "background-color", "#202020FF"),
                rule(".btn:hover", List.of(tr("scale", 0.2), tr("background-color", 0.2)),
                    "scale", 1.2, "background-color", "#A0A0A0FF"),
                rule(".btn.big", List.of(tr("all", 0.5)), "translate-x", 10, "width", 300)));
    }

    @Test
    void hoverAnimatesWithNoScriptInvolved() {
        UiDocumentInstance ui = run(button());
        UiElement play = ui.find("play");
        assertEquals(1, num(ui, "play", "scale"), "the first resolve never transitions");
        play.setState(UiElement.HOVER, true); // what the input router does
        ui.resolveStyles();
        assertEquals(1, num(ui, "play", "scale"), 1e-9, "starts from what was shown");
        frame(ui, 0.1);
        assertEquals(1.1, num(ui, "play", "scale"), 1e-6);
        assertEquals(UiValue.of("#606060FF"), shown(ui, "play", "background-color"));
        frame(ui, 0.1);
        assertEquals(1.2, num(ui, "play", "scale"), 1e-6);

        play.setState(UiElement.HOVER, false);
        ui.resolveStyles(); // hosts resolve on render; the transition starts at this clock reading
        frame(ui, 0.05);
        assertEquals(1.15, num(ui, "play", "scale"), 1e-6, "un-hover eases back from the shown 1.2");
        play.setState(UiElement.HOVER, true);
        ui.resolveStyles();
        frame(ui, 0.1);
        assertEquals(1.175, num(ui, "play", "scale"), 1e-6, "re-hover mid-way starts from the current value");
        frame(ui, 0.2);
        assertEquals(1.2, num(ui, "play", "scale"), 1e-6);
        assertEquals(0, ui.animator().activeTransitions(), "finished transitions clear their layer");
        assertNull(play.animatedStyle("scale"));
    }

    @Test
    void classTogglesAndInlineWritesFromCodeTriggerTransitions() {
        UiDocumentInstance ui = run(button());
        UiElement play = ui.find("play");
        play.addClass("big");
        ui.resolveStyles();
        frame(ui, 0.25);
        assertEquals(5, num(ui, "play", "translate-x"), 1e-6, "'all' covers paint properties");
        assertEquals(UiValue.of(300.0), shown(ui, "play", "width"), "'all' never animates layout: it relayouts");

        play.setStyle("background-color", UiValue.of("#FFFFFFFF")); // a script's inline write
        ui.resolveStyles();
        frame(ui, 0.1);
        assertTrue(!UiValue.of("#FFFFFFFF").equals(shown(ui, "play", "background-color")), "eases");
        frame(ui, 0.1);
        assertEquals(UiValue.of("#FFFFFFFF"), shown(ui, "play", "background-color"));
    }

    @Test
    void delayHoldsTheOldValueAndUnsetEndsSettleOnTheCascade() {
        OmuiArchive doc = screen("t:ui/d", box("root").kids(box("panel").cls("p")),
            sheet("s", rule(".p.on", List.of(tr("opacity", 0.2, UiEasing.LINEAR, 0.1)), "opacity", 0.5),
                rule(".p", List.of(tr("opacity", 0.2)))));
        UiDocumentInstance ui = run(doc);
        ui.find("panel").addClass("on");
        ui.resolveStyles();
        frame(ui, 0.05);
        assertEquals(1, num(ui, "panel", "opacity"), 1e-9, "the unset start is the neutral 1, held through the delay");
        frame(ui, 0.15);
        assertEquals(0.75, num(ui, "panel", "opacity"), 1e-9);
        frame(ui, 0.15);
        assertEquals(0.5, num(ui, "panel", "opacity"), 1e-9);

        ui.find("panel").removeClass("on"); // opacity is unset again: transition to neutral, then nothing
        ui.resolveStyles();
        frame(ui, 0.1);
        assertEquals(0.75, num(ui, "panel", "opacity"), 1e-9);
        frame(ui, 0.15);
        assertNull(shown(ui, "panel", "opacity"));
    }

    @Test
    void reducedMotionJumpsMotionAndShortensFades() {
        UiDocumentInstance ui = run(button());
        ui.setPreferences(new UiPreferences(true, 1f));
        ui.find("play").setState(UiElement.HOVER, true);
        ui.resolveStyles();
        assertEquals(1.2, num(ui, "play", "scale"), 1e-9, "motion jumps");
        frame(ui, UiAnimator.REDUCED_FADE_SECONDS / 2);
        assertTrue(!UiValue.of("#A0A0A0FF").equals(shown(ui, "play", "background-color")), "colour still fades");
        frame(ui, UiAnimator.REDUCED_FADE_SECONDS / 2 + 1e-6);
        assertEquals(UiValue.of("#A0A0A0FF"), shown(ui, "play", "background-color"), "within the shortened 0.15 s");
    }

    @Test
    void reducedMotionMarksTheRootSoSheetsCanAuthorAlternates() {
        OmuiArchive doc = screen("t:ui/r", box("root").kids(box("panel").cls("p")),
            sheet("s", rule(".p", List.of(tr("translate-y", 0.3)), "translate-y", 0),
                rule(".sb-reduced-motion .p", List.of(tr("opacity", 0.3)), "opacity", 0.9)));
        UiDocumentInstance ui = run(doc);
        assertNull(shown(ui, "panel", "opacity"));
        ui.setPreferences(new UiPreferences(true, 1f));
        ui.resolveStyles();
        assertTrue(ui.root().hasClass(UiDocumentInstance.REDUCED_MOTION_CLASS));
        frame(ui, 1);
        assertEquals(0.9, num(ui, "panel", "opacity"), 1e-9, "the authored alternate applies");
    }
}
