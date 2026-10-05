package com.openmason.engine.ui.runtime.input;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiFeatures;
import com.openmason.engine.ui.masonry.MKeys;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.node;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Bindings, remapping, settings round trip and the hints that follow them (#288). */
class ActionMapTest {

    @Test
    void defaultsAndModifierMatching() {
        UiActionMap m = UiActionMap.defaults();
        assertEquals(UiAction.NEXT, m.actionForKey(MKeys.KEY_TAB, 0));
        assertEquals(UiAction.PREVIOUS, m.actionForKey(MKeys.KEY_TAB, MKeys.MOD_SHIFT));
        assertNull(m.actionForKey(MKeys.KEY_TAB, MKeys.MOD_CONTROL), "Ctrl+Tab is not bound");
        assertEquals(UiAction.NAVIGATE_UP, m.actionForKey(MKeys.KEY_UP, MKeys.MOD_SHIFT), "arrows match any modifier");
        assertEquals(UiAction.NEXT, m.actionForKey(MKeys.KEY_TAB, 0x10), "Caps Lock is not a modifier");
        assertEquals(UiAction.SUBMIT, m.actionForKey(MKeys.KEY_SPACE, 0));
        assertNull(m.actionForKey(MKeys.KEY_SPACE, MKeys.MOD_SHIFT));
        assertEquals(UiAction.SUBMIT, m.actionForButton(GamepadButtons.A));
        assertEquals(UiAction.CANCEL, m.actionForButton(GamepadButtons.B));
    }

    @Test
    void wireRoundTripKeepsRemapsAndFillsNewActions() {
        UiActionMap remapped = UiActionMap.defaults()
            .withKeys(UiAction.SUBMIT, List.of(UiActionMap.KeyChord.exact(69, 0)))
            .withButtons(UiAction.CANCEL, List.of(GamepadButtons.Y));
        Map<String, List<String>> wire = remapped.toWire();
        assertEquals(List.of("key:69+exact", "pad:0"), wire.get("ui.submit"));
        assertEquals(remapped, UiActionMap.fromWire(wire));
        Map<String, List<String>> old = Map.of("ui.submit", List.of("key:69+exact", "garbage", "pad:x"),
            "ui.unknown", List.of("key:1"));
        UiActionMap read = UiActionMap.fromWire(old);
        assertEquals(UiAction.SUBMIT, read.actionForKey(69, 0));
        assertEquals(UiActionMap.defaults().keys(UiAction.CANCEL), read.keys(UiAction.CANCEL),
            "actions missing from an older file keep their defaults");
    }

    @Test
    void hintsShowTheCurrentBindingForTheCurrentDevice() {
        OmuiArchive doc = InputRig.withFeatures(screen("t:ui/hints", box("root").kids(
            node("ok", "Button").prop("actionHints", com.openmason.engine.format.omui.UiValue.Obj.sorted(Map.of(
                "ui.submit", com.openmason.engine.format.omui.UiValue.of("Select"),
                "ui.cancel", com.openmason.engine.format.omui.UiValue.of("Back"),
                "ui.nonsense", com.openmason.engine.format.omui.UiValue.of("ignored")))))), UiFeatures.INPUT);
        try (InputRig r = new InputRig(doc)) {
            UiActionMap map = UiActionMap.defaults();
            assertEquals(List.of(new ActionHints.Hint(UiAction.CANCEL, "Esc", "Back"),
                    new ActionHints.Hint(UiAction.SUBMIT, "Enter", "Select")),
                ActionHints.of(r.el("ok"), map, InputDevice.KEYBOARD));
            assertEquals("A", ActionHints.of(r.el("ok"), map, InputDevice.GAMEPAD).get(1).glyph());
            UiActionMap remapped = map.withKeys(UiAction.SUBMIT, List.of(UiActionMap.KeyChord.exact(69, MKeys.MOD_SHIFT)));
            assertEquals("Shift+E", ActionHints.of(r.el("ok"), remapped, InputDevice.KEYBOARD).get(1).glyph(),
                "a remap changes the hint without touching the document");
            assertEquals("Shift+Tab", ActionHints.glyph(UiAction.PREVIOUS, map, InputDevice.KEYBOARD));
            assertEquals("LB", ActionHints.glyph(UiAction.PREVIOUS, map, InputDevice.GAMEPAD));
            assertEquals("", ActionHints.glyph(UiAction.SUBMIT, map.withKeys(UiAction.SUBMIT, List.of()),
                InputDevice.KEYBOARD), "unbound: no glyph");
        }
    }
}
