package com.openmason.engine.ui.runtime.input;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.ui.masonry.MKeys;
import com.openmason.engine.ui.runtime.UiElement;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.node;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regressions for the #282 hardening review of the #288 input foundations: keyboard layouts and
 * AltGr, what a focused field keeps, release pairing during a capture, per-button click counts,
 * held buttons and modifiers on moves, controller drags, late drop replies, drags across a
 * rebuilt slot, and stacked documents.
 */
class InputHardeningTest {

    private static final int KEY_Q = 81;
    private static final int KEY_LEFT_SHIFT = 340;
    private static final int KEY_KP_1 = 321;
    private static final int KEY_F2 = 291;
    private static final int CTRL_ALT = MKeys.MOD_CONTROL | MKeys.MOD_ALT;

    private static OmuiArchive form() {
        return screen("t:ui/form", box("root").style("width", 400).style("height", 300).style("picking-mode", "ignore")
            .kids(node("field", "TextField").prop("text", "abc").style("width", 200).style("height", 30),
                node("ok", "Button").style("width", 60).style("height", 30)));
    }

    private static InputRig focusedField() {
        InputRig r = new InputRig(form());
        assertTrue(r.router.focus().focus(r.el("field"), InputDevice.MOUSE));
        r.frame();
        r.router.textField(r.el("field")).model().moveDocumentEnd(false);
        return r;
    }

    private static String text(InputRig r) {
        return r.el("field").text("text");
    }

    // ── keyboard layouts and AltGr ─────────────────────────────────────────

    @Test
    void altGrIsTypingNotAShortcut() {
        try (InputRig r = focusedField()) {
            assertTrue(r.router.keyDown(MKeys.KEY_A, CTRL_ALT, false), "AltGr+A belongs to the field");
            r.router.keyUp(MKeys.KEY_A, CTRL_ALT);
            assertTrue(r.router.text("ą"));
            assertEquals("abcą", text(r), "AltGr+A must not select all before its character arrives");
            assertTrue(r.router.keyDown(MKeys.KEY_Z, CTRL_ALT, false));
            r.router.keyUp(MKeys.KEY_Z, CTRL_ALT);
            r.router.text("ż");
            assertEquals("abcąż", text(r), "AltGr+Z must not undo");
            assertTrue(r.router.keyDown(KEY_Q, CTRL_ALT, false), "AltGr+Q (@ on German) must not reach gameplay as Q");
        }
    }

    @Test
    void plainControlShortcutsStillWork() {
        try (InputRig r = focusedField()) {
            r.router.text("x");
            assertEquals("abcx", text(r));
            assertTrue(r.press(MKeys.KEY_Z, MKeys.MOD_CONTROL));
            assertEquals("abc", text(r), "Ctrl+Z undoes (hosts pass layout-translated keys)");
        }
    }

    // ── what a focused field keeps ─────────────────────────────────────────

    @Test
    void typingModifiersAndKeypadStayWithAFocusedField() {
        try (InputRig r = focusedField()) {
            assertTrue(r.router.keyDown(KEY_LEFT_SHIFT, MKeys.MOD_SHIFT, false), "a Shift for a capital must not sneak");
            assertTrue(r.router.keyUp(KEY_LEFT_SHIFT, 0));
            assertTrue(r.router.keyDown(KEY_KP_1, 0, false), "keypad digits are typing");
            r.router.keyUp(KEY_KP_1, 0);
            assertFalse(r.router.keyDown(KEY_F2, 0, false), "function keys (screenshots) pass through");
            r.router.keyUp(KEY_F2, 0);
        }
        try (InputRig r = new InputRig(form())) {
            assertFalse(r.router.keyDown(KEY_LEFT_SHIFT, MKeys.MOD_SHIFT, false), "without a field Shift is gameplay's");
        }
    }

    // ── pointer pairing, click counts, held state ─────────────────────────

