package com.openmason.main.systems.uiPreview.graph;

import com.openmason.engine.ui.script.GraphDebugger;
import com.openmason.main.systems.themes.utils.ThemeColors.Tone;
import com.openmason.main.systems.themes.utils.ThemedWidgets;
import imgui.ImGui;

import java.util.ArrayList;
import java.util.List;

/**
 * Debug section: paused tasks (Continue / Continue all), breakpoints and the recent trace of the
 * running preview. Clicking a trace entry or a breakpoint selects its node. Needs the debug build
 * the preview compiles graphs with; without a debugger it says so.
 */
final class GraphDebugPanel {

    private static final int TRACE_ROWS = 60;

    /** A debugger location key ({@code node} or {@code fn:f/node}) split into body and node id. */
    record Location(String function, String node) {
        static Location parse(String loc) {
            if (loc.startsWith("fn:")) {
                int slash = loc.indexOf('/');
                if (slash > 3) {
                    return new Location(loc.substring(3, slash), loc.substring(slash + 1));
                }
            }
            return new Location("", loc);
        }
    }

    void draw(GraphEditorState st) {
        GraphDebugger dbg = st.debugger;
        if (dbg == null) {
            ImGui.textWrapped("No debugger: open a document in the preview (debug build) to see live hits, values and breakpoints.");
            return;
        }
        String graph = st.graphId();
        ImGui.separatorText("Paused");
        List<GraphDebugger.Paused> paused = dbg.paused();
        if (paused.isEmpty()) {
            ImGui.textDisabled("Nothing is paused.");
        }
        for (GraphDebugger.Paused p : paused) {
            ImGui.pushID(Long.hashCode(p.token()));
            ThemedWidgets.statusText(Tone.WARNING, "paused at " + p.graph() + "#" + p.node());
            ImGui.textDisabled(p.context());
            if (p.graph().equals(graph)) {
                ImGui.sameLine();
                if (ImGui.smallButton("Show")) {
                    Location l = Location.parse(p.node());
                    st.jumpTo(l.function(), l.node());
                }
            }
            if (ImGui.smallButton("Continue")) {
                dbg.resume(p.token());
            }
            ImGui.popID();
        }
        if (!paused.isEmpty() && ImGui.button("Continue all")) {
            dbg.resumeAll();
        }

        ImGui.separatorText("Breakpoints");
        List<String> mine = new ArrayList<>();
        for (String k : dbg.breakpoints()) {
            if (k.startsWith(graph + "#")) {
                mine.add(k.substring(graph.length() + 1));
            }
        }
        if (mine.isEmpty()) {
            ImGui.textDisabled("None. Select a node and press F9.");
        }
        for (String loc : mine) {
            ImGui.pushID(loc);
            if (ImGui.smallButton("x")) {
                dbg.setBreakpoint(graph, loc, false);
            }
            ImGui.sameLine();
            if (ImGui.selectable(loc, false)) {
                Location l = Location.parse(loc);
                st.jumpTo(l.function(), l.node());
            }
            ImGui.popID();
        }

        ImGui.separatorText("Recent trace");
        if (ImGui.smallButton("Clear history")) {
            dbg.clearHistory();
        }
        List<String> trace = dbg.trace();
        int shown = 0;
        for (int i = trace.size() - 1; i >= 0 && shown < TRACE_ROWS; i--, shown++) {
            String entry = trace.get(i);
            ImGui.pushID(i);
            boolean ours = entry.startsWith(graph + "#");
            if (ImGui.selectable(entry, false) && ours) {
                Location l = Location.parse(entry.substring(graph.length() + 1));
                st.jumpTo(l.function(), l.node());
            }
            ImGui.popID();
        }
        if (trace.isEmpty()) {
            ImGui.textDisabled("Nothing has run yet. Interact with the preview.");
        }
    }
}
