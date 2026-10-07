package com.openmason.engine.ui.runtime.input;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiFeatures;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.masonry.MKeys;
import com.openmason.engine.ui.runtime.UiDocs;
import com.openmason.engine.ui.runtime.UiElement;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.node;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regressions for defects found in review of the #288 router: pairing, re-entrancy, reload, tooltips. */
class ReviewRegressionTest {

    private static UiDocs.N button(String id) {
        return node(id, "Button").style("width", 60).style("height", 30);
    }

    private static OmuiArchive menu() {
        return screen("t:ui/review", box("root").style("width", 400).style("height", 300).style("picking-mode", "ignore")
            .kids(button("a").prop("tooltip", "Attack"), button("b"),
                box("dialog").prop("focusScope", "modal").style("display", "none").kids(button("ok"))));
    }

    @Test
    void aReleaseIsTheUisOnlyWhenItsPressWas() {
        try (InputRig r = new InputRig(menu())) {
            assertFalse(r.router.pointerDown(300, 250, PointerEvent.PRIMARY, 0), "the world took the press");
            assertFalse(r.router.pointerUp(r.cx("a"), r.cy("a"), PointerEvent.PRIMARY, 0),
                "so the world must get the release, or its button stays down");
            assertNull(r.router.lastClick());
        }
    }

    @Test
    void aKeyPressedBeforeAModalOpenedIsReleasedToItsOwner() {
        try (InputRig r = new InputRig(menu())) {
            assertFalse(r.router.keyDown(87, 0, false), "W walks");
            r.el("dialog").setStyle("display", UiValue.of("flex"));
            r.frame();
            assertFalse(r.router.keyDown(87, 0, true), "its repeat is still gameplay's");
            assertFalse(r.router.keyUp(87, 0), "and so is its release");
            assertTrue(r.router.keyDown(87, 0, false), "a new press belongs to the modal");
            assertTrue(r.router.keyUp(87, 0));
        }
    }

    @Test
    void keyboardFocusShowsTheTooltipEvenThoughKeysHideHover() {
        try (InputRig r = new InputRig(menu())) {
            r.press(MKeys.KEY_TAB);
            assertEquals("a", r.focusKey());
            r.router.tick(0.01);
            assertNotNull(r.router.tooltips().current(), "focus tooltip");
            assertEquals("Attack", r.router.tooltips().current().text());
            assertTrue(r.router.tooltips().current().fromFocus());
            r.press(MKeys.KEY_TAB);
            r.router.tick(0.01);
            assertNull(r.router.tooltips().current(), "b has no tooltip");
        }
    }

    @Test
    void aHoverTooltipWaitsForItsDelayAndAPressHidesIt() {
        try (InputRig r = new InputRig(menu())) {
            r.router.pointerMove(r.cx("a"), r.cy("a"));
            r.router.tick(0.3);
            assertNull(r.router.tooltips().current());
            r.router.tick(0.3);
            assertEquals("Attack", r.router.tooltips().current().text());
            r.router.pointerDown(r.cx("a"), r.cy("a"), PointerEvent.PRIMARY, 0);
            r.router.tick(1);
            assertTrue(r.router.tooltips().current() == null || r.router.tooltips().current().fromFocus(),
                "no hover tooltip after a press until the pointer moves to another element");
        }
    }

    @Test
    void aPreventedChildDismissKeepsItsParentPopupOpen() {
        OmuiArchive doc = screen("t:ui/sub", box("root").style("width", 400).style("height", 300).kids(
            button("outside"),
            box("menu").prop("focusScope", "popup").style("display", "none").kids(button("m1"),
                box("sub").prop("focusScope", "popup").style("display", "none").kids(button("s1")))));
        try (InputRig r = new InputRig(doc)) {
            r.el("menu").setStyle("display", UiValue.of("flex"));
            r.frame();
            r.el("sub").setStyle("display", UiValue.of("flex"));
            r.frame();
            r.el("sub").on(UiEventType.DISMISS, UiEvent::preventDefault);
            assertTrue(r.click("outside"), "the press is still consumed");
            assertTrue(r.el("sub").isVisible());
            assertTrue(r.el("menu").isVisible(), "the parent stays open under a submenu that refused");
        }
    }

    private static OmuiArchive slots() {
        return screen("t:ui/drag2", box("root").style("width", 400).style("height", 300).style("flex-direction", "row")
            .kids(node("from", "ItemSlot").prop("draggable", true).style("width", 50).style("height", 50),
                box("gap").style("width", 100), node("to", "ItemSlot").style("width", 50).style("height", 50)));
    }

