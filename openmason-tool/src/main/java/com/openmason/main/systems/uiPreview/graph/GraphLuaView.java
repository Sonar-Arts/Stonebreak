package com.openmason.main.systems.uiPreview.graph;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.ui.graph.CompiledGraph;
import com.openmason.engine.ui.graph.GraphDiagnostic;
import com.openmason.engine.ui.graph.SourceMap;
import com.openmason.main.systems.themes.utils.ThemeColors;
import com.openmason.main.systems.themes.utils.ThemeColors.Tone;
import com.openmason.main.systems.themes.utils.ThemedWidgets;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiCol;

/**
 * "Show Lua": a read-only view of what the graph compiles to (release build, as shipped), with
 * the lines of the selected node highlighted and scrolled into view when the selection moves.
 * Compiling errors are listed instead when the graph does not compile.
 */
final class GraphLuaView {

    private OmuiArchive compiledFor;
    private CompiledGraph compiled;
    private String[] lines = new String[0];
    private String scrolledTo = "";

    void draw(GraphEditorState st, float height) {
        OmuiArchive doc = st.editor.document();
        if (compiledFor != doc) {
            compiledFor = doc;
            compiled = st.editor.compile(false);
            lines = compiled.ok() ? compiled.lua().split("\n", -1) : new String[0];
        }
        if (ImGui.beginChild("##graphLua", 0, height, true)) {
            if (!compiled.ok()) {
                ThemedWidgets.statusText(Tone.ERROR, "The graph does not compile:");
                for (GraphDiagnostic d : compiled.errors()) {
                    ImGui.textWrapped(d.toString());
                }
            } else {
                body(st);
            }
        }
        ImGui.endChild();
    }

    private void body(GraphEditorState st) {
        if (ImGui.smallButton("Copy Lua")) {
            ImGui.setClipboardText(compiled.lua());
        }
        SourceMap map = compiled.sourceMap();
        String sel = st.single();
        String selKey = sel == null ? "" : st.body + "/" + sel;
        ImDrawList dl = ImGui.getWindowDrawList();
        float lineH = ImGui.getTextLineHeightWithSpacing();
        int first = sel == null || map == null ? 0 : map.lineOf(st.body, sel);
        int highlightColor = ThemeColors.u32(ImGuiCol.CheckMark, 0.22f);
        for (int i = 0; i < lines.length; i++) {
            SourceMap.Location loc = map == null ? null : map.at(i + 1);
            boolean hot = sel != null && loc != null && loc.function().equals(st.body) && loc.node().equals(sel);
            ImVec2 p = ImGui.getCursorScreenPos();
            if (hot) {
                dl.addRectFilled(p.x - 2, p.y, p.x + ImGui.getContentRegionAvailX(), p.y + lineH, highlightColor);
            }
            ImGui.textUnformatted(String.format("%4d  %s", i + 1, lines[i]));
        }
        if (!selKey.equals(scrolledTo)) {
            scrolledTo = selKey;
            if (first > 0) {
                ImGui.setScrollY(Math.max(0, (first - 4) * lineH));
            }
        }
    }
}
