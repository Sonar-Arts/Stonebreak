package com.openmason.engine.ui.runtime.input;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.masonry.MKeys;
import com.openmason.engine.ui.runtime.UiElement;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.node;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drag and drop negotiation and every cancellation path (#288): no lost items, exactly one
 * {@code DRAG_END}, never a drop after a cancel.
 */
class DragDropTest {

    /** Two slots holding item counts: the "inventory" a drag must never lose items from. */
    private static final class Slots {
        final Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        final List<String> ends = new ArrayList<>();
        final List<DragSession> sessions = new ArrayList<>();
        int drops;

        int total() {
            return counts.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    private static OmuiArchive slots() {
        return screen("t:ui/drag", box("root").style("width", 400).style("height", 300).style("flex-direction", "row")
            .kids(node("from", "ItemSlot").prop("draggable", true).style("width", 50).style("height", 50),
                box("gap").style("width", 100),
                node("to", "ItemSlot").style("width", 50).style("height", 50)));
    }

    /** Wires the slots: "from" offers its stack, "to" accepts and moves it on drop. */
    private static Slots wire(InputRig r) {
        Slots s = new Slots();
        s.counts.put("from", 5);
        s.counts.put("to", 0);
        r.el("from").on(UiEventType.DRAG_START, e -> ((DragEvent) e).setPayload("from"));
        r.el("to").on(UiEventType.DRAG_OVER, e -> ((DragEvent) e).acceptDrop());
        r.el("to").on(UiEventType.DRAG_DROP, e -> {
            DragEvent d = (DragEvent) e;
            s.sessions.add(d.session());
            s.drops++;
            s.counts.merge("to", s.counts.get(d.payload()), Integer::sum);
            s.counts.put((String) d.payload(), 0);
        });
        Consumer<UiEvent> end = e -> s.ends.add(((DragEvent) e).session().outcome() + ":"
            + ((DragEvent) e).session().cancelReason());
        r.el("from").on(UiEventType.DRAG_END, end::accept);
        r.el("root").on(UiEventType.DRAG_END, e -> {
            if (e.target() == r.el("root")) {
                end.accept(e);
            }
        });
        return s;
    }

    private static void startOverTarget(InputRig r) {
        r.router.pointerDown(r.cx("from"), r.cy("from"), PointerEvent.PRIMARY, 0);
        r.router.pointerMove(r.cx("from") + 20, r.cy("from"));
        r.router.pointerMove(r.cx("to"), r.cy("to"));
    }

    @Test
    void aDropMovesTheStackOnce() {
        try (InputRig r = new InputRig(slots())) {
            Slots s = wire(r);
            startOverTarget(r);
            DragSession session = r.router.drag().session();
            assertTrue(session.isLive());
            assertEquals(r.el("to"), session.acceptor());
            assertTrue(r.router.pointerUp(r.cx("to"), r.cy("to"), PointerEvent.PRIMARY, 0));
            assertEquals(Map.of("from", 0, "to", 5), s.counts);
            assertEquals(List.of("DROPPED:null"), s.ends);
            assertFalse(session.isLive());
            assertEquals(r.el("to"), session.dropTarget());
            assertNull(r.router.drag().session());
            assertNull(r.router.lastClick(), "a drag is not a click");
        }
    }

    @Test
    void aSmallWiggleIsStillAClick() {
        try (InputRig r = new InputRig(slots())) {
            Slots s = wire(r);
            r.router.pointerDown(r.cx("from"), r.cy("from"), PointerEvent.PRIMARY, 0);
            r.router.pointerMove(r.cx("from") + 3, r.cy("from"));
            r.router.pointerUp(r.cx("from") + 3, r.cy("from"), PointerEvent.PRIMARY, 0);
            assertNull(r.router.drag().session());
            assertEquals(r.el("from"), r.router.lastClick());
            assertTrue(s.ends.isEmpty());
        }
    }

    @Test
    void droppingWhereNothingAcceptsReturnsTheItem() {
        try (InputRig r = new InputRig(slots())) {
            Slots s = wire(r);
            r.router.pointerDown(r.cx("from"), r.cy("from"), PointerEvent.PRIMARY, 0);
            r.router.pointerMove(r.cx("gap"), r.cy("gap"));
            r.router.pointerUp(r.cx("gap"), r.cy("gap"), PointerEvent.PRIMARY, 0);
            assertEquals(5, s.counts.get("from"));
            assertEquals(List.of("CANCELLED:REJECTED"), s.ends);
        }
    }

    @Test
    void aTargetCanRejectInItsDropHandler() {
        try (InputRig r = new InputRig(slots())) {
            Slots s = wire(r);
            r.el("to").on(UiEventType.DRAG_DROP, UiEvent::preventDefault, EventCallbacks.Phase.TRICKLE_DOWN);
            r.el("to").on(UiEventType.DRAG_DROP, UiEvent::stopImmediatePropagation, EventCallbacks.Phase.TRICKLE_DOWN);
            startOverTarget(r);
            r.router.pointerUp(r.cx("to"), r.cy("to"), PointerEvent.PRIMARY, 0);
            assertEquals(5, s.counts.get("from"));
            assertEquals(List.of("CANCELLED:REJECTED"), s.ends);
        }
    }

    /** Every cancellation: same guarantees. */
    @Test
    void everyCancellationEndsOnceWithoutAStaleDrop() {
        Map<CancelReason, Consumer<InputRig>> causes = new EnumMap<>(CancelReason.class);
        causes.put(CancelReason.ESCAPE, r -> r.router.keyDown(MKeys.KEY_ESCAPE, 0, false));
        causes.put(CancelReason.TARGET_REMOVED, r -> {
            r.el("to").remove();
            r.frame();
        });
        causes.put(CancelReason.SOURCE_REMOVED, r -> {
            r.el("from").setStyle("display", UiValue.of("none"));
            r.frame();
        });
        causes.put(CancelReason.DISCONNECT, r -> r.router.cancelInteractions(CancelReason.DISCONNECT));
        causes.put(CancelReason.WINDOW_FOCUS_LOST, r -> r.router.windowFocusLost());
        causes.put(CancelReason.SCREEN_CLOSED, r -> r.router.screenClosed());
        for (Map.Entry<CancelReason, Consumer<InputRig>> cause : causes.entrySet()) {
            try (InputRig r = new InputRig(slots())) {
                Slots s = wire(r);
                startOverTarget(r);
                DragSession session = r.router.drag().session();
                cause.getValue().accept(r);
                String why = cause.getKey().toString();
                assertFalse(session.isLive(), why);
                assertEquals(cause.getKey(), session.cancelReason(), why);
                assertEquals(1, s.ends.size(), why + ": exactly one DRAG_END " + s.ends);
                // The release that follows must not drop anything.
                r.router.pointerUp(175, 25, PointerEvent.PRIMARY, 0);
                assertEquals(0, s.drops, why + ": no drop after a cancel");
                assertEquals(5, s.total(), why + ": no item lost");
                assertEquals(1, s.ends.size(), why);
            }
        }
    }

    @Test
    void escapeDuringADragIsConsumedAndDoesNotReachTheHost() {
        try (InputRig r = new InputRig(slots())) {
            wire(r);
            startOverTarget(r);
            assertTrue(r.router.keyDown(MKeys.KEY_ESCAPE, 0, false));
            assertTrue(r.router.keyUp(MKeys.KEY_ESCAPE, 0));
        }
    }

    @Test
    void enterAndLeaveTrackTheDraggedPayload() {
        try (InputRig r = new InputRig(slots())) {
            wire(r);
            List<String> log = new ArrayList<>();
            r.el("to").on(UiEventType.DRAG_ENTER, e -> log.add("enter"));
            r.el("to").on(UiEventType.DRAG_LEAVE, e -> log.add("leave"));
            startOverTarget(r);
            r.router.pointerMove(r.cx("gap"), r.cy("gap"));
            assertNull(r.router.drag().session().acceptor(), "leaving the target withdraws its acceptance");
            r.router.pointerMove(r.cx("to"), r.cy("to"));
            r.router.pointerUp(r.cx("to"), r.cy("to"), PointerEvent.PRIMARY, 0);
            assertEquals(List.of("enter", "leave", "enter", "leave"), log);
        }
    }

    @Test
    void codeCanStartADragForControllers() {
        try (InputRig r = new InputRig(slots())) {
            Slots s = wire(r);
            UiElement from = r.el("from");
            DragSession session = r.router.drag().start(from, "from");
            assertTrue(session.isLive());
            assertTrue(r.router.gamepadButton(GamepadButtons.B, true), "B cancels the drag");
            assertEquals(List.of("CANCELLED:ESCAPE"), s.ends);
            assertTrue(session.id() > 0);
            DragSession next = r.router.drag().start(from, "from");
            assertTrue(next.id() > session.id(), "ids are never reused");
        }
    }
}
