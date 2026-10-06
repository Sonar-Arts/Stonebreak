package com.openmason.main.systems.uiEditor.view;

import com.openmason.main.systems.uiEditor.document.UiEditorDocument;
import com.openmason.main.systems.uiEditor.view.widgets.EditorWidgets;
import imgui.ImGui;

import java.util.List;

/**
 * Undo history of the active document, Unreal's Undo History: the redo steps (dimmed) above
 * the current state and the undo steps below; clicking a step undoes or redoes to it.
 */
final class HistoryPanel {

    static final String TITLE = "History###uiHistory";

    private final UiEditorContext ctx;

    HistoryPanel(UiEditorContext ctx) {
        this.ctx = ctx;
    }

    void render() {
        if (!ImGui.begin(TITLE)) {
            ImGui.end();
            return;
        }
        ctx.noteFocus();
        UiEditorDocument doc = ctx.doc();
        if (doc == null) {
            EditorWidgets.emptyState("No document", null);
            ImGui.end();
            return;
        }
        List<String> redo = doc.history().redoLabels();
        List<String> undo = doc.history().undoLabels();
        int target = Integer.MIN_VALUE;
        for (int i = redo.size() - 1; i >= 0; i--) {
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, ImGui.getStyle().getColor(imgui.flag.ImGuiCol.TextDisabled));
            if (ImGui.selectable(redo.get(i) + "##r" + i)) {
                target = i + 1; // redo this many
            }
            ImGui.popStyleColor();
        }
        String state = doc.isDirty() ? "  (current, unsaved)" : "  (current, saved)";
        for (int i = 0; i < undo.size(); i++) {
            if (ImGui.selectable(undo.get(i) + (i == 0 ? state : "") + "##u" + i, i == 0)) {
                target = -i; // undo back to just after this step
            }
        }
        if (ImGui.selectable("Opened document" + (undo.isEmpty() ? state : "") + "##open", undo.isEmpty())) {
            target = -undo.size();
        }
        if (target != Integer.MIN_VALUE && target != 0) {
            for (int i = 0; i < Math.abs(target); i++) {
                boolean ok = target > 0 ? doc.redo() : doc.undo();
                if (!ok) {
                    break;
                }
            }
        }
        ImGui.end();
    }
}
