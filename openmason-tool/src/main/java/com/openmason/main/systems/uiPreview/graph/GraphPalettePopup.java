package com.openmason.main.systems.uiPreview.graph;

import com.openmason.engine.format.omui.UiGraph.GraphNode;
import com.openmason.engine.ui.graph.NodeKind;
import com.openmason.engine.ui.graph.NodeKinds;
import com.openmason.engine.ui.graph.NodePorts;
import com.openmason.engine.ui.graph.PortSpec;
import com.openmason.engine.ui.graph.edit.GraphEditor;
import com.openmason.engine.ui.graph.edit.PaletteEntry;
import imgui.ImGui;
import imgui.flag.ImGuiKey;
import imgui.type.ImString;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The node palette popup: a search field over {@link GraphEditor#palette}, keyboard driven
 * (type to filter, arrows, Enter adds at the spot the popup was opened). Opened from a right
 * click on empty canvas, or by releasing a dragged pin over empty space, in which case the list
 * keeps only nodes with a port {@link PinConnectPicker} can link, and the new node is wired to the
 * dragged pin.
 */
final class GraphPalettePopup {

    private static final String POPUP = "##nodePalette";
    private static final int MAX_ROWS = 200;

    /** The pin a link drag started from. */
    record LinkSource(String node, PortSpec port, boolean output) {
    }

    private final ImString query = new ImString(128);
    private final PaletteSelection selection = new PaletteSelection();
    private boolean wantOpen;
    private boolean focusSearch;
    private double graphX;
    private double graphY;
    private LinkSource link;
    private String lastQuery;
    private String lastBody;
    private List<PaletteEntry> results = List.of();

    void openAt(double gx, double gy, LinkSource from) {
        graphX = gx;
        graphY = gy;
        link = from;
        wantOpen = true;
        focusSearch = true;
        query.set("");
        lastQuery = null;
    }

    /** Draws the popup when open; returns the new node id (or null) the author just added. */
    String draw(GraphEditorState st) {
        if (wantOpen) {
            ImGui.openPopup(POPUP);
            wantOpen = false;
        }
        String added = null;
        ImGui.setNextWindowSize(320, 360);
        if (!ImGui.beginPopup(POPUP)) {
            return null;
        }
        GraphEditor ed = st.editor;
        ImGui.textDisabled(link == null ? "Add node" : "Add node and connect to " + link.port().name());
        if (focusSearch) {
            ImGui.setKeyboardFocusHere();
            focusSearch = false;
        }
        ImGui.setNextItemWidth(-1);
        ImGui.inputText("##paletteSearch", query);
        refresh(ed, st.body);
        if (ImGui.isKeyPressed(ImGuiKey.DownArrow)) {
            selection.move(1);
        }
        if (ImGui.isKeyPressed(ImGuiKey.UpArrow)) {
            selection.move(-1);
        }
        PaletteEntry choice = null;
        if (ImGui.isKeyPressed(ImGuiKey.Enter) || ImGui.isKeyPressed(ImGuiKey.KeypadEnter)) {
            choice = selection.selected(results);
        }
        if (link == null && ImGui.button("Add comment here")) {
            ed.addComment(st.body, graphX, graphY, 260, 140, "Comment");
            ImGui.closeCurrentPopup();
        }
        if (ImGui.beginChild("##paletteList", 0, 0, true)) {
            String category = null;
            int rows = Math.min(results.size(), MAX_ROWS);
            for (int i = 0; i < rows; i++) {
                PaletteEntry e = results.get(i);
                if (!e.category().equals(category)) {
                    category = e.category();
                    ImGui.textDisabled(category);
                }
                ImGui.pushID(i);
                if (ImGui.selectable("  " + e.title(), selection.isSelected(i))) {
                    choice = e;
                }
                if (selection.isSelected(i) && (ImGui.isKeyPressed(ImGuiKey.DownArrow)
                    || ImGui.isKeyPressed(ImGuiKey.UpArrow))) {
                    ImGui.setScrollHereY();
                }
                if (ImGui.isItemHovered() && !e.doc().isEmpty()) {
                    ImGui.setTooltip(e.doc());
                }
                ImGui.popID();
            }
            if (results.isEmpty()) {
                ImGui.textDisabled("No matching nodes");
            } else if (results.size() > rows) {
                ImGui.textDisabled("... " + (results.size() - rows) + " more: keep typing");
            }
        }
        ImGui.endChild();
        if (choice != null) {
            added = add(st, choice);
            ImGui.closeCurrentPopup();
        }
        ImGui.endPopup();
        return added;
    }

    private String add(GraphEditorState st, PaletteEntry entry) {
        GraphEditor ed = st.editor;
        double x = graphX;
        double y = graphY;
        if (link != null && !link.output()) {
            x -= 170; // an output feeds the dragged input: land the new node to its left
        }
        String id = ed.addNode(st.body, entry, x, y);
        if (link != null) {
            NodePorts ports = ed.ports(st.body, id);
            String port = PinConnectPicker.pick(link.port(), link.output(), ports);
            if (port == null) {
                st.status = "No compatible port on " + entry.title();
            } else {
                String why = ed.connect(st.body, link.node(), link.port().name(), id, port);
                st.status = why == null ? "" : why;
            }
        }
        return id;
    }

    private void refresh(GraphEditor ed, String body) {
        String q = query.get();
        if (q.equals(lastQuery) && body.equals(lastBody)) {
            return;
        }
        boolean typed = lastQuery != null && !q.equals(lastQuery);
        lastQuery = q;
        lastBody = body;
        List<PaletteEntry> all = ed.palette(body, q);
        if (link == null) {
            results = all;
        } else {
            List<PaletteEntry> keep = new ArrayList<>();
            for (PaletteEntry e : all) {
                NodePorts ports = portsOf(ed, body, e);
                if (PinConnectPicker.compatible(link.port(), link.output(), ports)) {
                    keep.add(e);
                }
            }
            results = keep;
        }
        selection.setCount(results.size());
        if (typed) {
            selection.reset();
        }
    }

    /** Ports an entry's node would have, resolved on a throwaway node. */
    private static NodePorts portsOf(GraphEditor ed, String body, PaletteEntry e) {
        NodeKind k = NodeKinds.get(e.kind());
        if (k == null) {
            return NodePorts.NONE;
        }
        try {
            GraphNode probe = new GraphNode("probe", e.kind(), k.version(), 0, 0, Map.of(), e.props(), Map.of());
            return k.ports(ed.context(body), probe);
        } catch (RuntimeException ex) {
            return NodePorts.NONE;
        }
    }
}
