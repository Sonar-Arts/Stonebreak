package com.openmason.main.systems.uiPreview.graph;

import com.openmason.engine.ui.graph.edit.GraphEditor;
import com.openmason.engine.ui.script.GraphDebugger;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The state every panel of the graph editor window shares: the headless editor, which body is
 * on the canvas, the selection, per-body camera, the debugger of the running preview and a few
 * one-shot requests (frame the view, jump to an element). Panels read and mutate it directly;
 * all document edits still go through {@link GraphEditor}.
 */
final class GraphEditorState {

    GraphEditor editor;
    /** {@code ""} is the event graph, otherwise a function id. */
    String body = "";
    final Set<String> selection = new LinkedHashSet<>();
    String selectedComment;
    String status = "";
    /** Live debugger of the preview, or null; refreshed every frame. */
    GraphDebugger debugger;
    /** Preview UI time (seconds), the clock of {@link GraphDebugger.Hit#time()}. */
    double time;
    Consumer<String> jumpToElement = path -> { };
    /** Frame the whole body on the next canvas draw. */
    boolean frameAll;
    /** Frame this node (and select it) on the next canvas draw. */
    String frameNode;
    /** Selection changed from outside the canvas: scroll the Lua view to it. */
    boolean selectionDirty;

    private final Map<String, GraphCanvasView> views = new HashMap<>();
    private final Set<String> framed = new LinkedHashSet<>();

    GraphEditorState(GraphEditor editor) {
        this.editor = editor;
    }

    String graphId() {
        return editor.graphId();
    }

    /** The camera of the current body (created, and framed on first show, when absent). */
    GraphCanvasView view() {
        GraphCanvasView v = views.computeIfAbsent(body, b -> new GraphCanvasView());
        if (framed.add(body)) {
            frameAll = true;
        }
        return v;
    }

    /** The debugger's location key of a node of the current body ({@code id} or {@code fn:f/id}). */
    String loc(String nodeId) {
        return loc(body, nodeId);
    }

    static String loc(String function, String nodeId) {
        return function.isEmpty() ? nodeId : "fn:" + function + "/" + nodeId;
    }

    boolean hasBreakpoint(String nodeId) {
        return debugger != null && debugger.hasBreakpoint(graphId(), loc(nodeId));
    }

    void toggleBreakpoint(String nodeId) {
        if (debugger == null) {
            status = "Breakpoints need the running preview (debug build)";
            return;
        }
        debugger.setBreakpoint(graphId(), loc(nodeId), !hasBreakpoint(nodeId));
    }

    void switchBody(String function) {
        if (!function.equals(body)) {
            body = function;
            selection.clear();
            selectedComment = null;
        }
    }

    /** Selects one node of {@code function}, switching body and framing it. */
    void jumpTo(String function, String nodeId) {
        switchBody(function);
        selection.clear();
        selection.add(nodeId);
        selectedComment = null;
        frameNode = nodeId;
        selectionDirty = true;
    }

    void selectOnly(String nodeId) {
        selection.clear();
        selection.add(nodeId);
        selectedComment = null;
        selectionDirty = true;
    }

    /** The sole selected node id, or null when the selection is empty or has several. */
    String single() {
        return selection.size() == 1 ? selection.iterator().next() : null;
    }

    /** The editor was replaced (other graph, reload): re-validates body and selection, optionally keeping cameras. */
    void rebind(GraphEditor next, boolean keepViews) {
        editor = next;
        if (!body.isEmpty() && editor.graph().functions().stream().noneMatch(f -> f.id().equals(body))) {
            body = "";
        }
        selection.removeIf(id -> editor.node(body, id) == null);
        if (selectedComment != null && editor.layout().comments().stream().noneMatch(c -> c.id().equals(selectedComment))) {
            selectedComment = null;
        }
        if (!keepViews) {
            views.clear();
            framed.clear();
        }
    }
}
