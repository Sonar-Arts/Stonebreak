package com.openmason.engine.format.omui;

import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.UiDocument.ComponentDef;
import com.openmason.engine.format.omui.UiDocument.Param;
import com.openmason.engine.format.omui.UiNode.ComponentInstance;
import com.openmason.engine.format.omui.UiNode.InstanceOverride;
import com.openmason.engine.format.omui.UiNode.UiBinding;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Checks {@code document.json}: node identity, widget types, styles, bindings, instances, refs. */
final class TreeValidator {

    private static final String E = OmuiFormat.DOCUMENT;

    private final OmuiArchive archive;
    private final UiDiagnostics d;
    private final Set<String> nodeIds = new HashSet<>();
    private final Set<String> providerIds = new HashSet<>();

    private TreeValidator(OmuiArchive archive, UiDiagnostics d) {
        this.archive = archive;
        this.d = d;
        archive.manifest().providers().forEach(p -> providerIds.add(p.id()));
    }

    /** @return every node id in the tree (for clip and editor-metadata checks) */
    static Set<String> validate(OmuiArchive archive, UiDiagnostics d) {
        TreeValidator v = new TreeValidator(archive, d);
        UiDocument doc = archive.document();
        v.node(doc.root(), "/root");
        v.documentRefs(doc);
        if (doc.component() != null) {
            v.component(doc.component());
        }
        return v.nodeIds;
    }

    private void node(UiNode n, String ptr) {
        if (!OmuiFormat.LOCAL_ID.matcher(n.id()).matches()) {
            d.error(Code.INVALID_ID, E, ptr + "/id", "Invalid node id '" + n.id() + "'");
        } else if (!nodeIds.add(n.id())) {
            d.error(Code.DUPLICATE_ID, E, ptr + "/id", "Node id '" + n.id() + "' is used more than once");
        }
        if (n.name() != null && !UiSelectors.isIdent(n.name())) {
            d.error(Code.INVALID_ID, E, ptr + "/name", "Invalid node name '" + n.name() + "'");
        }
        widget(n, ptr);
        for (int i = 0; i < n.classes().size(); i++) {
            if (!UiSelectors.isIdent(n.classes().get(i))) {
                d.error(Code.INVALID_ID, E, ptr + "/classes/" + i, "Invalid class name '" + n.classes().get(i) + "'");
            }
        }
        style(n.style(), E, ptr + "/style", archive.dependencies(), d);
        if (n.dataSource() != null && !UiPaths.isDataPath(n.dataSource())) {
            d.error(Code.INVALID_VALUE, E, ptr + "/dataSource", "Invalid data path '" + n.dataSource() + "'");
        }
        Set<String> targets = new HashSet<>();
        for (int i = 0; i < n.bindings().size(); i++) {
            binding(n.bindings().get(i), ptr + "/bindings/" + i, targets);
        }
        if (n.instance() != null) {
            instance(n.instance(), ptr + "/instance");
        }
        for (int i = 0; i < n.children().size(); i++) {
            node(n.children().get(i), ptr + "/children/" + i);
        }
    }

    private void widget(UiNode n, String ptr) {
        if (!OmuiFormat.WIDGET_TYPE.matcher(n.type()).matches()) {
            d.error(Code.INVALID_VALUE, E, ptr + "/type", "Invalid widget type '" + n.type() + "'");
            return;
        }
        if (n.typeVersion() < 1 || n.typeVersion() > OmuiFormat.MAX_VERSION) {
            d.error(Code.INVALID_VALUE, E, ptr + "/typeVersion", "typeVersion must be >= 1");
        }
        if (UiWidgets.isNamespaced(n.type())) {
            if (!providerIds.contains(n.type())) {
                d.error(Code.UNRESOLVED_REFERENCE, E, ptr + "/type",
                        "Provider widget '" + n.type() + "' is not declared in manifest providers");
            }
        } else {
            int supported = UiWidgets.supportedVersion(n.type());
            if (supported == 0) {
                d.error(Code.UNSUPPORTED_WIDGET_VERSION, E, ptr + "/type", "Unknown widget type '" + n.type() + "'");
            } else if (n.typeVersion() > supported) {
                d.error(Code.UNSUPPORTED_WIDGET_VERSION, E, ptr + "/typeVersion",
                        n.type() + " v" + n.typeVersion() + " is newer than supported v" + supported);
            }
        }
        boolean isInstance = UiNode.INSTANCE_TYPE.equals(n.type());
        if (isInstance && n.instance() == null) {
            d.error(Code.MISSING_FIELD, E, ptr + "/instance", "Instance node has no instance data");
        } else if (!isInstance && n.instance() != null) {
            d.error(Code.INVALID_VALUE, E, ptr + "/instance", "Only Instance nodes carry instance data");
        }
        if (isInstance && !n.children().isEmpty()) {
            d.error(Code.INVALID_VALUE, E, ptr + "/children", "Instance content goes in named slots, not children");
        }
    }

