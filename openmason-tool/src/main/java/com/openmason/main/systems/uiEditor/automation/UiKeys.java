package com.openmason.main.systems.uiEditor.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.openmason.engine.ui.masonry.MKeys;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Key names an agent types ({@code enter}, {@code a}, {@code f5}) to the GLFW codes the router uses. */
final class UiKeys {

    private static final Map<String, Integer> NAMED = new LinkedHashMap<>();

    static {
        NAMED.put("space", MKeys.KEY_SPACE);
        NAMED.put("escape", MKeys.KEY_ESCAPE);
        NAMED.put("enter", MKeys.KEY_ENTER);
        NAMED.put("tab", MKeys.KEY_TAB);
        NAMED.put("backspace", MKeys.KEY_BACKSPACE);
        NAMED.put("insert", MKeys.KEY_INSERT);
        NAMED.put("delete", MKeys.KEY_DELETE);
        NAMED.put("right", MKeys.KEY_RIGHT);
        NAMED.put("left", MKeys.KEY_LEFT);
        NAMED.put("down", MKeys.KEY_DOWN);
        NAMED.put("up", MKeys.KEY_UP);
        NAMED.put("pageup", MKeys.KEY_PAGE_UP);
        NAMED.put("pagedown", MKeys.KEY_PAGE_DOWN);
        NAMED.put("home", MKeys.KEY_HOME);
        NAMED.put("end", MKeys.KEY_END);
    }

    private UiKeys() {
    }

    static int code(String name) {
        String n = name.trim().toLowerCase(Locale.ROOT).replace("_", "");
        if (n.equals("esc")) {
            n = "escape";
        } else if (n.equals("return")) {
            n = "enter";
        }
        Integer named = NAMED.get(n);
        if (named != null) {
            return named;
        }
        if (n.length() == 1 && (n.charAt(0) >= 'a' && n.charAt(0) <= 'z' || n.charAt(0) >= '0' && n.charAt(0) <= '9')) {
            return Character.toUpperCase(n.charAt(0)); // GLFW letters/digits are their ASCII codes
        }
        if (n.matches("f([1-9]|1[0-2])")) {
            return 290 + Integer.parseInt(n.substring(1)) - 1; // GLFW_KEY_F1 = 290
        }
        throw new IllegalArgumentException("unknown key '" + name + "'; keys: " + NAMED.keySet()
            + ", a-z, 0-9, f1-f12");
    }

    static int mods(JsonNode mods) {
        int out = 0;
        if (mods != null && mods.isArray()) {
            for (JsonNode m : mods) {
                out |= switch (m.asText().toLowerCase(Locale.ROOT)) {
                    case "shift" -> MKeys.MOD_SHIFT;
                    case "ctrl", "control" -> MKeys.MOD_CONTROL;
                    case "alt" -> MKeys.MOD_ALT;
                    case "super", "meta" -> MKeys.MOD_SUPER;
                    default -> throw new IllegalArgumentException("mods are shift, ctrl, alt, super");
                };
            }
        }
        return out;
    }
}
