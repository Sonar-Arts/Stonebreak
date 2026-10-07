package com.openmason.engine.ui.script;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.input.InputDevice;
import com.openmason.engine.ui.runtime.input.PointerEvent;
import com.openmason.engine.ui.runtime.input.UiEventType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A keyboard or controller "click" (Enter/Space on a focused button) has no pointer position. Its
 * NaN coordinates used to make the event unencodable for Lua, so a scripted button never heard
 * keyboard activation (#299: found on the pause and main menus). They are now simply absent.
 */
class ScriptKeyboardClickTest {

    @Test
    void aClickWithoutAPositionEncodesWithoutCoordinates() {
        PointerEvent click = new PointerEvent(UiEventType.CLICK, 1.0, Float.NaN, Float.NaN, 1.5f, PointerEvent.PRIMARY,
            0, 1, InputDevice.KEYBOARD);
        UiValue.Obj e = ScriptEvents.encode(click, 1.5f);
        assertEquals(UiValue.of("click"), e.get("type"));
        assertEquals(UiValue.of("keyboard"), e.get("device"));
        for (String k : new String[]{"x", "y", "lx", "ly"}) {
            assertFalse(e.fields().containsKey(k), k);
        }
    }

    @Test
    void aPointerClickKeepsItsCoordinates() {
        PointerEvent click = new PointerEvent(UiEventType.CLICK, 1.0, 30f, 45f, 1.5f, PointerEvent.PRIMARY, 0, 1,
            InputDevice.MOUSE);
        UiValue.Obj e = ScriptEvents.encode(click, 1.5f);
        assertEquals(UiValue.of(20.0), e.get("x"));
        assertEquals(UiValue.of(30.0), e.get("y"));
    }
}
