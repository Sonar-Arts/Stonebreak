package com.openmason.main.systems.scripting.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openmason.main.systems.uiEditor.ops.UiOpBatch;
import com.openmason.main.systems.uiEditor.ops.UiOpException;

import java.util.Map;

/**
 * The scripting side of UI document editing (#324): {@code om.ui.*} calls queue ops of the
 * {@link UiOpBatch} format here; when the script succeeds the queue is applied to the target
 * document as ONE {@code ui_ops} batch (one undo step), through the same validator and executor
 * as the MCP tool, so a script and the equivalent batch produce the same document. A failing
 * script applies nothing.
 *
 * <p>Each queued op is validated against the ops before it at once, so a bad call fails at its
 * own script line rather than at the end.
 */
public final class UiScriptCommands {

    /** The UI documents a script can edit (the live editor); absent headless. */
    public interface Target {
        /** Runs {@code batch} on document {@code doc} (null = active) as one undo step. */
        Map<String, Object> apply(String doc, JsonNode batch);

        /** {@code documents}, {@code tree} (flag = internals) or {@code get} (flag = computed). */
        JsonNode read(String doc, String what, String key, boolean flag);

        /** Undoes the step the last {@link #apply} made (a later step of the run failed). */
        void rollback();
    }

    private final ObjectMapper mapper;
    private Target target;
    private String doc;
    private String label;
    private final ArrayNode ops;
    private boolean flushed;

    public UiScriptCommands(ObjectMapper mapper) {
        this.mapper = mapper;
        this.ops = mapper.createArrayNode();
    }

    /** Connects the live editor (scripts run without one cannot use {@code om.ui}). */
    public void attach(Target target) {
        this.target = target;
    }

    /** Targets document {@code ref} (id, file name or title) instead of the active one. */
    public void use(String ref) {
        if (!ops.isEmpty()) {
            throw new CommandException("om.ui.use() must come before the first om.ui edit",
                "one script run edits one UI document");
        }
        doc = ref == null || ref.isBlank() ? null : ref;
    }

    /** History label for the run's step. */
    public void label(String text) {
        label = text == null || text.isBlank() ? null : text.trim();
    }

    /** Queues one op (JSON object text), validated against the queue so far. */
    public void queue(String opJson) {
        JsonNode op;
        try {
            op = mapper.readTree(opJson);
        } catch (Exception e) {
            throw new CommandException("om.ui: not an op: " + e.getMessage());
        }
        ArrayNode next = ops.deepCopy();
        next.add(op);
        try {
            UiOpBatch.parse(next);
        } catch (UiOpException e) {
            String name = op.path("op").asText("?");
            String msg = stripIndex(e.getMessage());
            if (msg.startsWith(name + ": ")) {
                msg = msg.substring(name.length() + 2);
            }
            throw new CommandException("om.ui." + name + ": " + msg, e.hint());
        }
        ops.add(op);
    }

    private static String stripIndex(String message) {
        return message.replaceFirst("^ops\\[\\d+] ", "");
    }

    public boolean hasOps() {
        return !ops.isEmpty();
    }

    /** The queued batch exactly as {@code ui_ops} would receive it. */
    public ObjectNode batch() {
        ObjectNode b = mapper.createObjectNode();
        b.put("version", UiOpBatch.VERSION);
        if (label != null) {
            b.put("label", label);
        }
        b.set("ops", ops.deepCopy());
        return b;
    }

    public JsonNode read(String what, String key, boolean flag) {
        return requireTarget().read(doc, what, key, flag);
    }

    /**
     * Applies the queue (the script succeeded).
     *
     * @return the batch result, or null when nothing was queued
     */
    public Map<String, Object> flush() {
        if (ops.isEmpty() || flushed) {
            return null;
        }
        Target t = requireTarget();
        try {
            Map<String, Object> result = t.apply(doc, batch());
            flushed = true;
            return result;
        } catch (UiOpException e) {
            throw new CommandException("om.ui: " + e.getMessage(), e.hint());
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new CommandException("om.ui: " + e.getMessage());
        }
    }

    /** Undoes an applied flush (a later deferred step of the run failed). */
    public void rollback() {
        if (flushed && target != null) {
            target.rollback();
            flushed = false;
        }
    }

    private Target requireTarget() {
        if (target == null) {
            throw new CommandException("om.ui edits UI documents in the live Open Mason UI Editor",
                "run the script in the tool (run_python_script) with a UI document open (ui_new / ui_open)");
        }
        return target;
    }
}
