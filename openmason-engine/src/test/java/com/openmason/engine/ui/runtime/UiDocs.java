package com.openmason.engine.ui.runtime;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiDocument;
import com.openmason.engine.format.omui.UiManifest;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiStyleSheet;
import com.openmason.engine.format.omui.UiValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Terse builders for runtime tests. Not a test class (no {@code Test} affix). */
public final class UiDocs {

    private UiDocs() {
    }

    /** Mutable node builder. */
    public static final class N {
        final String id;
        final String type;
        String name;
        final List<String> classes = new ArrayList<>();
        final Map<String, UiValue> props = new LinkedHashMap<>();
        final Map<String, UiValue> style = new LinkedHashMap<>();
        final List<UiNode.UiBinding> bindings = new ArrayList<>();
        final List<N> children = new ArrayList<>();
        String dataSource;
        UiNode.ComponentInstance instance;

        N(String id, String type) {
            this.id = id;
            this.type = type;
        }

        public N name(String n) {
            name = n;
            return this;
        }

        public N cls(String... c) {
            classes.addAll(List.of(c));
            return this;
        }

        public N prop(String k, Object v) {
            props.put(k, value(v));
            return this;
        }

        public N style(String k, Object v) {
            style.put(k, value(v));
            return this;
        }

        public N bind(String target, String path) {
            bindings.add(new UiNode.UiBinding(target, path));
            return this;
        }

        /** A binding with an explicit mode and optional converter (#289). */
        public N bind(String target, String path, UiNode.BindingMode mode, String converter) {
            bindings.add(new UiNode.UiBinding(target, path, mode, converter, Map.of()));
            return this;
        }

        public N data(String path) {
            dataSource = path;
            return this;
        }

        public N kids(N... c) {
            children.addAll(List.of(c));
            return this;
        }

        public N instance(UiNode.ComponentInstance i) {
            instance = i;
            return this;
        }

        public UiNode build() {
            return new UiNode(id, name, type, 1, classes, props, style, dataSource, bindings, instance,
                children.stream().map(N::build).toList(), Map.of());
        }
    }

    public static N node(String id, String type) {
        return new N(id, type);
    }

    public static N box(String id) {
        return node(id, "Box");
    }

    public static N label(String id, String text) {
        return node(id, "Label").prop("text", text);
    }

    /** An {@code Instance} node of {@code component} with params. */
    public static N inst(String id, String component, Map<String, Object> params,
                         List<UiNode.InstanceOverride> overrides, Map<String, List<N>> slots) {
        Map<String, UiValue> p = new LinkedHashMap<>();
        params.forEach((k, v) -> p.put(k, value(v)));
        Map<String, List<UiNode>> s = new LinkedHashMap<>();
        slots.forEach((k, v) -> s.put(k, v.stream().map(N::build).toList()));
        return node(id, UiNode.INSTANCE_TYPE).instance(new UiNode.ComponentInstance(component, p, overrides, s, Map.of()));
    }

    public static N inst(String id, String component, Map<String, Object> params) {
        return inst(id, component, params, List.of(), Map.of());
    }

    public static UiNode.InstanceOverride override(String target, Map<String, Object> style, List<String> add,
                                                   List<String> remove) {
        Map<String, UiValue> s = new LinkedHashMap<>();
        style.forEach((k, v) -> s.put(k, value(v)));
        return new UiNode.InstanceOverride(target, Map.of(), s, add, remove, Map.of());
    }

    public static UiNode.InstanceOverride overrideProps(String target, Map<String, Object> props) {
        Map<String, UiValue> p = new LinkedHashMap<>();
        props.forEach((k, v) -> p.put(k, value(v)));
        return new UiNode.InstanceOverride(target, p, Map.of(), List.of(), List.of(), Map.of());
    }

    /** A screen document with in-archive sheets referenced in order. */
    public static OmuiArchive screen(String id, N root, UiStyleSheet... sheets) {
        return archive(id, UiManifest.DocumentKind.SCREEN, root, null, List.of(), sheets);
    }

    /** A screen whose sheet list mixes dependency ids and in-archive sheets. */
    public static OmuiArchive screen(String id, N root, List<String> sheetRefs, UiStyleSheet... inArchive) {
        return archive(id, UiManifest.DocumentKind.SCREEN, root, null, sheetRefs, inArchive);
    }

