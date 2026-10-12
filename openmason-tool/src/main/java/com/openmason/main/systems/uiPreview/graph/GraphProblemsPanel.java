package com.openmason.main.systems.uiPreview.graph;

import com.openmason.engine.ui.graph.GraphDiagnostic;
import com.openmason.main.systems.themes.utils.ThemeColors.Tone;
import com.openmason.main.systems.themes.utils.ThemedWidgets;
import imgui.ImGui;

import java.util.List;

/** The graph's diagnostics: click one to select its node (switching body when needed) and frame it. */
final class GraphProblemsPanel {

    void draw(GraphEditorState st) {
        List<GraphDiagnostic> all = st.editor.diagnostics();
        if (all.isEmpty()) {
            ThemedWidgets.statusText(Tone.SUCCESS, "No problems");
            return;
        }
        int i = 0;
        for (GraphDiagnostic d : all) {
            Tone tone = switch (d.severity()) {
                case ERROR -> Tone.ERROR;
                case WARNING -> Tone.WARNING;
                case INFO -> null;
            };
            String where = d.node().isEmpty() ? "graph" : (d.function().isEmpty() ? "" : d.function() + "/") + d.node()
                + (d.port().isEmpty() ? "" : "." + d.port());
            ImGui.pushID(i++);
            if (ImGui.selectable(d.severity() + "  " + where + "\n   " + d.message(), false) && !d.node().isEmpty()) {
                st.jumpTo(d.function(), d.node());
            }
            ImGui.popID();
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip(d.code() + (d.node().isEmpty() ? "" : "\nClick to select the node"));
            }
            if (tone != null) {
                ImGui.separator();
            }
        }
    }
}
