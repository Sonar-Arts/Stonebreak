package com.openmason.engine.ui.runtime;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiDocument;
import com.openmason.engine.format.omui.UiManifest;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiStyleSheet;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic.Code;
import com.openmason.engine.ui.runtime.style.CompiledSheet;
import com.openmason.engine.ui.runtime.style.SheetBinding;
import com.openmason.engine.ui.runtime.widget.WidgetDescriptor;
import com.openmason.engine.ui.runtime.widget.WidgetRegistry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Expands a document definition into runtime elements (#287): validates every node against
 * its widget descriptor, expands component instances (parameters, explicit overrides, named
 * slots) and attaches style sheets at their cascade rank. Nothing here writes to the
 * definition; every per-instance value lands on the {@link UiElement}.
 *
 * <p>Composition errors never abort the build: a missing or recursive component leaves its
 * {@code Instance} element empty, an override whose target vanished from the component's
 * current revision is reported where it was written, and the rest of the screen still works.
 */
final class UiTreeBuilder {

    /** Where a node was authored: its key prefix, component nesting and static data scope. */
    record Scope(String prefix, int depth, String componentId, OmuiArchive archive, UiValue data,
                         Map<String, List<UiNode>> slotContent, Scope outer, OverrideSet outerOverrides) {
    }

    private final UiDocumentInstance owner;
    private final UiRuntimeContext context;
    private final List<UiElement> elements = new ArrayList<>();
    private final Map<String, UiElement> byKey = new LinkedHashMap<>();
    private final Map<String, UiElement> existing;
    private int duplicates;
    private final List<SheetBinding> sheets = new ArrayList<>();
    private final List<UiDocumentInstance.AuthoringScope> scopes = new ArrayList<>();
    private final List<OverrideSet.Authored> authoredOverrides = new ArrayList<>();
    private final Deque<String> componentStack = new ArrayDeque<>();
    private final Map<String, CompiledSheet> sheetCache = new HashMap<>();

    UiTreeBuilder(UiDocumentInstance owner, UiRuntimeContext context) {
        this(owner, context, Map.of());
    }

    /** @param existing keys already in the instance, for runtime insertions */
    UiTreeBuilder(UiDocumentInstance owner, UiRuntimeContext context, Map<String, UiElement> existing) {
        this.owner = owner;
        this.context = context;
        this.existing = existing;
    }

    List<UiElement> elements() {
        return elements;
    }

    /**
     * Keys this build found already taken (counted, not read back from the instance's
     * deduplicated diagnostics: the same duplicate inserted twice reports one diagnostic).
     */
    int duplicates() {
        return duplicates;
    }

    Map<String, UiElement> byKey() {
        return byKey;
    }

    List<SheetBinding> sheets() {
        return sheets;
    }

    /** The screen's and every built component instance's authoring scope (#295 state machines). */
    List<UiDocumentInstance.AuthoringScope> scopes() {
        return scopes;
    }

    UiElement build(OmuiArchive document) {
        List<UiStyleSheet> theme = context.theme();
        for (int i = 0; i < theme.size(); i++) {
            UiStyleSheet t = theme.get(i);
            sheets.add(new SheetBinding(compile("theme:" + t.id(), t), SheetBinding.THEME_RANK, i, null));
        }
        Scope top = new Scope("", 0, null, document, null, Map.of(), null, OverrideSet.EMPTY);
        scopes.add(new UiDocumentInstance.AuthoringScope("", document));
        UiElement root = buildNode(document.document().root(), top, OverrideSet.EMPTY, null);
        attachSheets(document, 0, null, "");
        reportUnusedOverrides();
        return root;
    }

    /**
     * Builds {@code node} for insertion under {@code parent} at runtime, in the parent's
     * authoring scope. The caller attaches it.
     */
    UiElement buildChild(UiElement parent, UiNode node) {
        UiElement el = buildNode(node, (Scope) parent.scope, OverrideSet.EMPTY, null);
        reportUnusedOverrides();
        return el;
    }

    /**
     * Builds a collection row from {@code template} with element key {@code key}; descendants
     * are keyed {@code key/<nodeId>} so every row of one template is distinct (#289).
     */
    UiElement buildRow(UiElement parent, UiNode template, String key) {
        UiElement el = buildNode(template, (Scope) parent.scope, OverrideSet.EMPTY, key);
        reportUnusedOverrides();
        return el;
    }

    /** {@code ListView} children are its row template, not live elements (#289). */
    static boolean isTemplateHost(UiNode node) {
        return "ListView".equals(node.type());
    }

    private void reportUnusedOverrides() {
        for (OverrideSet.Authored a : authoredOverrides) {
            if (!a.used) {
                owner.report(UiRuntimeDiagnostic.warning(Code.OVERRIDE_TARGET_MISSING, a.instanceKey,
                    "override target '" + a.override.target() + "' does not exist in the component's current revision"));
            }
        }
    }

    /** @param rowKey explicit key of a template row; its descendants are keyed below it */
    private UiElement buildNode(UiNode node, Scope scope, OverrideSet overrides, String rowKey) {
        String key = rowKey != null ? rowKey : scope.prefix + node.id();
        WidgetRegistry registry = context.widgets();
        Map<String, UiValue> props = registry.validate(node, key, owner::report);
        WidgetDescriptor descriptor = registry.get(node.type());
        if (props == null) {
            // Unknown or too-new type: keep a plain container so the screen still lays out.
            descriptor = WidgetDescriptor.of(node.type(), 1, true, false, "unavailable widget", List.of());
            props = Map.of();
        }
        UiElement el = new UiElement(owner, key, node, descriptor, props, scope.depth, scope.componentId);
        if (byKey.putIfAbsent(key, el) != null || existing.containsKey(key)) {
            duplicates++;
            owner.report(UiRuntimeDiagnostic.error(Code.DUPLICATE_ELEMENT_KEY, key, "element key is not unique"));
        }
        elements.add(el);

        for (UiNode.InstanceOverride o : overrides.forNode(node.id())) {
            el.putOverrideProps(WidgetRegistry.validProps(descriptor, o.props(), key, owner::report));
            el.putOverrideStyle(o.style());
            el.applyOverrideClasses(o.addClasses(), o.removeClasses());
        }

        UiValue data = scope.data;
        if (node.dataSource() != null) {
            data = DataPaths.eval(data, node.dataSource()); // absolute sources are live host data (#289)
        }
        applyStaticBindings(el, node, data);
        String prefix = rowKey != null ? rowKey + "/" : scope.prefix;
        Scope own = data == scope.data && rowKey == null ? scope : new Scope(prefix, scope.depth, scope.componentId,
            scope.archive, data, rowKey != null ? Map.of() : scope.slotContent, scope.outer, scope.outerOverrides);
        el.scope = new Scope(own.prefix, own.depth, own.componentId, own.archive, own.data, Map.of(), own.outer,
            own.outerOverrides);

        if (descriptor.acceptsChildren() && !isTemplateHost(node)) {
            for (UiNode child : node.children()) {
                el.addChild(buildNode(child, own, overrides, null));
            }
        }
        List<UiNode> slotted = scope.slotContent.get(node.id());
        if (slotted != null) {
            if (!descriptor.acceptsChildren()) {
                owner.report(UiRuntimeDiagnostic.error(Code.CHILDREN_NOT_ALLOWED, key,
                    "slot host " + node.type() + " cannot hold slot content"));
            } else {
                for (UiNode child : slotted) {
                    el.addChild(buildNode(child, scope.outer, scope.outerOverrides, null));
                }
            }
        }
        if (node.instance() != null) {
            buildInstance(el, node, own, overrides);
        }
        return el;
    }

    private void buildInstance(UiElement el, UiNode node, Scope scope, OverrideSet overrides) {
        UiNode.ComponentInstance inst = node.instance();
        String componentId = inst.component();
        String key = el.key();
        List<OverrideSet.Authored> own = new ArrayList<>();
        for (UiNode.InstanceOverride o : inst.overrides()) {
            OverrideSet.Authored a = new OverrideSet.Authored(o, key);
            own.add(a);
            authoredOverrides.add(a);
        }
        if (componentStack.contains(componentId)) {
            owner.report(UiRuntimeDiagnostic.error(Code.RECURSIVE_COMPONENT, key,
                "component " + componentId + " contains itself through " + String.join(" > ", componentStack.reversed())));
            own.forEach(a -> a.used = true);
            return;
        }
        OmuiArchive archive = context.source().component(componentId);
        if (archive == null) {
            owner.report(UiRuntimeDiagnostic.error(Code.MISSING_COMPONENT, key, "component " + componentId + " not found"));
            own.forEach(a -> a.used = true);
            return;
        }
        UiDocument doc = archive.document();
        if (archive.manifest().kind() != UiManifest.DocumentKind.COMPONENT || doc.component() == null) {
            owner.report(UiRuntimeDiagnostic.error(Code.NOT_A_COMPONENT, key, componentId + " is not a component document"));
            own.forEach(a -> a.used = true);
            return;
        }
        UiDocument.ComponentDef def = doc.component();
        Map<String, List<UiNode>> slotContent = new HashMap<>();
        inst.slots().forEach((slotName, nodes) -> {
            UiDocument.Slot slot = def.slots().stream().filter(s -> s.name().equals(slotName)).findFirst().orElse(null);
            if (slot == null) {
                owner.report(UiRuntimeDiagnostic.error(Code.UNKNOWN_SLOT, key,
                    componentId + " has no slot '" + slotName + "'; " + nodes.size() + " node(s) dropped"));
            } else {
                slotContent.computeIfAbsent(slot.host(), h -> new ArrayList<>()).addAll(nodes);
            }
        });
        UiValue.Obj params = params(def, inst, key, boundParams(el, node, def, scope.data));
        el.componentParams = params;
        Scope inner = new Scope(key + "/", scope.depth + 1, componentId, archive, params,
            slotContent, scope, overrides);
        componentStack.push(componentId);
        scopes.add(new UiDocumentInstance.AuthoringScope(key, archive));
        try {
            UiElement root = buildNode(doc.root(), inner, overrides.enter(node.id(), own), null);
            el.addChild(root);
            for (String host : slotContent.keySet()) {
                if (!byKey.containsKey(key + "/" + host)) {
                    owner.report(UiRuntimeDiagnostic.error(Code.UNKNOWN_SLOT, key,
                        componentId + " declares slot host '" + host + "' but has no such node"));
                }
            }
            attachSheets(archive, scope.depth + 1, root, componentId);
        } finally {
            componentStack.pop();
        }
    }

    /**
     * Static values of {@code prop:<param>} bindings on an {@code Instance} node: an instance's
     * props are its component's parameters, so a binding there feeds the component's data
     * source (#289). Live values are the binder's.
     */
    private Map<String, UiValue> boundParams(UiElement el, UiNode node, UiDocument.ComponentDef def, UiValue data) {
        Map<String, UiValue> out = new LinkedHashMap<>();
        for (UiNode.UiBinding b : node.bindings()) {
            if (!b.target().startsWith("prop:")) {
                continue;
            }
            String name = b.target().substring(5);
            UiDocument.Param p = def.params().stream().filter(q -> q.name().equals(name)).findFirst().orElse(null);
            if (p == null) {
                owner.report(UiRuntimeDiagnostic.error(Code.UNKNOWN_PARAM, el.key(),
                    "binding " + b.target() + ": " + node.instance().component() + " has no parameter '" + name + "'"));
                continue;
            }
            UiValue v = b.converter() == null && DataPaths.isRelative(b.path()) ? DataPaths.eval(data, b.path()) : null;
            if (v != null && p.type().accepts(v)) {
                out.put(name, v);
            }
        }
        return out;
    }

    /** Declared parameters with defaults, overlaid with the instance's valid values, then static param bindings. */
    private UiValue.Obj params(UiDocument.ComponentDef def, UiNode.ComponentInstance inst, String key,
                               Map<String, UiValue> bound) {
        Map<String, UiValue> values = new LinkedHashMap<>();
        for (UiDocument.Param p : def.params()) {
            values.put(p.name(), p.defaultValue() == null ? UiValue.NULL : p.defaultValue());
        }
        inst.params().forEach((name, value) -> {
            UiDocument.Param p = def.params().stream().filter(q -> q.name().equals(name)).findFirst().orElse(null);
            if (p == null) {
                owner.report(UiRuntimeDiagnostic.error(Code.UNKNOWN_PARAM, key,
                    inst.component() + " has no parameter '" + name + "'"));
            } else if (!p.type().accepts(value)) {
                owner.report(UiRuntimeDiagnostic.error(Code.PARAM_TYPE, key,
                    "parameter " + name + " expects " + p.type().wire() + ", got " + value.typeName()));
            } else {
                values.put(name, value);
            }
        });
        values.putAll(bound);
        return new UiValue.Obj(values);
    }

    /**
     * Applies bindings whose relative path resolves against static data (component
     * parameters). Converter bindings and host data wait for the binding runtime (#289).
     */
    private void applyStaticBindings(UiElement el, UiNode node, UiValue data) {
        for (UiNode.UiBinding b : node.bindings()) {
            if (b.converter() != null || !DataPaths.isRelative(b.path())) {
                continue;
            }
            UiValue v = DataPaths.eval(data, b.path());
            if (v == null) {
                continue;
            }
            String target = b.target();
            int colon = target.indexOf(':');
            String kind = target.substring(0, colon);
            String name = target.substring(colon + 1);
            if (node.instance() != null && kind.equals("prop")) {
                continue; // a component parameter (boundParams)
            }
            switch (kind) {
                case "prop" -> {
                    var p = el.descriptor().property(name);
                    if (p == null || p.problem(v) != null) {
                        owner.report(UiRuntimeDiagnostic.error(p == null ? Code.UNKNOWN_PROPERTY : Code.PROPERTY_TYPE,
                            el.key(), "binding " + target + ": " + (p == null ? "no such property" : p.problem(v))));
                    } else {
                        el.setBindingProp(name, v);
                    }
                }
                case "style" -> el.setBindingStyle(name, v);
                case "class" -> el.setBindingClass(name, v instanceof UiValue.Bool bool && bool.value());
                default -> {
                }
            }
        }
    }

    private void attachSheets(OmuiArchive archive, int depth, UiElement scope, String context) {
        List<String> refs = archive.document().styleSheets();
        for (int i = 0; i < refs.size(); i++) {
            String ref = refs.get(i);
            UiStyleSheet sheet = ref.indexOf(':') >= 0 ? this.context.source().styleSheet(ref) : archive.styles().get(ref);
            if (sheet == null) {
                owner.report(UiRuntimeDiagnostic.warning(Code.MISSING_STYLESHEET, scope == null ? "" : scope.key(),
                    "style sheet " + ref + " not found" + (context.isEmpty() ? "" : " (component " + context + ")")));
                continue;
            }
            String cacheKey = ref.indexOf(':') >= 0 ? ref : archive.manifest().documentId() + "#" + ref;
            sheets.add(new SheetBinding(compile(cacheKey, sheet), SheetBinding.rankForDepth(depth), i, scope));
        }
    }

    private CompiledSheet compile(String cacheKey, UiStyleSheet sheet) {
        return sheetCache.computeIfAbsent(cacheKey, k -> CompiledSheet.compile(k, sheet, owner::report));
    }
}