    private void binding(UiBinding b, String ptr, Set<String> targets) {
        String p = UiPaths.targetProblem(b.target());
        if (p != null) {
            d.error(Code.INVALID_VALUE, E, ptr + "/target", p);
        } else if (b.target().startsWith("style:") && !UiStyleProperties.isKnown(b.target().substring(6))) {
            d.warning(Code.UNKNOWN_FIELD_PRESERVED, E, ptr + "/target", "Unknown style property in '" + b.target() + "'");
        }
        if (!targets.add(b.target())) {
            d.error(Code.DUPLICATE_ID, E, ptr + "/target", "Target '" + b.target() + "' is bound twice");
        }
        if (!UiPaths.isDataPath(b.path())) {
            d.error(Code.INVALID_VALUE, E, ptr + "/path", "Invalid data path '" + b.path() + "'");
        }
        if (b.converter() != null && !OmuiFormat.LOCAL_ID.matcher(b.converter()).matches()) {
            d.error(Code.INVALID_ID, E, ptr + "/converter", "Invalid converter name '" + b.converter() + "'");
        }
    }

    private void instance(ComponentInstance inst, String ptr) {
        if (inst.component().equals(archive.manifest().documentId())) {
            d.error(Code.RECURSIVE_COMPONENT, E, ptr + "/component", "A component cannot instantiate itself");
        } else {
            dependencyRef(inst.component(), UiDependency.Kind.COMPONENT, ptr + "/component");
        }
        for (String p : inst.params().keySet()) {
            if (!UiSelectors.isIdent(p)) {
                d.error(Code.INVALID_ID, E, ptr + "/params", "Invalid parameter name '" + p + "'");
            }
        }
        for (int i = 0; i < inst.overrides().size(); i++) {
            InstanceOverride o = inst.overrides().get(i);
            String op = ptr + "/overrides/" + i;
            if (!UiPaths.isOverrideTarget(o.target())) {
                d.error(Code.INVALID_ID, E, op + "/target", "Invalid override target '" + o.target() + "'");
            }
            style(o.style(), E, op + "/style", archive.dependencies(), d);
            if (i > 0 && inst.overrides().get(i - 1).target().equals(o.target())) {
                d.error(Code.DUPLICATE_ID, E, op + "/target", "Two overrides target '" + o.target() + "'");
            }
        }
        for (Map.Entry<String, List<UiNode>> slot : inst.slots().entrySet()) {
            if (!UiSelectors.isIdent(slot.getKey())) {
                d.error(Code.INVALID_ID, E, ptr + "/slots", "Invalid slot name '" + slot.getKey() + "'");
            }
            List<UiNode> content = slot.getValue();
            for (int i = 0; i < content.size(); i++) {
                node(content.get(i), ptr + "/slots/" + slot.getKey() + "/" + i);
            }
        }
    }

    private void documentRefs(UiDocument doc) {
        for (int i = 0; i < doc.styleSheets().size(); i++) {
            String ref = doc.styleSheets().get(i);
            if (OmuiFormat.isDependencyRef(ref)) {
                dependencyRef(ref, UiDependency.Kind.STYLESHEET, "/styleSheets/" + i);
            } else if (!archive.styles().containsKey(ref)) {
                d.error(Code.UNRESOLVED_REFERENCE, E, "/styleSheets/" + i, "No style sheet '" + ref + "' in the archive");
            }
        }
        String code = doc.codeBehind();
        if (code != null) {
            if (OmuiFormat.isDependencyRef(code)) {
                dependencyRef(code, UiDependency.Kind.SCRIPT, "/codeBehind");
            } else if (!archive.scripts().containsKey(code)) {
                d.error(Code.UNRESOLVED_REFERENCE, E, "/codeBehind", "No script '" + code + "' in the archive");
            }
        }
        boolean isComponent = archive.manifest().kind() == UiManifest.DocumentKind.COMPONENT;
        if (isComponent && doc.component() == null) {
            d.error(Code.INCONSISTENT_MANIFEST, E, "/component", "A component document must declare its contract");
        } else if (!isComponent && doc.component() != null) {
            d.error(Code.INCONSISTENT_MANIFEST, E, "/component", "Only component documents declare a contract");
        }
    }

