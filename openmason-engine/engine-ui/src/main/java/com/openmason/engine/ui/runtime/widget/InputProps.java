package com.openmason.engine.ui.runtime.widget;

import com.openmason.engine.format.omui.UiFeatures;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.ValueType;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Interaction and accessibility properties every widget accepts (#288), gated by the
 * {@code ui-input} feature ({@link UiFeatures#INPUT_PROPS} is the format's list of the same
 * names). They are ordinary properties, so overrides, bindings and script writes reach them
 * like any other; {@link WidgetDescriptor#of} appends them to every descriptor that does not
 * declare its own property of the same name (a host widget may, to change a default).
 *
 * <p>{@code focusable} defaults to {@code null}, meaning "the widget type decides"
 * ({@link #focusableByDefault}): buttons and text fields take focus, containers and labels do
 * not.
 */
public final class InputProps {

    public static final String FOCUSABLE = "focusable";
    public static final String TAB_INDEX = "tabIndex";
    public static final String AUTOFOCUS = "autofocus";
    public static final String NAV_UP = "navUp";
    public static final String NAV_DOWN = "navDown";
    public static final String NAV_LEFT = "navLeft";
    public static final String NAV_RIGHT = "navRight";
    public static final String FOCUS_SCOPE = "focusScope";
    public static final String DRAGGABLE = "draggable";
    public static final String TOOLTIP = "tooltip";
    public static final String TOOLTIP_KEY = "tooltipKey";
    public static final String ROLE = "role";
    public static final String ACCESSIBLE_NAME = "accessibleName";
    public static final String ACCESSIBLE_DESCRIPTION = "accessibleDescription";
    public static final String ACCESSIBLE_VALUE = "accessibleValue";
    public static final String STATUS = "status";
    public static final String ACTION_HINTS = "actionHints";

    /** {@code focusScope}: none, a navigation group, a modal (traps focus, blocks lower input) or a popup. */
    public static final Set<String> SCOPES = Set.of("none", "group", "modal", "popup");

    /** {@code role}: semantic roles (an ARIA-shaped subset) for platform accessibility later. */
    public static final Set<String> ROLES = Set.of("none", "group", "button", "text", "heading", "image",
        "text-field", "checkbox", "switch", "slider", "progress", "list", "list-item", "menu", "menu-item",
        "tab-list", "tab", "dialog", "alert", "status", "tooltip", "scroll-area", "grid", "cell", "link");

    /** {@code status}: conveyed by a symbol and the accessible state, never by colour alone. */
    public static final Set<String> STATUSES = Set.of("none", "info", "success", "warning", "error", "busy");

    private static final Set<String> FOCUSABLE_TYPES = Set.of("Button", "TextField");

    private static final List<PropertyDescriptor> COMMON = List.of(
        new PropertyDescriptor(FOCUSABLE, ValueType.BOOL, UiValue.NULL,
            "Takes keyboard/controller focus (unset = the widget type decides)"),
        new PropertyDescriptor(TAB_INDEX, ValueType.INT, UiValue.of(0),
            "Tab order: positive values first (ascending), 0 in tree order, negative = skipped by Tab"),
        new PropertyDescriptor(AUTOFOCUS, ValueType.BOOL, UiValue.FALSE,
            "Receives focus when its scope opens"),
        new PropertyDescriptor(NAV_UP, ValueType.STRING, UiValue.NULL, "Element key focused by navigate-up"),
        new PropertyDescriptor(NAV_DOWN, ValueType.STRING, UiValue.NULL, "Element key focused by navigate-down"),
        new PropertyDescriptor(NAV_LEFT, ValueType.STRING, UiValue.NULL, "Element key focused by navigate-left"),
        new PropertyDescriptor(NAV_RIGHT, ValueType.STRING, UiValue.NULL, "Element key focused by navigate-right"),
        new PropertyDescriptor(FOCUS_SCOPE, ValueType.STRING, UiValue.NULL,
            "none, group, modal (traps focus, blocks lower input) or popup (dismissable)", SCOPES),
        new PropertyDescriptor(DRAGGABLE, ValueType.BOOL, UiValue.FALSE, "Pointer drags start drag and drop"),
        new PropertyDescriptor(TOOLTIP, ValueType.STRING, UiValue.NULL, "Tooltip text"),
        new PropertyDescriptor(TOOLTIP_KEY, ValueType.STRING, UiValue.NULL, "Localized tooltip key (ui-l10n)"),
        new PropertyDescriptor(ROLE, ValueType.STRING, UiValue.NULL, "Semantic role (unset = from the widget type)",
            ROLES),
        new PropertyDescriptor(ACCESSIBLE_NAME, ValueType.STRING, UiValue.NULL,
            "Accessible name (unset = derived from text content)"),
        new PropertyDescriptor(ACCESSIBLE_DESCRIPTION, ValueType.STRING, UiValue.NULL, "Accessible description"),
        new PropertyDescriptor(ACCESSIBLE_VALUE, ValueType.STRING, UiValue.NULL,
            "Accessible value (unset = the widget's own value)"),
        new PropertyDescriptor(STATUS, ValueType.STRING, UiValue.NULL,
            "Status shown with a symbol, not colour alone", STATUSES),
        new PropertyDescriptor(ACTION_HINTS, ValueType.OBJECT, UiValue.NULL,
            "Controller/keyboard hints: {\"ui.submit\": \"Select\"}; glyphs follow the player's bindings"));

    private InputProps() {
    }

    public static List<PropertyDescriptor> common() {
        return COMMON;
    }

    /** True for widget types that take focus unless {@code focusable} says otherwise. */
    public static boolean focusableByDefault(String type) {
        return FOCUSABLE_TYPES.contains(type);
    }

    /** True when {@code name} is one of the common properties. */
    public static boolean isCommon(String name) {
        for (PropertyDescriptor p : COMMON) {
            if (p.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** The common descriptors {@code declared} does not already have. */
    static List<PropertyDescriptor> missingFrom(Map<String, PropertyDescriptor> declared) {
        return COMMON.stream().filter(p -> !declared.containsKey(p.name())).toList();
    }
}