    @Test
    void aRightPressTheWorldTookDuringATextCaptureGetsItsRelease() {
        try (InputRig r = new InputRig(form())) {
            assertTrue(r.router.pointerDown(r.cx("field"), r.cy("field"), PointerEvent.PRIMARY, 0));
            assertSame(r.el("field"), r.router.pointerCapture(), "a text field captures for drag selection");
            assertFalse(r.router.pointerDown(350, 250, PointerEvent.SECONDARY, 0), "the world took the right press");
            assertFalse(r.router.pointerUp(350, 250, PointerEvent.SECONDARY, 0),
                "so its release is the world's too, or the right button sticks");
            assertTrue(r.router.pointerUp(r.cx("field"), r.cy("field"), PointerEvent.PRIMARY, 0),
                "the primary release ends the capture and is the UI's");
            assertNull(r.router.pointerCapture());
        }
    }

    @Test
    void clickCountsDoNotMixButtons() {
        try (InputRig r = new InputRig(form())) {
            List<Integer> counts = new ArrayList<>();
            r.el("ok").on(UiEventType.POINTER_DOWN, e -> counts.add(((PointerEvent) e).clickCount()));
            float x = r.cx("ok");
            float y = r.cy("ok");
            r.router.pointerDown(x, y, PointerEvent.PRIMARY, 0);
            r.router.pointerUp(x, y, PointerEvent.PRIMARY, 0);
            r.router.pointerDown(x, y, PointerEvent.SECONDARY, 0);
            r.router.pointerUp(x, y, PointerEvent.SECONDARY, 0);
            r.router.pointerDown(x, y, PointerEvent.SECONDARY, 0);
            r.router.pointerUp(x, y, PointerEvent.SECONDARY, 0);
            assertEquals(List.of(1, 1, 2), counts, "left then right is two single clicks; right, right is a double");
        }
    }

    @Test
    void movesCarryHeldButtonsAndModifiers() {
        try (InputRig r = new InputRig(form())) {
            List<PointerEvent> moves = new ArrayList<>();
            r.el("ok").on(UiEventType.POINTER_MOVE, e -> moves.add((PointerEvent) e));
            float x = r.cx("ok");
            float y = r.cy("ok");
            r.router.keyDown(KEY_LEFT_SHIFT, 0, false); // GLFW may report the bit only from the next event
            r.router.pointerDown(x, y, PointerEvent.SECONDARY, MKeys.MOD_SHIFT);
            r.router.pointerMove(x + 1, y);
            PointerEvent m = moves.getLast();
            assertTrue(m.isHeld(PointerEvent.SECONDARY), "right-drag distribution needs the held button");
            assertFalse(m.isHeld(PointerEvent.PRIMARY));
            assertEquals(MKeys.MOD_SHIFT, m.modifiers() & MKeys.MOD_SHIFT, "and Shift-drag needs the modifier");
            r.router.pointerUp(x + 1, y, PointerEvent.SECONDARY, MKeys.MOD_SHIFT);
            r.router.keyUp(KEY_LEFT_SHIFT, MKeys.MOD_SHIFT);
            r.router.pointerMove(x + 2, y);
            assertEquals(0, moves.getLast().buttons());
            assertEquals(0, moves.getLast().modifiers());
        }
    }

    // ── drag and drop ──────────────────────────────────────────────────────

    private static OmuiArchive slots() {
        return screen("t:ui/slots", box("root").style("width", 400).style("height", 300).style("flex-direction", "row")
            .kids(node("from", "ItemSlot").prop("draggable", true).prop("focusable", true)
                    .style("width", 50).style("height", 50),
                box("gap").style("width", 100),
                node("to", "ItemSlot").prop("focusable", true).style("width", 50).style("height", 50)));
    }

    /** "to" accepts and takes the stack; returns the counts and the outcomes seen at DRAG_END. */
    private static Map<String, Integer> wire(InputRig r, List<String> ends) {
        Map<String, Integer> counts = new LinkedHashMap<>(Map.of("from", 5, "to", 0));
        r.el("from").on(UiEventType.DRAG_START, e -> ((DragEvent) e).setPayload("from"));
        r.el("to").on(UiEventType.DRAG_OVER, e -> ((DragEvent) e).acceptDrop());
        r.el("to").on(UiEventType.DRAG_DROP, e -> {
            counts.merge("to", counts.get("from"), Integer::sum);
            counts.put("from", 0);
        });
        r.el("from").on(UiEventType.DRAG_END, e -> ends.add(String.valueOf(((DragEvent) e).session().outcome())));
        return counts;
    }

