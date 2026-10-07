package com.openmason.engine.ui.runtime.input;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiFeatures;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.node;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hit routing, hover enter/leave, click, capture, wheel and scrollbars over real geometry (#288). */
class PointerRoutingTest {

    private static OmuiArchive twoButtons() {
        return screen("t:ui/pointer", box("root").style("width", 400).style("height", 300).style("picking-mode", "ignore")
            .kids(box("panel").style("width", 200).style("height", 120).style("padding-left", 10).kids(
                node("a", "Button").style("width", 80).style("height", 40),
                node("b", "Button").style("width", 80).style("height", 40))));
    }

    @Test
    void enterAndLeaveFollowTheChainWithoutRefiringParents() {
        try (InputRig r = new InputRig(twoButtons())) {
            for (String k : List.of("panel", "a", "b")) {
                r.listen(k, EventCallbacks.Phase.BUBBLE_UP, UiEventType.POINTER_ENTER, UiEventType.POINTER_LEAVE);
            }
            r.router.pointerMove(r.cx("a"), r.cy("a"));
            assertEquals(List.of("POINTER_ENTER@panel/AT_TARGET", "POINTER_ENTER@a/AT_TARGET"), r.log);
            r.log.clear();
            r.router.pointerMove(r.cx("b"), r.cy("b"));
            assertEquals(List.of("POINTER_LEAVE@a/AT_TARGET", "POINTER_ENTER@b/AT_TARGET"), r.log,
                "moving between siblings never leaves the shared parent");
            r.log.clear();
            r.router.pointerLeave();
            assertEquals(List.of("POINTER_LEAVE@b/AT_TARGET", "POINTER_LEAVE@panel/AT_TARGET"), r.log,
                "leave runs deepest first, enter outermost first");
            assertFalse(r.el("b").hasState("hover"));
        }
    }

    @Test
    void consumptionFollowsTheHitTest() {
        try (InputRig r = new InputRig(twoButtons())) {
            assertTrue(r.router.pointerDown(r.cx("a"), r.cy("a"), PointerEvent.PRIMARY, 0));
            assertTrue(r.router.pointerUp(r.cx("a"), r.cy("a"), PointerEvent.PRIMARY, 0));
            assertFalse(r.router.pointerDown(350, 250, PointerEvent.PRIMARY, 0),
                "the full-screen root ignores picking: a press there belongs to the world");
            assertFalse(r.router.pointerUp(350, 250, PointerEvent.PRIMARY, 0));
            assertTrue(r.router.pointerDown(r.cx("a"), r.cy("a"), PointerEvent.PRIMARY, 0));
            assertTrue(r.router.pointerUp(350, 250, PointerEvent.PRIMARY, 0), "a release ending a UI press is the UI's");
            assertNull(r.router.lastClick(), "pressed on a, released elsewhere: no click");
        }
    }

    @Test
    void disabledElementsBlockButReceiveNothing() {
        try (InputRig r = new InputRig(twoButtons())) {
            r.el("a").setEnabled(false);
            r.frame();
            r.listen("a", EventCallbacks.Phase.BUBBLE_UP, UiEventType.POINTER_DOWN, UiEventType.CLICK,
                UiEventType.POINTER_ENTER);
            r.listen("panel", EventCallbacks.Phase.BUBBLE_UP, UiEventType.POINTER_DOWN, UiEventType.CLICK);
            r.router.pointerMove(r.cx("a"), r.cy("a"));
            assertTrue(r.click("a"), "consumed: nothing below a disabled button is reached");
            assertEquals(List.of(), r.log);
            assertNull(r.router.lastClick());
            assertNull(r.router.focus().focused(), "disabled elements are never focused");
        }
    }

    @Test
    void hiddenCollapsedAndIgnoredElementsLetInputThrough() {
        OmuiArchive doc = screen("t:ui/pass", box("root").style("width", 300).style("height", 200).kids(
            node("under", "Button").style("position", "absolute").style("width", 100).style("height", 50),
            box("ghost").style("position", "absolute").style("width", 100).style("height", 50)
                .style("visibility", "hidden"),
            box("glass").style("position", "absolute").style("width", 100).style("height", 50)
                .style("picking-mode", "ignore")));
        try (InputRig r = new InputRig(doc)) {
            r.router.pointerDown(50, 25, PointerEvent.PRIMARY, 0);
            r.router.pointerUp(50, 25, PointerEvent.PRIMARY, 0);
            assertEquals("under", r.router.lastClick().key(), "hidden and ignore-picking elements pass hits through");
            r.el("under").setStyle("display", com.openmason.engine.format.omui.UiValue.of("none"));
            r.frame();
            r.router.pointerDown(50, 25, PointerEvent.PRIMARY, 0);
            r.router.pointerUp(50, 25, PointerEvent.PRIMARY, 0);
            assertEquals("root", r.router.lastClick().key(), "collapsed elements receive nothing");
        }
    }

    @Test
    void clippedAndScrolledContentIsHitWhereItIsPainted() {
        OmuiArchive doc = screen("t:ui/scroll", box("root").style("width", 300).style("height", 300).kids(
            node("view", "ScrollView").style("width", 200).style("height", 100).kids(
                node("i0", "Button").style("height", 60).style("flex-shrink", 0),
                node("i1", "Button").style("height", 60).style("flex-shrink", 0),
                node("i2", "Button").style("height", 60).style("flex-shrink", 0)),
            node("below", "Button").style("width", 200).style("height", 50)));
        doc = InputRig.withFeatures(doc, UiFeatures.SCROLL);
        try (InputRig r = new InputRig(doc)) {
            float x = 100;
            float clippedY = 130; // i2 lies at y=120..180 in layout, outside the 100 px view
            assertEquals("below", hit(r, x, clippedY), "content clipped by the view is not hit");
            r.el("view").scrollTo(0, 60);
            r.frame();
            assertEquals("i1", hit(r, x, 10), "after scrolling, i1 is under the view's top edge");
            assertEquals("i2", hit(r, x, 70));
            List<String> got = new ArrayList<>();
            r.el("i2").on(UiEventType.CLICK, e -> got.add(e.currentTarget().key()));
            r.router.pointerDown(x, 70, PointerEvent.PRIMARY, 0);
            r.router.pointerUp(x, 70, PointerEvent.PRIMARY, 0);
            assertEquals(List.of("i2"), got);
        }
    }

    @Test
    void wheelScrollsTheInnermostContainerThatCanStillMove() {
        OmuiArchive doc = InputRig.withFeatures(screen("t:ui/wheel", box("root").style("width", 300).style("height", 300).kids(
            node("outer", "ScrollView").style("width", 200).style("height", 150).kids(
                node("inner", "ScrollView").style("height", 100).style("flex-shrink", 0).kids(
                    box("content").style("height", 160).style("flex-shrink", 0)),
                box("tail").style("height", 200).style("flex-shrink", 0)))), UiFeatures.SCROLL);
        InputSettings step20 = InputSettings.DEFAULTS.withWheelStep(20);
        try (InputRig r = new InputRig(doc, 300, 300, step20)) {
            UiElement inner = r.el("inner");
            UiElement outer = r.el("outer");
            assertTrue(r.router.wheel(50, 50, 0, -1, 0));
            r.frame();
            assertEquals(20, inner.scrollY(), 0.01);
            assertEquals(0, outer.scrollY(), 0.01, "the inner view takes the wheel first");
            for (int i = 0; i < 5; i++) {
                r.router.wheel(50, 50, 0, -1, 0);
                r.frame();
            }
            assertEquals(60, inner.scrollY(), 0.01, "clamped at its extent");
            assertTrue(outer.scrollY() > 0, "once the inner view is at its end the outer one scrolls");
        }
    }

    @Test
    void aHandlerCanPreventWheelScrolling() {
        OmuiArchive doc = InputRig.withFeatures(screen("t:ui/wheel2", box("root").style("width", 300).style("height", 300).kids(
            node("view", "ScrollView").style("width", 200).style("height", 100).kids(
                box("content").style("height", 300).style("flex-shrink", 0)))), UiFeatures.SCROLL);
        try (InputRig r = new InputRig(doc)) {
            r.el("view").on(UiEventType.WHEEL, UiEvent::preventDefault);
            assertTrue(r.router.wheel(50, 50, 0, -1, 0));
            r.frame();
            assertEquals(0, r.el("view").scrollY(), 0.01);
        }
    }

    @Test
    void scrollbarThumbDragsTheView() {
        OmuiArchive doc = InputRig.withFeatures(screen("t:ui/bar", box("root").style("width", 300).style("height", 300).kids(
            node("view", "ScrollView").style("width", 200).style("height", 100).kids(
                box("content").style("height", 300).style("flex-shrink", 0)))), UiFeatures.SCROLL);
        try (InputRig r = new InputRig(doc)) {
            UiElement view = r.el("view");
            float barX = view.rect().right() - 2;
            assertTrue(r.router.pointerDown(barX, 5, PointerEvent.PRIMARY, 0));
            assertSame(view, r.router.pointerCapture(), "the bar captures the pointer");
            r.router.pointerMove(barX + 100, 80); // leaving the bar sideways keeps dragging
            r.frame();
            assertTrue(view.scrollY() > 150, "dragging the thumb down scrolls down, got " + view.scrollY());
            r.router.pointerUp(barX + 100, 80, PointerEvent.PRIMARY, 0);
            assertNull(r.router.pointerCapture());
        }
    }

    @Test
    void captureSendsEveryMoveToTheCapturer() {
        try (InputRig r = new InputRig(twoButtons())) {
            List<String> moves = new ArrayList<>();
            r.el("a").on(UiEventType.POINTER_MOVE, e -> moves.add(((PointerEvent) e).target().key()));
            r.el("a").on(UiEventType.POINTER_DOWN, e -> r.router.capturePointer(r.el("a")));
            r.router.pointerDown(r.cx("a"), r.cy("a"), PointerEvent.PRIMARY, 0);
            assertTrue(r.router.pointerMove(r.cx("b"), r.cy("b")));
            assertTrue(r.router.pointerMove(-50, -50), "outside the canvas a capture still receives moves");
            assertEquals(List.of("a", "a"), moves);
            r.router.pointerUp(r.cx("b"), r.cy("b"), PointerEvent.PRIMARY, 0);
            assertNull(r.router.pointerCapture(), "the primary release ends a capture");
        }
    }

    @Test
    void inputOutsideTheCanvasIsNotTheDocuments() {
        try (InputRig r = new InputRig(twoButtons())) {
            assertFalse(r.router.pointerDown(-1, 10, PointerEvent.PRIMARY, 0));
            assertFalse(r.router.pointerDown(400, 10, PointerEvent.PRIMARY, 0), "the right edge is outside");
            assertFalse(r.router.wheel(10, 300, 0, 1, 0));
            r.router.pointerMove(r.cx("a"), r.cy("a"));
            r.router.pointerMove(500, 500);
            assertFalse(r.el("a").hasState("hover"), "leaving the canvas clears hover");
        }
    }

    @Test
    void doubleClicksCountWithinTimeAndDistance() {
        try (InputRig r = new InputRig(twoButtons())) {
            List<Integer> counts = new ArrayList<>();
            r.el("a").on(UiEventType.CLICK, e -> counts.add(((PointerEvent) e).clickCount()));
            r.click("a");
            r.router.tick(0.2);
            r.click("a");
            r.router.tick(1.0);
            r.click("a");
            assertEquals(List.of(1, 2, 1), counts);
        }
    }

    @Test
    void localCoordinatesInvertScrollAndTranslation() {
        OmuiArchive doc = InputRig.withFeatures(screen("t:ui/local", box("root").style("width", 300).style("height", 300).kids(
            node("view", "ScrollView").style("width", 200).style("height", 100).style("left", 10).style("top", 20)
                .style("position", "absolute").kids(
                    box("pad").style("height", 50).style("flex-shrink", 0),
                    node("target", "Button").style("height", 100).style("flex-shrink", 0)
                        .style("translate-x", 5)))), UiFeatures.SCROLL);
        try (InputRig r = new InputRig(doc)) {
            r.el("view").scrollTo(0, 30);
            r.frame();
            UiRect t = r.el("target").rect();
            assertEquals(10 + 5, t.x(), 0.01);
            assertEquals(20 + 50 - 30, t.y(), 0.01);
            float[] local = new float[2];
            r.el("target").on(UiEventType.POINTER_DOWN, e -> {
                local[0] = ((PointerEvent) e).localX();
                local[1] = ((PointerEvent) e).localY();
            });
            r.router.pointerDown(t.x() + 7, t.y() + 9, PointerEvent.PRIMARY, 0);
            assertEquals(7, local[0], 0.01);
            assertEquals(9, local[1], 0.01);
        }
    }

    private static String hit(InputRig r, float x, float y) {
        UiElement e = r.ui.hitTest(x, y);
        return e == null ? null : e.key();
    }
}
