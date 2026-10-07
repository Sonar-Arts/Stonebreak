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
     * UI state machines (#295): {@code animations/<id>.states.json} parts. An older reader would
     * keep them as unknown entries and never pose the document.
     */
    public static final String STATES = "ui-states";

    /**
     * Wrapped, truncated and rich label text: {@code white-space: normal | pre-wrap},
     * {@code text-overflow: ellipsis}, {@code -sb-max-lines} and the Label {@code rich} markup. An
     * older reader would draw one unwrapped line of raw markup.
     */
    public static final String TEXT = "ui-text";

    /**
     * Pointer-driven layering: {@code -sb-anchor: pointer} (the cursor layer a carried item or
     * drag ghost uses) and {@code pointer-events}. An older reader would leave the element at its
     * layout position and keep click-through elements clickable.
     */
    public static final String CURSOR = "ui-cursor";

    /**
     * Motion semantics added by #295 after documents could already carry their syntax: custom
     * {@code bezier} timing on clip keys and style transitions, and {@code transform-origin-x/y}.
     * A reader that predates them would play the named easing and rotate/scale about the centre,
     * silently. {@link #RETRO_GATED}: implied for files that use them without declaring it.
     */
    public static final String MOTION = "ui-motion";

    /**
     * Explicit paint layers (#287 {@code -sb-layer}). A reader that ignored the property would
     * paint popups and overlays in tree order, under later siblings and inside their clips.
     * {@link #RETRO_GATED}.
     */
    public static final String LAYERS = "ui-layers";

    /**
     * The Masonry house look as style (#297): {@code -sb-surface} (stone panel, button and HUD
     * surfaces on any element, or none on a {@code Button}), {@code -sb-text-effect} (a label's
     * house shadow, no shadow, or the layered title) and {@code -sb-pixel-grid} (a root that keeps
     * fractional legacy geometry). An older reader would drop the surfaces and effects and snap the
     * layout, with only "unknown property" warnings.
     */
    public static final String MASONRY = "ui-masonry";

    /**
     * Every feature this code understands: the reader's {@code SUPPORTED_FEATURES}. A new
     * feature is one constant here plus its entries in {@link #STYLE_FEATURES} /
     * {@link #PROP_FEATURES} (or a predicate in {@link #used}).
     */
    public static final Set<String> ALL = Set.of(SCROLL, INPUT, L10N, DATA, CANVAS, SPRITES, STATES, MOTION, LAYERS,
            TEXT, CURSOR, MASONRY);

    /**
     * Features gated after documents could already use their syntax. Files that use one without
     * declaring it read as though they had (the reader adds it, with an info diagnostic), so
     * existing documents keep opening; the writer always declares them (see {@link #withInferred}).
     */
    public static final Set<String> RETRO_GATED = Set.of(MOTION, LAYERS);

    /**
     * Style property → the feature it needs whatever its value: semantics an older reader would
     * drop with only an "unknown property" warning. Inline styles, overrides, sheet rules and
     * {@code style:} clip tracks all consult it. Shared registry: new gated properties are
     * added here.
     */
    public static final Map<String, String> STYLE_FEATURES = Map.ofEntries(
            Map.entry("transform-origin-x", MOTION),
            Map.entry("transform-origin-y", MOTION),
            Map.entry("-sb-layer", LAYERS),
            Map.entry("white-space", TEXT),
            Map.entry("text-overflow", TEXT),
            Map.entry("-sb-max-lines", TEXT),
            Map.entry("-sb-anchor", CURSOR),
            Map.entry("pointer-events", CURSOR),
            Map.entry("-sb-surface", MASONRY),
            Map.entry("-sb-text-effect", MASONRY),
            Map.entry("-sb-pixel-grid", MASONRY));

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

    /**
     * Widget property → the feature it needs: {@link #INPUT_PROPS}, {@link #L10N_PROPS}, plus any
     * later gated property. Shared registry: new gated widget properties are added here.
     */
    public static final Map<String, String> PROP_FEATURES = propFeatures();

    private static Map<String, String> propFeatures() {
        Map<String, String> m = new java.util.HashMap<>();
        INPUT_PROPS.forEach(p -> m.put(p, INPUT));
        L10N_PROPS.forEach(p -> m.put(p, L10N));
        m.put("rich", TEXT); // Label rich-text markup
        return Map.copyOf(m);
    }

    /** @return the feature a style declaration needs, or null */
    public static String forStyle(String property, UiValue value) {
        if ("overflow".equals(property) && value instanceof UiValue.Str s && "scroll".equals(s.value())) {
            return SCROLL;
        }
        return STYLE_FEATURES.get(property);
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
        return PROP_FEATURES.get(name);
    }

    /**
     * @return the feature a clip track's binding target needs ({@code style:-sb-layer}), or null.
     * Only {@code style:} targets are gated; {@code prop:} targets are ordinary widget props.
     */
    public static String forTrack(String property) {
        if (property.startsWith("style:")) {
            return STYLE_FEATURES.get(property.substring("style:".length()));
        }
        if (property.startsWith("prop:")) {
            return forProp(property.substring("prop:".length()));
        }
        return null;
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
        if (!archive.stateMachines().isEmpty()) {
            out.add(STATES);
        }
        for (UiStyleSheet sheet : archive.styles().values()) {
            for (UiStyleSheet.StyleRule rule : sheet.rules()) {
                addIfPresent(out, forSelector(rule.selector()));
                rule.style().forEach((k, v) -> addIfPresent(out, forStyle(k, v)));
                for (UiStyleSheet.StyleTransition t : rule.transitions()) {
                    if (t.bezier() != null) {
                        out.add(MOTION);
                    }
                }
            }
        }
        for (UiAnimationClip clip : archive.animations().values()) {
            for (UiAnimationClip.AnimTrack track : clip.tracks()) {
                addIfPresent(out, forTrack(track.property()));
                for (UiAnimationClip.AnimKey key : track.keys()) {
                    if (key.bezier() != null) {
                        out.add(MOTION);
                    }
                }
            }
        }
        return out;
    }

    /**
     * {@code archive} with every feature it uses declared in {@code requires}; the same instance
     * when nothing is missing. Declared-but-unused features are kept. The writer applies this, so
     * saved bytes always declare what they use and an older reader refuses them cleanly rather
     * than silently mis-rendering.
     */
    public static OmuiArchive withInferred(OmuiArchive archive) {
        return withAdded(archive, used(archive));
    }

    /**
     * {@code archive} with the {@link #RETRO_GATED} features it uses declared, for files written
     * before those features were gated. Records an info diagnostic per feature added.
     */
    public static OmuiArchive withImpliedRetroGated(OmuiArchive archive, UiDiagnostics d) {
        java.util.SortedSet<String> implied = new java.util.TreeSet<>(used(archive));
        implied.retainAll(RETRO_GATED);
        implied.removeAll(archive.manifest().requires());
        for (String f : implied) {
            d.info(UiDiagnostic.Code.UNDECLARED_FEATURE, OmuiFormat.MANIFEST, "/requires",
                    "Implied \"" + f + "\" (the document predates it); saving declares it");
        }
        return withAdded(archive, implied);
    }

    private static OmuiArchive withAdded(OmuiArchive archive, java.util.Collection<String> features) {
        UiManifest m = archive.manifest();
        if (m.requires().containsAll(features)) {
            return archive;
        }
        java.util.SortedSet<String> all = new java.util.TreeSet<>(m.requires());
        all.addAll(features);
        return archive.withManifest(new UiManifest(m.schemaVersion(), m.documentId(), m.kind(), m.displayName(),
                m.uiApi(), m.layoutSemantics(), java.util.List.copyOf(all), m.hostApis(), m.providers(), m.unknown()));
    }

    private static void usedBy(UiNode n, java.util.Set<String> out) {
        addIfPresent(out, UiWidgets.requiredFeature(n.type()));
        n.props().keySet().forEach(p -> addIfPresent(out, forProp(p)));
        n.style().forEach((k, v) -> addIfPresent(out, forStyle(k, v)));
        if (n.instance() != null) {
            for (UiNode.InstanceOverride o : n.instance().overrides()) {
                o.props().keySet().forEach(p -> addIfPresent(out, forProp(p)));
                o.style().forEach((k, v) -> addIfPresent(out, forStyle(k, v)));
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
