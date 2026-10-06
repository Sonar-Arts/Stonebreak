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
     * Sprite-sheet references (#294): an asset value {@code <sheet id>#<sprite>} naming one region
     * or skin of a {@code sprites} dependency. An older reader would see an unlisted id.
     */
    public static final String SPRITES = "ui-sprites";

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
        if (firstSpriteRef(archive) != null) {
            out.add(SPRITES);
        }
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

    /**
     * The first {@code <sheet>#<sprite>} reference in {@code archive} whose sheet is a
     * {@code sprites} row of its table, or null. Scans every place {@code DependencyRefs} rewrites:
     * node props, inline styles, instance params and overrides, component parameter and event
     * argument defaults, sheet rules and variables, graph variable defaults and literals, clip keys.
     */
    public static String firstSpriteRef(OmuiArchive archive) {
        java.util.Set<String> sheets = new java.util.HashSet<>();
        for (UiDependency dep : archive.dependencies().entries()) {
            if (dep.kind() == UiDependency.Kind.SPRITES) {
                sheets.add(dep.id());
            }
        }
        if (sheets.isEmpty()) {
            return null;
        }
        java.util.List<String> hits = new java.util.ArrayList<>(1);
        java.util.function.Consumer<UiValue> scan = v -> spriteRefs(v, sheets, hits);
        nodeValues(archive.document().root(), scan);
        UiDocument.ComponentDef contract = archive.document().component();
        if (contract != null) {
            contract.params().forEach(p -> defaultValue(p.defaultValue(), scan));
            contract.events().forEach(e -> e.args().forEach(p -> defaultValue(p.defaultValue(), scan)));
        }
        for (UiStyleSheet sheet : archive.styles().values()) {
            sheet.variables().values().forEach(scan);
            sheet.rules().forEach(r -> r.style().values().forEach(scan));
        }
        for (UiGraph g : archive.graphs().values()) {
            g.variables().forEach(v -> defaultValue(v.defaultValue(), scan));
            g.nodes().forEach(n -> graphNode(n, scan));
            g.functions().forEach(f -> f.nodes().forEach(n -> graphNode(n, scan)));
        }
        for (UiAnimationClip c : archive.animations().values()) {
            c.tracks().forEach(t -> t.keys().forEach(k -> scan.accept(k.value())));
        }
        return hits.isEmpty() ? null : hits.getFirst();
    }

    private static void defaultValue(UiValue v, java.util.function.Consumer<UiValue> scan) {
        if (v != null) {
            scan.accept(v);
        }
    }

    private static void graphNode(UiGraph.GraphNode n, java.util.function.Consumer<UiValue> scan) {
        n.inputs().values().forEach(scan);
        n.props().values().forEach(scan);
    }

    private static void nodeValues(UiNode n, java.util.function.Consumer<UiValue> scan) {
        n.props().values().forEach(scan);
        n.style().values().forEach(scan);
        if (n.instance() != null) {
            n.instance().params().values().forEach(scan);
            for (UiNode.InstanceOverride o : n.instance().overrides()) {
                o.props().values().forEach(scan);
                o.style().values().forEach(scan);
            }
            n.instance().slots().values().forEach(list -> list.forEach(c -> nodeValues(c, scan)));
        }
        n.children().forEach(c -> nodeValues(c, scan));
    }

    private static void spriteRefs(UiValue v, java.util.Set<String> sheets, java.util.List<String> hits) {
        if (!hits.isEmpty()) {
            return;
        }
        switch (v) {
            case UiValue.Str s -> {
                UiSpriteRef ref = UiSpriteRef.parse(s.value());
                if (ref != null && sheets.contains(ref.sheet())) {
                    hits.add(s.value());
                }
            }
            case UiValue.Arr a -> a.items().forEach(i -> spriteRefs(i, sheets, hits));
            case UiValue.Obj o -> o.fields().values().forEach(i -> spriteRefs(i, sheets, hits));
            default -> {
            }
        }
    }
}
