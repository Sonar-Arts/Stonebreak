package com.openmason.engine.ui.runtime.input;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.has;
import static com.openmason.engine.ui.runtime.UiDocs.node;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Trickle-down / target / bubble-up propagation, stopping and preventing (#288). */
class EventDispatchTest {

    private static OmuiArchive nested() {
        return screen("t:ui/dispatch", box("root").style("width", 300).style("height", 200).kids(
            box("panel").style("width", 200).style("height", 100).kids(
                node("button", "Button").style("width", 100).style("height", 40))));
    }

    @Test
    void trickleDownThenTargetThenBubbleUp() {
        try (InputRig r = new InputRig(nested())) {
            for (String k : List.of("root", "panel", "button")) {
                r.listen(k, EventCallbacks.Phase.TRICKLE_DOWN, UiEventType.CLICK);
                r.listen(k, EventCallbacks.Phase.BUBBLE_UP, UiEventType.CLICK);
            }
            r.click("button");
            assertEquals(List.of(
                "CLICK@root/TRICKLE_DOWN", "CLICK@panel/TRICKLE_DOWN",
                "CLICK@button/AT_TARGET", "CLICK@button/AT_TARGET",
                "CLICK@panel/BUBBLE_UP", "CLICK@root/BUBBLE_UP"), r.log);
        }
    }

    @Test
    void nonBubblingEventsStopAtTheTarget() {
        try (InputRig r = new InputRig(nested())) {
            r.listen("root", EventCallbacks.Phase.TRICKLE_DOWN, UiEventType.FOCUS);
            r.listen("root", EventCallbacks.Phase.BUBBLE_UP, UiEventType.FOCUS, UiEventType.FOCUS_IN);
            r.listen("button", EventCallbacks.Phase.BUBBLE_UP, UiEventType.FOCUS);
            r.click("button");
            assertEquals(List.of("FOCUS_IN@root/BUBBLE_UP", "FOCUS@root/TRICKLE_DOWN", "FOCUS@button/AT_TARGET"),
                r.log, "FOCUS trickles but does not bubble; FOCUS_IN bubbles");
        }
    }

    @Test
    void stopPropagationFinishesTheCurrentElementOnly() {
        try (InputRig r = new InputRig(nested())) {
            r.el("panel").on(UiEventType.CLICK, e -> {
                r.log.add("first");
                e.stopPropagation();
            });
            r.el("panel").on(UiEventType.CLICK, e -> r.log.add("second"));
            r.el("root").on(UiEventType.CLICK, e -> r.log.add("root"));
            r.click("button");
            assertEquals(List.of("first", "second"), r.log);
        }
    }

    @Test
    void stopImmediatePropagationSkipsTheRestOfTheElementToo() {
        try (InputRig r = new InputRig(nested())) {
            r.el("panel").on(UiEventType.CLICK, e -> {
                r.log.add("first");
                e.stopImmediatePropagation();
            });
            r.el("panel").on(UiEventType.CLICK, e -> r.log.add("second"));
            r.click("button");
            assertEquals(List.of("first"), r.log);
        }
    }

    @Test
    void trickleDownCanStopBeforeTheTarget() {
        try (InputRig r = new InputRig(nested())) {
            r.el("root").on(UiEventType.CLICK, UiEvent::stopPropagation, EventCallbacks.Phase.TRICKLE_DOWN);
            r.listen("button", EventCallbacks.Phase.BUBBLE_UP, UiEventType.CLICK);
            r.click("button");
            assertTrue(r.log.isEmpty(), "a parent's trickle-down handler intercepts first");
        }
    }

    @Test
    void aThrowingHandlerIsReportedAndDispatchContinues() {
        try (InputRig r = new InputRig(nested())) {
            r.el("button").on(UiEventType.CLICK, e -> {
                throw new IllegalStateException("boom");
            });
            r.el("root").on(UiEventType.CLICK, e -> r.log.add("root still runs"));
            r.click("button");
            assertEquals(List.of("root still runs"), r.log);
            assertTrue(has(r.ui, UiRuntimeDiagnostic.Code.EVENT_HANDLER_FAILED));
        }
    }

    @Test
    void handlersAddedDuringDispatchApplyToTheNextEvent() {
        try (InputRig r = new InputRig(nested())) {
            List<String> seen = new ArrayList<>();
            UiElement button = r.el("button");
            button.on(UiEventType.CLICK, e -> {
                seen.add("outer");
                button.on(UiEventType.CLICK, e2 -> seen.add("late"));
            });
            r.click("button");
            assertEquals(List.of("outer"), seen);
            r.click("button");
            assertEquals(List.of("outer", "outer", "late"), seen);
        }
    }

    @Test
    void aHandlerRemovingAnAncestorStopsDeliveryToIt() {
        try (InputRig r = new InputRig(nested())) {
            r.el("button").on(UiEventType.CLICK, e -> r.el("panel").remove());
            r.listen("panel", EventCallbacks.Phase.BUBBLE_UP, UiEventType.CLICK);
            r.listen("root", EventCallbacks.Phase.BUBBLE_UP, UiEventType.CLICK);
            r.click("button");
            assertEquals(List.of("CLICK@root/BUBBLE_UP"), r.log, "removed elements on the path are skipped");
        }
    }

    @Test
    void anEventObjectCannotBeDispatchedTwice() {
        try (InputRig r = new InputRig(nested())) {
            EventDispatcher d = r.router.dispatcher();
            TextInputEvent e = new TextInputEvent(0, "x");
            d.dispatch(e, r.el("button"));
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> d.dispatch(e, r.el("root")));
            assertFalse(e.isHandled());
        }
    }
}
