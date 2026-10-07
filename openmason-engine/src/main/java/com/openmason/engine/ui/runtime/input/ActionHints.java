package com.openmason.engine.ui.runtime.input;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.masonry.MKeys;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.widget.InputProps;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Remappable action hints (#288). A document says <i>what</i> an action does
 * ({@code "actionHints": {"ui.submit": "Select", "ui.cancel": "Back"}}); the glyph showing
 * <i>which</i> key or button does it always comes from the player's current
 * {@link UiActionMap} and the device in use, so remapping or picking up a controller updates
 * every hint without touching the document.
 */
public final class ActionHints {

    /** One hint: the action, the glyph for the current device, the document's label. */
    public record Hint(UiAction action, String glyph, String label) {
    }

    private ActionHints() {
    }

    /** Hints declared on {@code el}, in the property's (canonical) key order; unknown actions are skipped. */
    public static List<Hint> of(UiElement el, UiActionMap map, InputDevice device) {
        List<Hint> out = new ArrayList<>();
        if (el.prop(InputProps.ACTION_HINTS) instanceof UiValue.Obj obj) {
            for (Map.Entry<String, UiValue> e : obj.fields().entrySet()) {
                UiAction action = UiAction.fromId(e.getKey());
                if (action != null && e.getValue() instanceof UiValue.Str label) {
                    out.add(new Hint(action, glyph(action, map, device), label.value()));
                }
            }
        }
        return out;
    }

    /** The first binding of {@code action} for {@code device} ("Enter", "Shift+Tab", "A"), or "" when unbound. */
    public static String glyph(UiAction action, UiActionMap map, InputDevice device) {
        if (device == InputDevice.GAMEPAD) {
            List<Integer> b = map.buttons(action);
            if (!b.isEmpty()) {
                return GamepadButtons.name(b.getFirst());
            }
        }
        List<UiActionMap.KeyChord> k = map.keys(action);
        if (k.isEmpty()) {
            return "";
        }
        UiActionMap.KeyChord c = k.getFirst();
        String key = keyName(c.key());
        if (c.modifiers() <= 0) {
            return key;
        }
        StringBuilder sb = new StringBuilder();
        if ((c.modifiers() & MKeys.MOD_CONTROL) != 0) {
            sb.append("Ctrl+");
        }
        if ((c.modifiers() & MKeys.MOD_ALT) != 0) {
            sb.append("Alt+");
        }
        if ((c.modifiers() & MKeys.MOD_SUPER) != 0) {
            sb.append("Super+");
        }
        if ((c.modifiers() & MKeys.MOD_SHIFT) != 0) {
            sb.append("Shift+");
        }
        return sb.append(key).toString();
    }

    /** ASCII key label (the game font has no arrow glyphs). */
    public static String keyName(int key) {
        if (key >= 'A' && key <= 'Z' || key >= '0' && key <= '9') {
            return String.valueOf((char) key);
        }
        if (key >= 290 && key <= 314) {
            return "F" + (key - 289);
        }
        return switch (key) {
            case MKeys.KEY_SPACE -> "Space";
            case MKeys.KEY_ESCAPE -> "Esc";
            case MKeys.KEY_ENTER -> "Enter";
            case MKeys.KEY_TAB -> "Tab";
            case MKeys.KEY_BACKSPACE -> "Backspace";
            case MKeys.KEY_INSERT -> "Ins";
            case MKeys.KEY_DELETE -> "Del";
            case MKeys.KEY_RIGHT -> "Right";
            case MKeys.KEY_LEFT -> "Left";
            case MKeys.KEY_DOWN -> "Down";
            case MKeys.KEY_UP -> "Up";
            case MKeys.KEY_PAGE_UP -> "PgUp";
            case MKeys.KEY_PAGE_DOWN -> "PgDn";
            case MKeys.KEY_HOME -> "Home";
            case MKeys.KEY_END -> "End";
            case MKeys.KEY_KP_ENTER -> "Num Enter";
            default -> "Key " + key;
        };
    }
}