    @Test
    void aHandlerThatCancelsTheDragMidNegotiationEndsItOnce() {
        try (InputRig r = new InputRig(slots())) {
            List<String> ends = new ArrayList<>();
            r.el("from").on(UiEventType.DRAG_START, e -> ((DragEvent) e).setPayload("x"));
            r.el("from").on(UiEventType.DRAG_END, e -> ends.add(String.valueOf(((DragEvent) e).session().cancelReason())));
            r.el("to").on(UiEventType.DRAG_ENTER, e -> r.router.cancelInteractions(CancelReason.DISCONNECT));
            r.router.pointerDown(r.cx("from"), r.cy("from"), PointerEvent.PRIMARY, 0);
            r.router.pointerMove(r.cx("gap"), r.cy("gap"));
            r.router.pointerMove(r.cx("to"), r.cy("to"));
            r.router.pointerUp(r.cx("to"), r.cy("to"), PointerEvent.PRIMARY, 0);
            assertEquals(List.of("DISCONNECT"), ends);
        }
    }

    @Test
    void aDropHandlerThatThrowsLeavesThePayloadWithItsSource() {
        try (InputRig r = new InputRig(slots())) {
            List<String> ends = new ArrayList<>();
            r.el("from").on(UiEventType.DRAG_START, e -> ((DragEvent) e).setPayload("x"));
            r.el("from").on(UiEventType.DRAG_END, e -> ends.add(((DragEvent) e).session().outcome() + ":"
                + ((DragEvent) e).session().cancelReason()));
            r.el("to").on(UiEventType.DRAG_OVER, e -> ((DragEvent) e).acceptDrop());
            r.el("to").on(UiEventType.DRAG_DROP, e -> {
                throw new IllegalStateException("server said no");
            });
            r.router.pointerDown(r.cx("from"), r.cy("from"), PointerEvent.PRIMARY, 0);
            r.router.pointerMove(r.cx("to"), r.cy("to"));
            r.router.pointerUp(r.cx("to"), r.cy("to"), PointerEvent.PRIMARY, 0);
            assertEquals(List.of("CANCELLED:REJECTED"), ends);
        }
    }

    private static OmuiArchive field() {
        return InputRig.withFeatures(screen("t:ui/f", box("root").style("width", 400).style("height", 300).kids(
            node("name", "TextField").prop("text", "two\nlines").style("width", 200).style("height", 30))),
            UiFeatures.INPUT);
    }

    @Test
    void aValueThatNormalizesDifferentlyDoesNotResetTheCaretEveryFrame() {
        try (InputRig r = new InputRig(field())) {
            r.click("name");
            TextFieldController tf = r.router.textField(r.el("name"));
            r.press(MKeys.KEY_HOME);
            r.frame();
            r.frame();
            assertEquals(0, tf.model().caret(), "the caret stays where the player put it");
        }
    }

    @Test
    void liveReloadKeepsAFocusedFieldEditable() {
        OmuiArchive doc = field();
        try (InputRig r = new InputRig(doc)) {
            r.click("name");
            r.ui.reload(doc);
            r.frame();
            r.press(MKeys.KEY_ESCAPE);
            assertEquals("two\nlines", r.el("name").text("text"), "Escape must not erase the field after a reload");
            assertTrue(r.router.textField(r.el("name")).focused());
        }
    }

    @Test
    void reloadingMidPressLeavesNoStuckActiveState() {
        OmuiArchive doc = menu();
        try (InputRig r = new InputRig(doc)) {
            r.router.pointerDown(r.cx("a"), r.cy("a"), PointerEvent.PRIMARY, 0);
            r.ui.reload(doc);
            r.frame();
            r.router.pointerUp(r.cx("a"), r.cy("a"), PointerEvent.PRIMARY, 0);
            r.frame();
            UiElement a = r.el("a");
            assertFalse(a.hasState(UiElement.ACTIVE));
            r.router.pointerMove(350, 250);
            r.frame();
            assertFalse(a.hasState(UiElement.HOVER), "and no stuck hover either");
        }
    }

    @Test
    void bindingTyposNeverUnbindOrInventChords() {
        UiActionMap m = UiActionMap.fromWire(Map.of(
            "ui.submit", List.of("key:69+shitf"),
            "ui.cancel", List.of("garbage", "key:x")));
        assertEquals(UiActionMap.defaults().keys(UiAction.SUBMIT), m.keys(UiAction.SUBMIT),
            "an unreadable entry keeps the default");
        assertNull(m.actionForKey(69, 0));
        assertEquals(UiAction.CANCEL, m.actionForKey(MKeys.KEY_ESCAPE, 0), "cancel stays reachable");
    }
}
