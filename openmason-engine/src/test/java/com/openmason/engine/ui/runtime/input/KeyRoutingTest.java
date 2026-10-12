package com.openmason.engine.ui.runtime.input;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.masonry.MKeys;
import com.openmason.engine.ui.runtime.UiDocs;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.node;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Key consumption, repeat and controller rules (#288). The battle cases mirror the Focus battle
 * input-precedence fixtures ({@code FocusBattleScreenInputTest}, {@code MenuInputRouter}): a
 * bound screen consumes every key, navigation repeats but confirm does not, a prompt outranks an
 * open command window, and a key held across a screen switch never acts in the new screen.
 */
class KeyRoutingTest {

    private static UiDocs.N button(String id) {
        return node(id, "Button").style("width", 60).style("height", 30);
    }

    private static OmuiArchive menu() {
        return screen("t:ui/keys", box("root").style("width", 400).style("height", 300).kids(
            button("first"), button("second"), button("third")));
    }

    @Test
    void keysThatDoNothingInTheUiFallThrough() {
        try (InputRig r = new InputRig(screen("t:ui/empty", box("root").style("width", 100).style("height", 100)))) {
            assertFalse(r.press(87 /* W */), "W walks the player when no UI wants it");
            assertFalse(r.press(MKeys.KEY_DOWN), "no focusable elements: arrows are gameplay's");
            assertFalse(r.press(MKeys.KEY_ESCAPE), "Escape opens the pause menu, not ours");
        }
    }

    @Test
    void aReleaseOrRepeatIsConsumedExactlyWhenItsPressWas() {
        try (InputRig r = new InputRig(menu())) {
            assertTrue(r.router.keyDown(MKeys.KEY_DOWN, 0, false));
            assertTrue(r.router.keyDown(MKeys.KEY_DOWN, 0, true));
            assertTrue(r.router.keyUp(MKeys.KEY_DOWN, 0));
            assertFalse(r.router.keyDown(87, 0, false));
            assertFalse(r.router.keyUp(87, 0));
        }
    }

    @Test
    void aKeyHeldAcrossAScreenSwitchNeverActsInTheNewScreen() {
        try (InputRig r = new InputRig(menu())) {
            List<String> clicks = new ArrayList<>();
            r.el("root").on(UiEventType.CLICK, e -> clicks.add(e.target().key()));
            r.router.focus().focus(r.el("first"), InputDevice.KEYBOARD);
            // Enter went down in the previous screen: this router never saw the press.
            assertFalse(r.router.keyDown(MKeys.KEY_ENTER, 0, true), "a stale repeat is not ours");
            assertFalse(r.router.keyUp(MKeys.KEY_ENTER, 0), "nor its release");
            assertEquals(List.of(), clicks);
        }
    }

    @Test
    void navigationRepeatsButConfirmDoesNot() {
        try (InputRig r = new InputRig(menu())) {
            List<String> clicks = new ArrayList<>();
            r.el("root").on(UiEventType.CLICK, e -> clicks.add(e.target().key()));
            r.router.keyDown(MKeys.KEY_DOWN, 0, false);
            r.router.keyDown(MKeys.KEY_DOWN, 0, true);
            r.router.keyDown(MKeys.KEY_DOWN, 0, true);
            r.router.keyUp(MKeys.KEY_DOWN, 0);
            assertEquals("third", r.focusKey(), "press focuses first, two repeats move twice");
            assertTrue(r.router.keyDown(MKeys.KEY_ENTER, 0, false));
            assertTrue(r.router.keyDown(MKeys.KEY_ENTER, 0, true), "the held confirm stays the UI's...");
            assertTrue(r.router.keyDown(MKeys.KEY_ENTER, 0, true));
            r.router.keyUp(MKeys.KEY_ENTER, 0);
            assertEquals(List.of("third"), clicks, "...but clicks once");
        }
    }

    @Test
    void controllerActionsRepeatOnTheRouterClock() {
        try (InputRig r = new InputRig(menu(), 400, 300, InputSettings.DEFAULTS.withControllerRepeat(0.4, 0.1))) {
            assertTrue(r.router.gamepadButton(GamepadButtons.DPAD_DOWN, true));
            assertEquals("first", r.focusKey());
            r.router.tick(0.39);
            assertEquals("first", r.focusKey(), "not before the delay");
            r.router.tick(0.02);
            assertEquals("second", r.focusKey());
            r.router.tick(0.1);
            assertEquals("third", r.focusKey());
            assertTrue(r.router.gamepadButton(GamepadButtons.DPAD_DOWN, false));
            r.router.tick(1);
            assertEquals("third", r.focusKey(), "released: no more repeats");
            assertTrue(r.el("third").hasState("focus-visible"));
            assertEquals(InputDevice.GAMEPAD, r.router.lastDevice());
            List<String> clicks = new ArrayList<>();
            r.el("root").on(UiEventType.CLICK, e -> clicks.add(e.target().key()));
            r.router.gamepadButton(GamepadButtons.A, true);
            r.router.tick(2);
            r.router.gamepadButton(GamepadButtons.A, false);
            assertEquals(List.of("third"), clicks, "confirm never repeats on a controller either");
        }
    }

    @Test
    void aModalPromptOutranksAnOpenCommandWindowAndConsumesEverything() {
        OmuiArchive doc = screen("t:ui/battle", box("root").style("width", 400).style("height", 300).kids(
            box("commands").kids(button("attack"), button("guard")),
            box("prompt").prop("focusScope", "modal").style("display", "none").kids(button("parry"))));
        try (InputRig r = new InputRig(doc)) {
            r.press(MKeys.KEY_DOWN);
            assertEquals("attack", r.focusKey());
            r.el("prompt").setStyle("display", UiValue.of("flex"));
            r.frame();
            List<String> clicks = new ArrayList<>();
            r.el("root").on(UiEventType.CLICK, e -> clicks.add(e.target().key()));
            assertTrue(r.press(MKeys.KEY_ENTER));
            assertEquals(List.of("parry"), clicks, "confirm answers the prompt, not the menu underneath");
            assertTrue(r.press(87 /* W */), "a bound screen consumes every key: nothing leaks to gameplay");
            assertTrue(r.router.text('w'));
        }
    }

    @Test
    void handlersCanTakeKeysBeforeDefaults() {
        try (InputRig r = new InputRig(menu())) {
            r.router.focus().focus(r.el("first"), InputDevice.KEYBOARD);
            r.el("root").on(UiEventType.KEY_DOWN, e -> {
                if (((KeyEvent) e).key() == MKeys.KEY_DOWN) {
                    e.preventDefault();
                }
            }, EventCallbacks.Phase.TRICKLE_DOWN);
            assertTrue(r.press(MKeys.KEY_DOWN), "prevented: still consumed");
            assertEquals("first", r.focusKey(), "but focus did not move");
            r.el("root").on(UiEventType.NAVIGATE, e -> e.stopPropagation());
            assertTrue(r.press(MKeys.KEY_TAB));
            assertEquals("first", r.focusKey(), "a NAVIGATE handler replaces the default move");
        }
    }

    @Test
    void remappedKeysDriveTheSameActions() {
        try (InputRig r = new InputRig(menu())) {
            r.router.setActionMap(UiActionMap.defaults().withKeys(UiAction.NAVIGATE_DOWN,
                List.of(UiActionMap.KeyChord.any(87 /* W */))));
            assertTrue(r.press(87 /* W */));
            assertEquals("first", r.focusKey());
            assertTrue(r.press(87 /* W */));
            assertEquals("second", r.focusKey());
            assertFalse(r.press(MKeys.KEY_DOWN), "an unbound key does nothing in the UI, so it falls through");
            assertEquals("second", r.focusKey());
        }
    }

    @Test
    void windowFocusLossForgetsHeldKeysAndButtons() {
        try (InputRig r = new InputRig(menu(), 400, 300, InputSettings.DEFAULTS.withControllerRepeat(0.1, 0.1))) {
            assertTrue(r.router.keyDown(MKeys.KEY_DOWN, 0, false));
            r.router.gamepadButton(GamepadButtons.DPAD_DOWN, true);
            r.router.windowFocusLost();
            assertFalse(r.router.keyUp(MKeys.KEY_DOWN, 0), "the release after focus loss is not ours");
            assertFalse(r.router.keyDown(MKeys.KEY_DOWN, 0, true), "nor a repeat");
            String before = r.focusKey();
            r.router.tick(1);
            assertEquals(before, r.focusKey(), "controller repeat stopped");
            assertFalse(r.router.gamepadButton(GamepadButtons.DPAD_DOWN, false));
        }
    }
}