    @Test
    void aControllerPickUpIsMovedByNavigationAndDroppedWithSubmit() {
        try (InputRig r = new InputRig(slots())) {
            List<String> ends = new ArrayList<>();
            Map<String, Integer> counts = wire(r, ends);
            r.router.focus().focus(r.el("from"), InputDevice.GAMEPAD);
            DragSession s = r.router.startDrag(r.el("from"), "from", InputDevice.GAMEPAD);
            assertEquals(InputDevice.GAMEPAD, s.device());
            assertTrue(r.router.gamepadButton(GamepadButtons.DPAD_RIGHT, true));
            r.router.gamepadButton(GamepadButtons.DPAD_RIGHT, false);
            assertEquals("to", r.router.focus().focused().key());
            assertSame(r.el("to"), s.acceptor(), "focus navigation sent DRAG_OVER to the newly focused slot");
            assertTrue(r.router.gamepadButton(GamepadButtons.A, true), "A drops instead of clicking the slot");
            r.router.gamepadButton(GamepadButtons.A, false);
            assertEquals(Map.of("from", 0, "to", 5), counts);
            assertEquals(List.of("DROPPED"), ends);
            assertNull(r.router.lastClick(), "the drop was not also a click");
        }
    }

    @Test
    void aDropStaysCurrentForItsLateReplyUntilSuperseded() {
        try (InputRig r = new InputRig(slots())) {
            wire(r, new ArrayList<>());
            r.router.pointerDown(r.cx("from"), r.cy("from"), PointerEvent.PRIMARY, 0);
            r.router.pointerMove(r.cx("from") + 20, r.cy("from"));
            r.router.pointerMove(r.cx("to"), r.cy("to"));
            DragSession dropped = r.router.drag().session();
            r.router.pointerUp(r.cx("to"), r.cy("to"), PointerEvent.PRIMARY, 0);
            assertFalse(dropped.isLive());
            assertTrue(dropped.isCurrent(), "the server-confirmed move must still apply");
            DragSession next = r.router.drag().start(r.el("from"), "from");
            assertFalse(dropped.isCurrent(), "a newer drag supersedes the reply");
            r.router.screenClosed();
            assertFalse(next.isCurrent(), "a cancelled drag is never current");

            DragSession cancelled = r.router.drag().start(r.el("from"), "from");
            r.router.drag().cancel(CancelReason.PROGRAM);
            assertFalse(cancelled.isCurrent());
        }
        try (InputRig r = new InputRig(slots())) {
            wire(r, new ArrayList<>());
            r.router.pointerDown(r.cx("from"), r.cy("from"), PointerEvent.PRIMARY, 0);
            r.router.pointerMove(r.cx("from") + 20, r.cy("from"));
            r.router.pointerMove(r.cx("to"), r.cy("to"));
            DragSession dropped = r.router.drag().session();
            r.router.pointerUp(r.cx("to"), r.cy("to"), PointerEvent.PRIMARY, 0);
            r.router.screenClosed();
            assertFalse(dropped.isCurrent(), "a closed screen supersedes it too");
        }
    }

    @Test
    void aDragFollowsASlotThatWasRebuiltUnderTheSameKey() {
        try (InputRig r = new InputRig(slots())) {
            List<String> ends = new ArrayList<>();
            wire(r, ends);
            r.router.pointerDown(r.cx("from"), r.cy("from"), PointerEvent.PRIMARY, 0);
            r.router.pointerMove(r.cx("from") + 20, r.cy("from"));
            r.router.pointerMove(r.cx("to"), r.cy("to"));
            DragSession s = r.router.drag().session();
            UiElement old = r.el("to");
            old.remove(); // an inventory sync re-renders the slot
            UiElement twin = r.el("root").insertChild(2, node("to", "ItemSlot").prop("focusable", true)
                .style("width", 50).style("height", 50).build());
            // The re-render wires the new slot like the old one.
            twin.on(UiEventType.DRAG_OVER, e -> ((DragEvent) e).acceptDrop());
            r.frame();
            assertNotSame(old, twin);
            assertSame(twin, r.el("to"));
            assertTrue(s.isLive(), "the rebuild must not cancel the drag");
            assertSame(twin, s.acceptor(), "the acceptance follows the key");
            r.router.pointerUp(r.cx("to"), r.cy("to"), PointerEvent.PRIMARY, 0);
            assertEquals(DragSession.Outcome.DROPPED, s.outcome());
            assertSame(twin, s.dropTarget());
            assertEquals(List.of("DROPPED"), ends);
        }
    }