    private void component(ComponentDef c) {
        params(c.params(), "/component/params");
        Set<String> names = new HashSet<>();
        for (int i = 0; i < c.events().size(); i++) {
            String name = c.events().get(i).name();
            if (!UiSelectors.isIdent(name) || !names.add(name)) {
                d.error(Code.DUPLICATE_ID, E, "/component/events/" + i, "Invalid or duplicate event '" + name + "'");
            }
            params(c.events().get(i).args(), "/component/events/" + i + "/args");
        }
        names.clear();
        for (int i = 0; i < c.slots().size(); i++) {
            UiDocument.Slot s = c.slots().get(i);
            if (!UiSelectors.isIdent(s.name()) || !names.add(s.name())) {
                d.error(Code.DUPLICATE_ID, E, "/component/slots/" + i, "Invalid or duplicate slot '" + s.name() + "'");
            }
            if (!nodeIds.contains(s.host())) {
                d.error(Code.UNRESOLVED_REFERENCE, E, "/component/slots/" + i + "/host", "No node '" + s.host() + "'");
            }
        }
    }

    private void params(List<Param> params, String ptr) {
        Set<String> names = new HashSet<>();
        for (int i = 0; i < params.size(); i++) {
            Param p = params.get(i);
            if (!UiSelectors.isIdent(p.name()) || !names.add(p.name())) {
                d.error(Code.DUPLICATE_ID, E, ptr + "/" + i + "/name", "Invalid or duplicate parameter '" + p.name() + "'");
            }
            if (p.defaultValue() != null && !p.type().accepts(p.defaultValue())) {
                d.error(Code.WRONG_TYPE, E, ptr + "/" + i + "/default",
                        "Default does not fit type " + p.type().wire());
            }
        }
    }

    private void dependencyRef(String id, UiDependency.Kind kind, String ptr) {
        UiDependency dep = archive.dependencies().find(id);
        if (dep == null) {
            d.error(Code.UNRESOLVED_REFERENCE, E, ptr, "'" + id + "' is not in dependencies.json");
        } else if (dep.kind() != kind) {
            d.error(Code.UNRESOLVED_REFERENCE, E, ptr, "'" + id + "' is a " + dep.kind().wire() + ", not a " + kind.wire());
        }
    }

    /**
     * Shared style-map check for nodes, overrides and sheet rules. Asset-valued properties
     * must be {@code none}, a {@code var(--token)} or a dependency id from {@code deps}: every
     * source reference resolves through the dependency table, never through a path.
     */
    static void style(Map<String, UiValue> style, String entry, String ptr, OmuiArchive.UiDependencies deps,
                      UiDiagnostics d) {
        for (Map.Entry<String, UiValue> e : style.entrySet()) {
            String key = e.getKey();
            String at = ptr + "/" + key.replace("~", "~0").replace("/", "~1");
            if (!UiStyleProperties.isKnown(key)) {
                d.warning(Code.UNKNOWN_FIELD_PRESERVED, entry, at, "Unknown style property '" + key + "' preserved");
                continue;
            }
            String problem = UiStyleProperties.problem(key, e.getValue());
            if (problem != null) {
                d.error(Code.INVALID_VALUE, entry, at, key + ": " + problem);
            } else if (UiStyleProperties.spec(key) != null
                    && UiStyleProperties.spec(key).kind() == UiStyleProperties.Kind.ASSET
                    && e.getValue() instanceof UiValue.Str s && !s.value().equals("none")
                    && !s.value().startsWith("var(") && deps.find(s.value()) == null) {
                d.error(Code.UNRESOLVED_REFERENCE, entry, at, "'" + s.value() + "' is not in dependencies.json");
            }
        }
    }
}
