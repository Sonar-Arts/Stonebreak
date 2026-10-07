package com.openmason.main.systems.uiEditor.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.main.systems.uiEditor.command.UiCommand;
import com.openmason.main.systems.uiEditor.command.UiCommandException;
import com.openmason.main.systems.uiEditor.command.UiEditContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The UI editor's automation frontend (#324): a JSON batch of edit ops,
 * {@code {"ops":[{"op":"create","type":"Label",...}, ...], "label":"..."}}, shared by the MCP
 * {@code ui_ops} tool and the Python {@code om.ui} API so both have one validator and one
 * executor.
 *
 * <p><b>Validate all, then apply as one command.</b> {@link #parse} checks every op's name,
 * fields and alias references without touching a document. {@link #command} turns the batch
 * into a single {@link UiCommand}; run through {@code UiEditorDocument.execute} it is exactly
 * one undo step, and when any op fails the history discards the whole step, so the document is
 * untouched and {@link #failure()} names the op and the reason.
 *
 * <p>Ops address elements by element key ({@code panel}, or {@code quit/label} inside a
 * component instance — edits there become instance overrides). Ops that create elements accept
 * {@code "as":"name"}; later ops refer to the created element as {@code $name} (or
 * {@code $name/inner} for its internals), since minted ids are not known in advance.
 */
public final class UiOpBatch {

    /** Batch format version. */
    public static final int VERSION = 1;

    private static final Pattern ALIAS = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]{0,63}");

    /** Finds the dependency row for a component the document does not list yet (project components). */
    @FunctionalInterface
    public interface Components {
        /**
         * @return the component's row followed by every row its closure needs (nested components
         *         and their shared assets), or empty when no such component is known
         */
        java.util.List<UiDependency> rows(String componentId) throws UiCommandException;

        /**
         * The source of a shared component (to check keys inside its instances), or null when
         * it cannot be read here; embedded components are read from the document itself.
         */
        default com.openmason.engine.format.omui.OmuiArchive archive(String componentId) {
            return null;
        }

        Components NONE = id -> java.util.List.of();
    }

    /** Which op failed while the batch ran, and why. */
    public record Failure(int opIndex, String op, String message) {
    }

    private final List<JsonNode> ops;
    private final String label;
    private final Map<String, List<String>> aliases = new LinkedHashMap<>();
    private Failure failure;

    private UiOpBatch(List<JsonNode> ops, String label) {
        this.ops = ops;
        this.label = label;
    }

    /**
     * Validates a batch: {@code {"version":1, "ops":[...], "label":"..."}} or a bare op array.
     *
     * @throws UiOpException naming the first bad op (nothing was run)
     */
    public static UiOpBatch parse(JsonNode root) {
        JsonNode opsNode;
        String label = null;
        if (root != null && root.isArray()) {
            opsNode = root;
        } else if (root != null && root.isObject()) {
            int version = root.path("version").asInt(VERSION);
            if (version != VERSION) {
                throw new UiOpException(-1, "Unsupported UI op batch version " + version,
                    "this build reads version " + VERSION);
            }
            opsNode = root.get("ops");
            JsonNode l = root.get("label");
            label = l != null && l.isTextual() && !l.asText().isBlank() ? l.asText().trim() : null;
        } else {
            throw new UiOpException(-1, "An op batch is an object {\"ops\":[...]} or an array of ops", null);
        }
        if (opsNode == null || !opsNode.isArray() || opsNode.isEmpty()) {
            throw new UiOpException(-1, "The batch has no ops",
                "\"ops\" is a non-empty array of {\"op\":...}; ops: " + UiOpCatalog.names());
        }
        List<JsonNode> list = new ArrayList<>();
        Set<String> defined = new HashSet<>();
        for (int i = 0; i < opsNode.size(); i++) {
            JsonNode op = opsNode.get(i);
            validate(i, op, defined);
            list.add(op);
        }
        return new UiOpBatch(List.copyOf(list), label);
    }

    private static void validate(int i, JsonNode op, Set<String> defined) {
        if (op == null || !op.isObject()) {
            throw new UiOpException(i, "ops[" + i + "] must be an object {\"op\":...}", null);
        }
        String name = op.path("op").asText("");
        UiOpCatalog.Spec spec = UiOpCatalog.get(name);
        if (spec == null) {
            String near = UiOpCatalog.closest(name);
            throw new UiOpException(i, name.isBlank() ? "ops[" + i + "] has no \"op\"" : "Unknown op '" + name + "'"
                + (near != null ? " (did you mean '" + near + "'?)" : ""), "ops: " + UiOpCatalog.names());
        }
        Iterator<Map.Entry<String, JsonNode>> it = op.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> f = it.next();
            String field = f.getKey();
            if (field.equals("op")) {
                continue;
            }
            if (field.equals("as")) {
                if (!spec.binds()) {
                    throw new UiOpException(i, name + " creates nothing to bind with \"as\"", null);
                }
                if (!f.getValue().isTextual() || !ALIAS.matcher(f.getValue().asText()).matches()) {
                    throw new UiOpException(i, "\"as\" must be a name like \"title\" (letters, digits, _ -)", null);
                }
                continue;
            }
            UiOpField type = spec.fields().get(field);
            if (type == null) {
                throw new UiOpException(i, name + " has no field '" + field + "'", spec.usage());
            }
            if (!type.accepts(f.getValue())) {
                throw new UiOpException(i, name + "." + field + " must be " + type.expected, spec.usage());
            }
            checkRefs(i, f.getValue(), type, defined);
        }
        for (String req : spec.required()) {
            JsonNode v = op.get(req);
            if (v == null || v.isMissingNode()) {
                throw new UiOpException(i, name + " needs '" + req + "'", spec.usage());
            }
        }
        try {
            spec.check().check(op);
        } catch (IllegalArgumentException e) {
            throw new UiOpException(i, name + ": " + e.getMessage(), spec.usage());
        }
        if (op.hasNonNull("as")) {
            defined.add(op.get("as").asText());
        }
    }

    private static void checkRefs(int i, JsonNode value, UiOpField type, Set<String> defined) {
        if (type != UiOpField.KEY && type != UiOpField.KEYS) {
            return;
        }
        List<JsonNode> refs = new ArrayList<>();
        if (value.isArray()) {
            value.forEach(refs::add);
        } else {
            refs.add(value);
        }
        for (JsonNode r : refs) {
            String s = r.asText();
            if (s.startsWith("$")) {
                String alias = head(s.substring(1));
                if (!defined.contains(alias)) {
                    throw new UiOpException(i, "'" + s + "' refers to $" + alias + ", which no earlier op binds",
                        "bind it first with \"as\":\"" + alias + "\" on a create, add_instance, duplicate or wrap");
                }
            }
        }
    }

    private static String head(String s) {
        int slash = s.indexOf('/');
        return slash < 0 ? s : s.substring(0, slash);
    }

    public int size() {
        return ops.size();
    }

    /** The op names in order (traces, history labels). */
    public List<String> opNames() {
        return ops.stream().map(o -> o.get("op").asText()).toList();
    }

    /** The undo step's label: the batch's own, else one naming the edit. */
    public String label() {
        if (label != null) {
            return label;
        }
        return ops.size() == 1 ? "Agent: " + opNames().getFirst() : "Agent: " + ops.size() + " edits";
    }

    /**
     * The whole batch as one command. Each run starts with fresh aliases and clears
     * {@link #failure()}; a failing op throws (the history then keeps nothing) after recording
     * which op it was.
     */
    public UiCommand command(Components components) {
        Components rows = components == null ? Components.NONE : components;
        String text = label();
        return UiCommand.of(text, ctx -> run(ctx, rows));
    }

    private void run(UiEditContext ctx, Components rows) throws UiCommandException {
        failure = null;
        aliases.clear();
        Set<String> baseline = writerErrors(ctx.doc());
        List<com.openmason.engine.format.omui.OmuiArchive> after = new ArrayList<>();
        UiOpScope scope = new UiOpScope(ctx, aliases, rows);
        for (int i = 0; i < ops.size(); i++) {
            JsonNode op = ops.get(i);
            String name = op.get("op").asText();
            try {
                UiOpCatalog.get(name).apply().apply(scope, op);
                if (op.hasNonNull("as")) {
                    aliases.put(op.get("as").asText(), List.copyOf(ctx.selection()));
                }
            } catch (UiCommandException | RuntimeException e) {
                throw fail(i, name, e.getMessage() == null ? e.toString() : e.getMessage(), e);
            }
            after.add(ctx.doc());
        }
        // The writer refuses a document with format errors (undeclared providers, bad binding paths,
        // unknown parameters, recursive components, ...): an edit that would make the document
        // unsaveable fails here, at the op that introduced the error, instead of at the next save.
        Set<String> introduced = writerErrors(ctx.doc());
        introduced.removeAll(baseline);
        if (!introduced.isEmpty()) {
            for (int i = 0; i < after.size(); i++) {
                Set<String> errs = writerErrors(after.get(i));
                errs.removeAll(baseline);
                if (!errs.isEmpty()) {
                    throw fail(i, ops.get(i).get("op").asText(), "the document would not save: "
                        + String.join("; ", errs), null);
                }
            }
        }
    }

    private UiCommandException fail(int i, String name, String msg, Throwable cause) {
        failure = new Failure(i, name, msg);
        return new UiCommandException("op " + i + " (" + name + "): " + msg, cause);
    }

    /** The errors {@code OmuiWriter} would refuse the document for (with used features declared, as the history does). */
    private static Set<String> writerErrors(com.openmason.engine.format.omui.OmuiArchive doc) {
        Set<String> out = new java.util.LinkedHashSet<>();
        for (com.openmason.engine.format.omui.UiDiagnostic d : com.openmason.engine.format.omui.OmuiValidator.validate(
            com.openmason.main.systems.uiEditor.command.UiHistory.withRequiredFeatures(doc))) {
            if (d.isError()) {
                // keyed without the JSON pointer: a pre-existing error must not look new because an
                // op moved the node it is about
                out.add(d.message() + " (" + d.entry() + ")");
            }
        }
        return out;
    }

    /** Set when the last run failed; null after a successful run. */
    public Failure failure() {
        return failure;
    }

    /** Alias → element ids bound by the last successful run ({@code "as"}). */
    public Map<String, List<String>> aliases() {
        return Map.copyOf(aliases);
    }
}