    // ── stacked documents ──────────────────────────────────────────────────

    /**
     * A host offers every event to its documents top-down and stops at the first that consumes
     * it. A HUD-like document above a screen with an open modal must not let the modal leak.
     */
    @Test
    void aModalInALowerDocumentStillOwnsInputUnderANonModalDocument() {
        OmuiArchive lower = screen("t:ui/lower", box("root").style("width", 400).style("height", 300)
            .style("picking-mode", "ignore")
            .kids(node("behind", "Button").style("width", 60).style("height", 30),
                box("dialog").prop("focusScope", "modal").style("width", 200).style("height", 100)
                    .kids(node("yes", "Button").style("width", 60).style("height", 30))));
        OmuiArchive upper = screen("t:ui/hud", box("root").style("width", 400).style("height", 300)
            .style("picking-mode", "ignore")
            .kids(node("hudButton", "Button").style("width", 40).style("height", 20)));
        try (InputRig low = new InputRig(lower); InputRig up = new InputRig(upper)) {
            List<InputRig> topDown = List.of(up, low);
            assertTrue(low.router.focus().activeModal() != null, "the modal opened");
            // Press on empty space: the HUD lets it through, the modal blocks it.
            assertTrue(offer(topDown, r -> r.router.pointerDown(390, 290, PointerEvent.PRIMARY, 0)) == low);
            assertTrue(offer(topDown, r -> r.router.pointerUp(390, 290, PointerEvent.PRIMARY, 0)) == low);
            // A key: the HUD has no focus, the modal takes it, gameplay never sees it.
            assertTrue(offer(topDown, r -> r.router.keyDown(87, 0, false)) == low);
            assertTrue(offer(topDown, r -> r.router.keyUp(87, 0)) == low);
            // A press on the HUD's own button is the HUD's; the modal underneath is not involved.
            assertTrue(offer(topDown, r -> r.router.pointerDown(up.cx("hudButton"), up.cy("hudButton"),
                PointerEvent.PRIMARY, 0)) == up);
            assertTrue(offer(topDown, r -> r.router.pointerUp(up.cx("hudButton"), up.cy("hudButton"),
                PointerEvent.PRIMARY, 0)) == up);
            assertEquals("hudButton", up.router.lastClick().key());
        }
    }

    private static InputRig offer(List<InputRig> topDown, java.util.function.Predicate<InputRig> event) {
        for (InputRig r : topDown) {
            if (event.test(r)) {
                return r;
            }
        }
        return null;
    }

    // ── the input gate across locales ──────────────────────────────────────

    @Test
    void aMonitorReportsWhenALocaleChangeBlocksAScreen() {
        OmuiArchive doc = screen("t:ui/m", box("root").kids(node("name", "TextField").prop("inputFilter", "ascii"),
            com.openmason.engine.ui.runtime.UiDocs.label("title", "Name")));
        try (InputRig r = new InputRig(doc)) {
            UiInputGate.Monitor m = UiInputGate.monitor(r.ui, java.util.EnumSet.of(InputCapability.POINTER,
                InputCapability.KEYBOARD, InputCapability.TEXT_INPUT, InputCapability.CLIPBOARD));
            assertEquals(List.of(), m.poll(java.util.Locale.ENGLISH));
            assertFalse(m.blocked());
            List<UiInputGate.Block> ru = m.poll(java.util.Locale.forLanguageTag("ru"));
            assertTrue(m.blocked(), "Cyrillic labels need fallback fonts the game font lacks");
            assertEquals(InputCapability.FONT_FALLBACK, ru.getFirst().capability());
            assertSame(ru, m.poll(java.util.Locale.forLanguageTag("ru")), "an unchanged input is not re-evaluated");
            assertEquals(List.of(), m.poll(java.util.Locale.ENGLISH), "switching back unblocks");
        }
    }
}
