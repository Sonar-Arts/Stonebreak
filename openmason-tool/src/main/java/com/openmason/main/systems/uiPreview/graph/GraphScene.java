package com.openmason.main.systems.uiPreview.graph;

import com.openmason.engine.format.omui.UiGraph.GraphEdge;
import com.openmason.engine.format.omui.UiGraph.GraphNode;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.graph.NodeKind;
import com.openmason.engine.ui.graph.NodePorts;
import com.openmason.engine.ui.graph.PortSpec;
import com.openmason.engine.ui.graph.edit.GraphEditor;
import com.openmason.main.systems.uiPreview.graph.GraphCanvasView.Bezier;
import com.openmason.main.systems.uiPreview.graph.GraphCanvasView.NodeBox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One frame's drawable snapshot of a graph body (pure): every node with its box, labelled pins
 * and role, and every link as a curve between pin anchors. Built from the headless
 * {@link GraphEditor}; text widths come from a {@link TextMeasure} so tests need no ImGui.
 */
public final class GraphScene {

    /** Width of a string in graph units at zoom 1. */
    @FunctionalInterface
    public interface TextMeasure {
        double width(String text);
    }

    public enum Role { EVENT, STATEMENT, PURE, UNKNOWN }

    /** One drawable pin. */
    public record Pin(PortSpec spec, boolean output, int index, String label, boolean connected) {
    }

    public record NodeView(GraphNode node, String title, String category, Role role, NodeBox box, List<Pin> inputs,
                           List<Pin> outputs) {
        public String id() {
            return node.id();
        }
    }

    public record LinkView(GraphEdge edge, Bezier curve, boolean exec, PortSpec fromPort) {
    }

    private static final double PIN_LABEL_GAP = 36;
    private static final double TITLE_PAD = 30;

    private final List<NodeView> nodes;
    private final List<NodeBox> boxes;
    private final List<LinkView> links;
    private final Map<String, NodeView> byId = new HashMap<>();

    private GraphScene(List<NodeView> nodes, List<LinkView> links) {
        this.nodes = List.copyOf(nodes);
        this.links = List.copyOf(links);
        List<NodeBox> b = new ArrayList<>();
        for (NodeView v : nodes) {
            b.add(v.box());
            byId.put(v.id(), v);
        }
        this.boxes = List.copyOf(b);
    }

    public List<NodeView> nodes() {
        return nodes;
    }

    public List<NodeBox> boxes() {
        return boxes;
    }

    public List<LinkView> links() {
        return links;
    }

    public NodeView node(String id) {
        return byId.get(id);
    }

    public static GraphScene build(GraphEditor editor, String body, TextMeasure measure) {
        Set<String> connected = new HashSet<>();
        for (GraphEdge e : editor.edges(body)) {
            connected.add(e.fromNode() + ">" + e.fromPort());
            connected.add(e.toNode() + "<" + e.toPort());
        }
        List<NodeView> views = new ArrayList<>();
        for (GraphNode n : editor.nodes(body)) {
            views.add(view(editor, body, n, connected, measure));
        }
        Map<String, NodeView> index = new HashMap<>();
        views.forEach(v -> index.put(v.id(), v));
        List<LinkView> links = new ArrayList<>();
        for (GraphEdge e : editor.edges(body)) {
            NodeView from = index.get(e.fromNode());
            NodeView to = index.get(e.toNode());
            Pin out = from == null ? null : pin(from.outputs(), e.fromPort());
            Pin in = to == null ? null : pin(to.inputs(), e.toPort());
            if (out == null || in == null) {
                continue; // broken link: the validator reports it, nothing to draw
            }
            double[] a = from.box().anchor(true, out.index());
            double[] b = to.box().anchor(false, in.index());
            links.add(new LinkView(e, Bezier.between(a[0], a[1], b[0], b[1]), out.spec().isExec(), out.spec()));
        }
        return new GraphScene(views, links);
    }

    private static Pin pin(List<Pin> pins, String name) {
        for (Pin p : pins) {
            if (p.spec().name().equals(name)) {
                return p;
            }
        }
        return null;
    }

    private static NodeView view(GraphEditor editor, String body, GraphNode n, Set<String> connected,
                                 TextMeasure measure) {
        NodeKind kind = editor.kind(body, n.id());
        NodePorts ports = editor.ports(body, n.id());
        Role role = kind == null ? Role.UNKNOWN : kind.isEvent() ? Role.EVENT
            : ports.hasExecInput() || !ports.execOutputs().isEmpty() ? Role.STATEMENT : Role.PURE;
        String title = kind == null ? n.kind() : kind.title();
        List<Pin> ins = new ArrayList<>();
        double left = 0;
        for (int i = 0; i < ports.inputs().size(); i++) {
            PortSpec p = ports.inputs().get(i);
            boolean linked = connected.contains(n.id() + "<" + p.name());
            String label = p.name();
            if (!p.isExec() && !linked) {
                UiValue lit = n.inputs().containsKey(p.name()) ? n.inputs().get(p.name()) : p.defaultValue();
                if (lit != null) {
                    label += " = " + GraphValues.compact(lit);
                }
            }
            left = Math.max(left, measure.width(label));
            ins.add(new Pin(p, false, i, label, linked));
        }
        List<Pin> outs = new ArrayList<>();
        double right = 0;
        for (int i = 0; i < ports.outputs().size(); i++) {
            PortSpec p = ports.outputs().get(i);
            right = Math.max(right, measure.width(p.name()));
            outs.add(new Pin(p, true, i, p.name(), connected.contains(n.id() + ">" + p.name())));
        }
        double width = Math.max(measure.width(title) + TITLE_PAD, left + right + PIN_LABEL_GAP);
        NodeBox box = NodeBox.of(n.id(), n.x(), n.y(), width, ins.size(), outs.size());
        return new NodeView(n, title, kind == null ? "" : kind.category(), role, box, ins, outs);
    }
}
