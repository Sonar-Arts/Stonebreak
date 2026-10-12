package com.openmason.engine.ui.runtime.input;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.widget.InputProps;

/**
 * Reads an element's interaction properties ({@link InputProps}) with their effective
 * defaults (#288). Pure queries; every rule about what may receive input lives here.
 */
public final class InputTraits {

    /** {@code focusScope} values. */
    public enum Scope { NONE, GROUP, MODAL, POPUP }

    private InputTraits() {
    }

    /** {@code focusable}, or the widget type's default when unset. */
    public static boolean declaresFocusable(UiElement el) {
        return el.prop(InputProps.FOCUSABLE) instanceof UiValue.Bool b ? b.value()
            : InputProps.focusableByDefault(el.type());
    }

    /**
     * Can take focus now: declared focusable, not removed, enabled through its ancestors,
     * not collapsed and not {@code visibility: hidden}. Invisible or disabled elements never
     * receive keyboard input.
     */
    public static boolean canFocus(UiElement el) {
        return el != null && !el.isRemoved() && declaresFocusable(el) && el.isEnabledInHierarchy() && el.isVisible();
    }

    /** Can receive pointer events now: live, enabled through its ancestors and visible. */
    public static boolean canReceivePointer(UiElement el) {
        return el != null && !el.isRemoved() && el.isEnabledInHierarchy() && el.isVisible();
    }

    public static int tabIndex(UiElement el) {
        return el.prop(InputProps.TAB_INDEX) instanceof UiValue.Num n ? (int) n.value() : 0;
    }

    public static boolean autofocus(UiElement el) {
        return el.prop(InputProps.AUTOFOCUS) instanceof UiValue.Bool b && b.value();
    }

    public static boolean draggable(UiElement el) {
        return el.prop(InputProps.DRAGGABLE) instanceof UiValue.Bool b && b.value();
    }

    public static Scope scope(UiElement el) {
        if (el.prop(InputProps.FOCUS_SCOPE) instanceof UiValue.Str s) {
            return switch (s.value()) {
                case "group" -> Scope.GROUP;
                case "modal" -> Scope.MODAL;
                case "popup" -> Scope.POPUP;
                default -> Scope.NONE;
            };
        }
        return Scope.NONE;
    }

    /** The explicit neighbour key for {@code action}, or null. */
    public static String explicitNeighbour(UiElement el, UiAction action) {
        String prop = switch (action) {
            case NAVIGATE_UP -> InputProps.NAV_UP;
            case NAVIGATE_DOWN -> InputProps.NAV_DOWN;
            case NAVIGATE_LEFT -> InputProps.NAV_LEFT;
            case NAVIGATE_RIGHT -> InputProps.NAV_RIGHT;
            default -> null;
        };
        return prop != null && el.prop(prop) instanceof UiValue.Str s && !s.value().isEmpty() ? s.value() : null;
    }

    /** True when {@code el} is {@code ancestor} or inside it. */
    public static boolean isInside(UiElement el, UiElement ancestor) {
        for (UiElement e = el; e != null; e = e.parent()) {
            if (e == ancestor) {
                return true;
            }
        }
        return false;
    }

    /** Nearest focusable element at or above {@code el}, or null. */
    public static UiElement focusableAncestor(UiElement el) {
        for (UiElement e = el; e != null; e = e.parent()) {
            if (canFocus(e)) {
                return e;
            }
        }
        return null;
    }
}
