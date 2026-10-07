package com.openmason.main.systems.scripting.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.main.systems.scripting.commands.UiScriptCommands;
import com.openmason.main.systems.uiEditor.automation.UiAutomation;
import com.openmason.main.systems.uiEditor.document.UiEditorDocument;
import com.openmason.main.systems.uiEditor.ops.UiOpBatch;

import java.util.Map;

/**
 * {@code om.ui} against the live UI Editor (#324): the script's queued batch goes through
 * {@link UiAutomation#apply}, the same path as the {@code ui_ops} MCP tool, so the run is one
 * undo step in the document's History. Runs on the UI thread, like every live script.
 */
public final class LiveUiScriptTarget implements UiScriptCommands.Target {

    private final UiAutomation automation;
    private final ObjectMapper mapper;
    private UiEditorDocument applied;
    private com.openmason.main.systems.uiEditor.command.UiHistory.Checkpoint before;

    public LiveUiScriptTarget(UiAutomation automation, ObjectMapper mapper) {
        this.automation = automation;
        this.mapper = mapper;
    }

    @Override
    public Map<String, Object> apply(String doc, JsonNode batch) {
        UiEditorDocument d = automation.document(doc);
        UiOpBatch parsed = UiOpBatch.parse(batch);
        com.openmason.main.systems.uiEditor.command.UiHistory.Checkpoint cp = d.checkpoint();
        Map<String, Object> result = automation.apply(d, parsed);
        applied = d;
        before = cp;
        result.put("doc", d.archive().manifest().documentId());
        return result;
    }

    @Override
    public JsonNode read(String doc, String what, String key, boolean flag) {
        return switch (what) {
            case "documents" -> mapper.valueToTree(automation.documents());
            case "tree" -> automation.tree(automation.document(doc), flag);
            case "get" -> automation.element(automation.document(doc), key, flag);
            default -> throw new IllegalArgumentException("om.ui reads documents, tree or get");
        };
    }

    @Override
    public void rollback() {
        if (applied != null) {
            applied.rollbackTo(before); // no redo entry: the failed run never happened
        }
        applied = null;
    }
}
