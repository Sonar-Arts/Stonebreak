package com.openmason.engine.format.omui;

import java.util.Map;
import java.util.Set;

/**
 * Optional format features (§1 of the wire contract). A document that uses one lists it in
 * the manifest's {@code requires}, so a reader that predates it refuses the document cleanly
 * ({@code UNSUPPORTED_REQUIRED_FEATURE}) instead of misreading it; a writer refuses a document
 * that uses a feature without declaring it ({@code UNDECLARED_FEATURE}).
 *
 * <p>Input, accessibility and localization metadata are ordinary widget properties (so
 * overrides and bindings reach them), but a runtime that ignored them would silently lose
 * focus order, modal trapping or a screen-reader name. Their names are therefore reserved
 * here and gated by a feature, like a widget type is (#288).
 */
public final class UiFeatures {

    /** The {@code ScrollView} widget and {@code overflow: scroll} (#287). */
    public static final String SCROLL = "ui-scroll";

    /**
     * Interaction and accessibility metadata (#288): the {@code TextField} widget, the
     * {@link #INPUT_PROPS} on any widget, and the {@code :focus-visible} / {@code :invalid}
     * pseudo-states.
     */
    public static final String INPUT = "ui-input";

    /** Localized text: the {@link #L10N_PROPS} ({@code textKey}, {@code textArgs}, ...) (#288). */
    public static final String L10N = "ui-l10n";

    /**
     * Collection views over host data (#289): the {@code ListView} widget, whose single child is
     * the row template each item is bound to.
     */
    public static final String DATA = "ui-data";

    /**
     * Script-drawn surfaces (#292): the {@code Canvas} widget, whose content is a per-frame
     * draw-command buffer filled by its document's Lua code-behind (minigames).
     */
    public static final String CANVAS = "ui-canvas";

    /**
     * Properties every widget accepts under {@link #INPUT}. Focus: {@code focusable},
     * {@code tabIndex}, {@code autofocus}, {@code navUp/Down/Left/Right}, {@code focusScope};
     * pointer: {@code draggable}, {@code tooltip}; accessibility: {@code role},
     * {@code accessibleName}, {@code accessibleDescription}, {@code accessibleValue},
     * {@code status}; controller hints: {@code actionHints}.
     */
    public static final Set<String> INPUT_PROPS = Set.of(
            "focusable", "tabIndex", "autofocus", "navUp", "navDown", "navLeft", "navRight", "focusScope",
            "draggable", "tooltip", "role", "accessibleName", "accessibleDescription", "accessibleValue", "status",
            "actionHints");

    /** Properties of localized text under {@link #L10N}. */
    public static final Set<String> L10N_PROPS = Set.of("textKey", "textArgs", "placeholderKey", "tooltipKey");

    /** Pseudo-states added by {@link #INPUT}. */
    public static final Set<String> INPUT_STATES = Set.of("focus-visible", "invalid");

    private UiFeatures() {
    }

    /** @return the feature a style declaration needs, or null */
    public static String forStyle(String property, UiValue value) {
        if ("overflow".equals(property) && value instanceof UiValue.Str s && "scroll".equals(s.value())) {
            return SCROLL;
        }
        return null;
    }

    /** @return the first feature {@code style} needs, or null */
    public static String forStyle(Map<String, UiValue> style) {
        for (Map.Entry<String, UiValue> e : style.entrySet()) {
            String f = forStyle(e.getKey(), e.getValue());
            if (f != null) {
                return f;
            }
        }
        return null;
    }

    /** @return the feature a widget property needs, or null */
    public static String forProp(String name) {
        if (INPUT_PROPS.contains(name)) {
            return INPUT;
        }
        return L10N_PROPS.contains(name) ? L10N : null;
    }

    /** @return the feature a selector list needs (a {@link #INPUT_STATES} pseudo-state), or null */
    public static String forSelector(String selector) {
        for (String state : INPUT_STATES) {
            int at = selector.indexOf(":" + state);
            while (at >= 0) {
                int end = at + 1 + state.length();
                if (end == selector.length() || !UiSelectors.isIdentChar(selector.charAt(end))) {
                    return INPUT;
                }
                at = selector.indexOf(":" + state, end);
            }
        }
        return null;
    }

    /**
     * Every optional feature {@code archive} uses (widget types, gated properties, overflow
     * scroll, gated pseudo-states), sorted: what its {@code requires} must contain. Editors add
     * these automatically so a document never fails the writer's {@code UNDECLARED_FEATURE} check.
     */
    public static java.util.SortedSet<String> used(OmuiArchive archive) {
        java.util.SortedSet<String> out = new java.util.TreeSet<>();
        usedBy(archive.document().root(), out);
        for (UiStyleSheet sheet : archive.styles().values()) {
            for (UiStyleSheet.StyleRule rule : sheet.rules()) {
                addIfPresent(out, forSelector(rule.selector()));
                addIfPresent(out, forStyle(rule.style()));
            }
        }
        return out;
    }

    private static void usedBy(UiNode n, java.util.Set<String> out) {
        addIfPresent(out, UiWidgets.requiredFeature(n.type()));
        n.props().keySet().forEach(p -> addIfPresent(out, forProp(p)));
        addIfPresent(out, forStyle(n.style()));
        if (n.instance() != null) {
            for (UiNode.InstanceOverride o : n.instance().overrides()) {
                o.props().keySet().forEach(p -> addIfPresent(out, forProp(p)));
                addIfPresent(out, forStyle(o.style()));
            }
            n.instance().slots().values().forEach(list -> list.forEach(c -> usedBy(c, out)));
        }
        n.children().forEach(c -> usedBy(c, out));
    }

    private static void addIfPresent(java.util.Set<String> out, String feature) {
        if (feature != null) {
            out.add(feature);
        }
    }
}
