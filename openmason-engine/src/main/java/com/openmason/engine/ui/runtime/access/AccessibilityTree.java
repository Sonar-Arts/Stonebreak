package com.openmason.engine.ui.runtime.access;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiTexts;
import com.openmason.engine.ui.runtime.input.ActionHints;
import com.openmason.engine.ui.runtime.input.InputDevice;
import com.openmason.engine.ui.runtime.input.InputTraits;
import com.openmason.engine.ui.runtime.input.UiActionMap;
import com.openmason.engine.ui.runtime.widget.InputProps;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds the semantic tree of a running document (#288). Elements with role {@code none} and no
 * name are transparent: their children attach to the nearest exposed ancestor. Collapsed and
 * hidden elements are left out, like a screen reader would.
 *
 * <p>Name derivation: {@code accessibleName}; else, for a {@code Label}, its (localized) text;
 * else, for a button, link, tab, list item, menu item or checkbox, the text of its labels
 * joined by spaces; else the tooltip; else "". A status is exposed as a state, never only as a
 * colour.
 */
public final class AccessibilityTree {

    private AccessibilityTree() {
    }

    public static AccessibleNode snapshot(UiDocumentInstance ui, UiActionMap bindings, InputDevice device) {
        List<AccessibleNode> top = new ArrayList<>();
        build(ui.root(), top, bindings, device);
        if (top.size() == 1) {
            return top.getFirst();
        }
        return new AccessibleNode("", "group", "", "", "", Set.of(), ui.root().rect(), List.of(), top);
    }

    private static void build(UiElement el, List<AccessibleNode> out, UiActionMap bindings, InputDevice device) {
        if (!el.isVisible()) {
            if (el.isCollapsed()) {
                return;
            }
            for (UiElement c : el.children()) {
                build(c, out, bindings, device); // visibility: hidden can have visible descendants
            }
            return;
        }
        List<AccessibleNode> kids = new ArrayList<>();
        for (UiElement c : el.children()) {
            build(c, kids, bindings, device);
        }
        String role = role(el);
        String name = name(el, role);
        if ("none".equals(role) && name.isEmpty() && InputTraits.scope(el) != InputTraits.Scope.MODAL) {
            out.addAll(kids);
            return;
        }
        List<String> hints = new ArrayList<>();
        for (ActionHints.Hint h : ActionHints.of(el, bindings, device)) {
            hints.add(h.glyph() + ": " + h.label());
        }
        out.add(new AccessibleNode(el.key(), role, name, description(el), value(el), states(el), el.rect(), hints,
            List.copyOf(kids)));
    }

    /** {@code role}, else the widget type's: Button → button, Label → text, Image → image, ... */
    public static String role(UiElement el) {
        if (el.prop(InputProps.ROLE) instanceof UiValue.Str s && !s.value().isEmpty()) {
            return s.value();
        }
        if (InputTraits.scope(el) == InputTraits.Scope.MODAL) {
            return "dialog";
        }
        return switch (el.type()) {
            case "Button" -> "button";
            case "Label" -> "text";
            case "Image" -> "image";
            case "TextField" -> "text-field";
            case "ScrollView" -> "scroll-area";
            case "ItemSlot" -> "cell";
            default -> "none";
        };
    }

    public static String name(UiElement el, String role) {
        if (el.prop(InputProps.ACCESSIBLE_NAME) instanceof UiValue.Str s && !s.value().isEmpty()) {
            return s.value();
        }
        if ("Label".equals(el.type())) {
            return UiTexts.label(el);
        }
        if (Set.of("button", "link", "tab", "list-item", "menu-item", "checkbox", "switch").contains(role)) {
            StringBuilder sb = new StringBuilder();
            collectText(el, sb);
            if (!sb.isEmpty()) {
                return sb.toString();
            }
        }
        if ("TextField".equals(el.type())) {
            String placeholder = UiTexts.placeholder(el);
            if (!placeholder.isEmpty()) {
                return placeholder;
            }
        }
        return UiTexts.tooltip(el);
    }

    private static void collectText(UiElement el, StringBuilder sb) {
        if (!el.isVisible()) {
            return;
        }
        if ("Label".equals(el.type())) {
            String t = UiTexts.label(el);
            if (!t.isEmpty()) {
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append(t);
            }
        }
        for (UiElement c : el.children()) {
            collectText(c, sb);
        }
    }

    private static String description(UiElement el) {
        if (el.prop(InputProps.ACCESSIBLE_DESCRIPTION) instanceof UiValue.Str s && !s.value().isEmpty()) {
            return s.value();
        }
        return el.prop(InputProps.ACCESSIBLE_NAME) instanceof UiValue.Str ? UiTexts.tooltip(el) : "";
    }

    private static String value(UiElement el) {
        if (el.prop(InputProps.ACCESSIBLE_VALUE) instanceof UiValue.Str s) {
            return s.value();
        }
        if ("TextField".equals(el.type())) {
            String t = el.text("text");
            return el.prop("password") instanceof UiValue.Bool b && b.value() ? "*".repeat(t.codePointCount(0, t.length())) : t;
        }
        return "";
    }

    private static Set<String> states(UiElement el) {
        Set<String> s = new LinkedHashSet<>();
        if (InputTraits.declaresFocusable(el)) {
            s.add("focusable");
        }
        if (el.hasState(UiElement.FOCUS)) {
            s.add("focused");
        }
        if (!el.isEnabledInHierarchy()) {
            s.add("disabled");
        }
        if (el.hasState(UiElement.CHECKED)) {
            s.add("checked");
        }
        if (el.hasState(UiElement.INVALID)) {
            s.add("invalid");
        }
        if (el.prop("readOnly") instanceof UiValue.Bool b && b.value()) {
            s.add("read-only");
        }
        if (InputTraits.scope(el) == InputTraits.Scope.MODAL) {
            s.add("modal");
        }
        if (el.prop(InputProps.STATUS) instanceof UiValue.Str st && !"none".equals(st.value())) {
            s.add("status:" + st.value());
        }
        return s;
    }
}