    public static OmuiArchive component(String id, N root, UiDocument.ComponentDef def, UiStyleSheet... sheets) {
        return archive(id, UiManifest.DocumentKind.COMPONENT, root, def, List.of(), sheets);
    }

    private static OmuiArchive archive(String id, UiManifest.DocumentKind kind, N root, UiDocument.ComponentDef def,
                                       List<String> extraRefs, UiStyleSheet... sheets) {
        List<String> refs = new ArrayList<>(extraRefs);
        for (UiStyleSheet s : sheets) {
            refs.add(s.id());
        }
        OmuiArchive a = OmuiArchive.of(UiManifest.create(id, kind, id),
            new UiDocument(root.build(), refs, null, def, Map.of()));
        for (UiStyleSheet s : sheets) {
            a = a.withStyle(s);
        }
        return a;
    }

    public static UiDocument.ComponentDef contract(List<UiDocument.Param> params, List<UiDocument.Slot> slots) {
        return new UiDocument.ComponentDef(params, List.of(), slots, Map.of());
    }

    public static UiDocument.Param param(String name, com.openmason.engine.format.omui.ValueType type, Object def) {
        return new UiDocument.Param(name, type, def == null ? null : value(def), Map.of());
    }

    public static UiDocument.Slot slot(String name, String host) {
        return new UiDocument.Slot(name, host, Map.of());
    }

    /** {@code sheet("s", "--accent", "#fff", ...)} is awkward; build rules with {@link #rule}. */
    public static UiStyleSheet sheet(String id, Map<String, Object> variables, List<String> customStates,
                                     UiStyleSheet.StyleRule... rules) {
        Map<String, UiValue> vars = new LinkedHashMap<>();
        variables.forEach((k, v) -> vars.put(k, value(v)));
        return new UiStyleSheet(id, vars, customStates, List.of(rules), Map.of());
    }

    public static UiStyleSheet sheet(String id, UiStyleSheet.StyleRule... rules) {
        return sheet(id, Map.of(), List.of(), rules);
    }

    /** {@code rule(".a", "width", 10, "color", "#FFFFFF")}. */
    public static UiStyleSheet.StyleRule rule(String selector, Object... kv) {
        Map<String, UiValue> style = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            style.put((String) kv[i], value(kv[i + 1]));
        }
        return new UiStyleSheet.StyleRule(selector, style, List.of(), Map.of());
    }

    public static UiValue value(Object v) {
        return switch (v) {
            case UiValue u -> u;
            case String s -> UiValue.of(s);
            case Boolean b -> UiValue.of(b);
            case Number n -> UiValue.of(n.doubleValue());
            default -> throw new IllegalArgumentException(String.valueOf(v));
        };
    }

    /** {@code doc} with extra {@code requires} features and host contracts ({@code "id@version"}, {@code "id@version?"} = optional). */
    public static OmuiArchive declare(OmuiArchive doc, List<String> features, String... hostApis) {
        UiManifest m = doc.manifest();
        List<String> req = new ArrayList<>(m.requires());
        req.addAll(features);
        List<UiManifest.HostRequirement> apis = new ArrayList<>(m.hostApis());
        for (String h : hostApis) {
            boolean optional = h.endsWith("?");
            String spec = optional ? h.substring(0, h.length() - 1) : h;
            int at = spec.indexOf('@');
            apis.add(new UiManifest.HostRequirement(spec.substring(0, at), Integer.parseInt(spec.substring(at + 1)),
                optional, Map.of()));
        }
        return doc.withManifest(new UiManifest(m.schemaVersion(), m.documentId(), m.kind(), m.displayName(),
            m.uiApi(), m.layoutSemantics(), req, apis, m.providers(), m.unknown()));
    }

    /** True when any diagnostic has {@code code}. */
    public static boolean has(UiDocumentInstance ui, UiRuntimeDiagnostic.Code code) {
        return ui.diagnostics().stream().anyMatch(d -> d.code() == code);
    }

    /** Measures text as 8 logical px per character, 16 px tall. */
    public static final ContentMeasurer FIXED_TEXT = (el, w, wMode, h, hMode, scale, out) -> {
        out[0] = el.text("text").length() * 8f * scale;
        out[1] = 16f * scale;
    };
}
