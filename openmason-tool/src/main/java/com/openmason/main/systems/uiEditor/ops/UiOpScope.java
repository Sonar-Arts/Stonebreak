package com.openmason.main.systems.uiEditor.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.main.systems.uiEditor.command.UiCommand;
import com.openmason.main.systems.uiEditor.command.UiCommandException;
import com.openmason.main.systems.uiEditor.command.UiEditContext;
import com.openmason.main.systems.uiEditor.document.NodeLocation;
import com.openmason.main.systems.uiEditor.document.UiTree;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What one op sees while a batch runs: the command context, the aliases bound so far and the
 * component resolver, plus the argument readers that resolve {@code $alias} references and
 * locations against the document as it is at that op.
 */
final class UiOpScope {

    final UiEditContext ctx;
    final UiOpBatch.Components components;
    private final Map<String, List<String>> aliases;

    UiOpScope(UiEditContext ctx, Map<String, List<String>> aliases, UiOpBatch.Components components) {
        this.ctx = ctx;
        this.aliases = aliases;
        this.components = components;
    }

    /** Runs a ready-made editor command as part of this batch's single step. */
    void run(UiCommand command) throws UiCommandException {
        command.apply(ctx);
    }

    // ── keys ────────────────────────────────────────────────────────────────

    /** One element key ({@code $alias} resolved to its first element). */
    String key(JsonNode op, String field) throws UiCommandException {
        return resolve(op.get(field).asText()).getFirst();
    }

    /** One key or an array of keys, aliases expanded. */
    List<String> keys(JsonNode op, String field) throws UiCommandException {
        JsonNode n = op.get(field);
        List<String> out = new ArrayList<>();
        if (n.isArray()) {
            for (JsonNode k : n) {
                out.addAll(resolve(k.asText()));
            }
        } else {
            out.addAll(resolve(n.asText()));
        }
        return out;
    }

    private List<String> resolve(String raw) throws UiCommandException {
        if (raw.startsWith("#")) {
            try {
                return List.of(UiInspector.resolveKey(ctx.root(), raw));
            } catch (IllegalArgumentException e) {
                throw new UiCommandException(e.getMessage());
            }
        }
        if (!raw.startsWith("$")) {
            return List.of(raw);
        }
        String rest = raw.substring(1);
        int slash = rest.indexOf('/');
        String alias = slash < 0 ? rest : rest.substring(0, slash);
        List<String> bound = aliases.get(alias);
        if (bound == null || bound.isEmpty()) {
            throw new UiCommandException("$" + alias + " is not bound (the op that binds it created nothing)");
        }
        if (slash < 0) {
            return bound;
        }
        return bound.stream().map(id -> id + rest.substring(slash)).toList();
    }

    /** The document node a key names, refusing component internals with a teaching message. */
    UiNode documentNode(String key, String what) throws UiCommandException {
        if (key.indexOf('/') >= 0) {
            throw new UiCommandException(what + " works on document elements; '" + key
                + "' is inside a component instance (use set_prop/set_style/set_classes to override it,"
                + " reset_override to reset it)");
        }
        return ctx.require(key);
    }

    /** Document-node keys, each checked to exist. */
    List<String> documentNodes(List<String> keys, String what) throws UiCommandException {
        for (String k : keys) {
            documentNode(k, what);
        }
        return keys;
    }

    /**
     * Checks that an internal key ({@code inst/node}, {@code inst/inner/leaf}) names an element of
     * the component, following nested instances, so a typo never becomes an orphan override.
     * A component whose source cannot be read here is accepted unchecked.
     */
    void requireInternal(String key) throws UiCommandException {
        String[] parts = key.split("/");
        UiNode inst = ctx.require(parts[0]);
        if (inst.instance() == null) {
            throw new UiCommandException("'" + parts[0] + "' is not a component instance, so '" + key
                + "' names nothing (internal keys are <instance>/<node>)");
        }
        OmuiArchive owner = ctx.doc(); // the archive whose dependency table lists inst's component
        for (int i = 1; i < parts.length; i++) {
            String componentId = inst.instance().component();
            OmuiArchive comp = component(componentId, owner);
            if (comp == null) {
                return;
            }
            UiNode n = UiTree.find(comp.document().root(), parts[i]);
            if (n == null) {
                throw new UiCommandException("Component " + componentId + " has no element '" + parts[i]
                    + "'; its elements: " + UiTree.ids(comp.document().root())
                    + " (ui_tree with internals:true lists keys)");
            }
            if (i + 1 < parts.length && n.instance() == null) {
                throw new UiCommandException("'" + String.join("/", java.util.Arrays.copyOf(parts, i + 1))
                    + "' is a " + n.type() + ", not a nested component instance, so '" + key + "' names nothing");
            }
            inst = n;
            owner = comp;
        }
    }

    /**
     * The source of component {@code id} as {@code owner} sees it: embedded in {@code owner}, else
     * embedded in the edited document (an export that collected everything), else the project's.
     */
    private OmuiArchive component(String id, OmuiArchive owner) {
        OmuiArchive embedded = embedded(owner, id);
        if (embedded == null && owner != ctx.doc()) {
            embedded = embedded(ctx.doc(), id);
        }
        return embedded != null ? embedded : components.archive(id);
    }

    private static OmuiArchive embedded(OmuiArchive in, String id) {
        com.openmason.engine.format.omui.UiDependency row = in.dependencies().find(id);
        if (row == null || row.mode() != com.openmason.engine.format.omui.UiDependency.Mode.EMBEDDED
            || row.entry() == null || !in.assets().containsKey(row.entry())) {
            return null;
        }
        try {
            return com.openmason.engine.format.omui.OmuiReader.read(in.assets().get(row.entry()).toArray()).archive();
        } catch (Exception e) {
            return null;
        }
    }

    // ── locations ───────────────────────────────────────────────────────────

    /**
     * Where an op places content: {@code before}/{@code after} a sibling, else {@code parent}
     * (default the root) with optional {@code slot} and {@code index} (default the end).
     */
    NodeLocation location(JsonNode op) throws UiCommandException {
        for (String rel : new String[]{"before", "after"}) {
            if (op.hasNonNull(rel)) {
                String sibling = key(op, rel);
                documentNode(sibling, rel);
                NodeLocation at = UiTree.locate(ctx.root(), sibling);
                if (at == null) {
                    throw new UiCommandException("The root has no siblings; use parent instead of " + rel);
                }
                return rel.equals("before") ? at : at.at(at.index() + 1);
            }
        }
        String parent = op.hasNonNull("parent") ? key(op, "parent") : ctx.root().id();
        documentNode(parent, "parent");
        String slot = op.hasNonNull("slot") ? op.get("slot").asText() : null;
        int index = op.hasNonNull("index") ? op.get("index").asInt() : Integer.MAX_VALUE;
        if (index < 0) {
            throw new UiCommandException("index must be 0 or more");
        }
        return new NodeLocation(parent, slot, index);
    }

    // ── values ──────────────────────────────────────────────────────────────

    /** A value field; JSON null and absence are {@code null} (= clear). */
    static UiValue value(JsonNode op, String field) {
        JsonNode n = op.get(field);
        return n == null || n.isNull() ? null : UiJson.value(n);
    }

    static String optText(JsonNode op, String field) {
        JsonNode n = op.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    static List<String> strings(JsonNode op, String field) {
        List<String> out = new ArrayList<>();
        JsonNode n = op.get(field);
        if (n != null && n.isArray()) {
            n.forEach(s -> out.add(s.asText()));
        }
        return out;
    }
}
