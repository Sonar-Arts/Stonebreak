package com.openmason.engine.format.omui.io;

import com.openmason.engine.format.omui.ArchiveLimits;
import com.openmason.engine.format.omui.OmuiFormat;
import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.UiGraph;
import com.openmason.engine.format.omui.UiGraph.GraphEdge;
import com.openmason.engine.format.omui.UiGraph.GraphFunction;
import com.openmason.engine.format.omui.UiGraph.GraphNode;
import com.openmason.engine.format.omui.UiGraph.GraphPort;
import com.openmason.engine.format.omui.UiGraph.GraphVariable;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.ValueType;

import java.util.ArrayList;
import java.util.List;

/** {@code graphs/<id>.graph.json} ↔ {@link UiGraph}. */
public final class GraphCodec {

    private GraphCodec() {
    }

    public static UiGraph read(String id, String entry, UiValue root, ArchiveLimits limits, UiDiagnostics d) {
        ObjReader r = ObjReader.of(root, entry, "", d);
        String declared = r.requiredString("id");
        if (!declared.equals(id)) {
            r.error(Code.INCONSISTENT_MANIFEST, "id",
                    "Graph id '" + declared + "' does not match its entry name '" + id + "'");
        }
        List<GraphVariable> variables = new ArrayList<>();
        for (ObjReader v : r.objects("variables")) {
            variables.add(new GraphVariable(v.requiredString("name"),
                    v.requiredEnum("type", ValueType.class, ValueType.STRING), v.raw("default"), v.unknown()));
        }
        List<GraphNode> nodes = readNodes(r);
        List<GraphEdge> edges = readEdges(r);
        List<GraphFunction> functions = new ArrayList<>();
        int total = nodes.size();
        for (ObjReader f : r.objects("functions")) {
            List<GraphNode> fnNodes = readNodes(f);
            total += fnNodes.size();
            functions.add(new GraphFunction(f.requiredString("id"), readPorts(f, "inputs"), readPorts(f, "outputs"),
                    fnNodes, readEdges(f), f.unknown()));
        }
        if (total > limits.maxGraphNodes()) {
            r.error(Code.LIMIT_EXCEEDED, null, "Graph has " + total + " nodes (limit " + limits.maxGraphNodes() + ")");
        }
        return new UiGraph(id, variables, nodes, edges, functions, r.unknown());
    }

    private static List<GraphNode> readNodes(ObjReader r) {
        List<GraphNode> out = new ArrayList<>();
        for (ObjReader n : r.objects("nodes")) {
            out.add(new GraphNode(n.requiredString("id"), n.requiredString("kind"),
                    n.optionalInt("kindVersion", 1, 1, OmuiFormat.MAX_VERSION),
                    n.optionalNumber("x", 0, -OmuiFormat.MAX_COORD, OmuiFormat.MAX_COORD), n.optionalNumber("y", 0, -OmuiFormat.MAX_COORD, OmuiFormat.MAX_COORD),
                    n.freeMap("inputs"), n.freeMap("props"), n.unknown()));
        }
        return out;
    }

    private static List<GraphEdge> readEdges(ObjReader r) {
        List<GraphEdge> out = new ArrayList<>();
        for (ObjReader e : r.objects("edges")) {
            out.add(new GraphEdge(e.requiredString("fromNode"), e.requiredString("fromPort"),
                    e.requiredString("toNode"), e.requiredString("toPort"), e.unknown()));
        }
        return out;
    }

    private static List<GraphPort> readPorts(ObjReader r, String key) {
        List<GraphPort> out = new ArrayList<>();
        for (ObjReader p : r.objects(key)) {
            String type = p.requiredString("type");
            if (!GraphPort.EXEC.equals(type) && ValueType.fromWire(type) == null) {
                p.error(Code.UNKNOWN_ENUM, "type", "'" + type + "' is not exec or a value type");
            }
            out.add(new GraphPort(p.requiredString("name"), type, p.unknown()));
        }
        return out;
    }

    public static UiValue.Obj write(UiGraph g) {
        return new ObjWriter()
                .put("id", g.id())
                .putList("variables", g.variables(), v -> new ObjWriter()
                        .put("name", v.name())
                        .putEnum("type", v.type())
                        .put("default", v.defaultValue())
                        .putUnknown(v.unknown())
                        .build())
                .putList("nodes", g.nodes(), GraphCodec::writeNode)
                .putList("edges", g.edges(), GraphCodec::writeEdge)
                .putList("functions", g.functions(), f -> new ObjWriter()
                        .put("id", f.id())
                        .putList("inputs", f.inputs(), GraphCodec::writePort)
                        .putList("outputs", f.outputs(), GraphCodec::writePort)
                        .putList("nodes", f.nodes(), GraphCodec::writeNode)
                        .putList("edges", f.edges(), GraphCodec::writeEdge)
                        .putUnknown(f.unknown())
                        .build())
                .putUnknown(g.unknown())
                .build();
    }

    private static UiValue writeNode(GraphNode n) {
        return new ObjWriter()
                .put("id", n.id())
                .put("kind", n.kind())
                .putIfNot("kindVersion", n.kindVersion(), 1)
                .putNumberIfNot("x", n.x(), 0)
                .putNumberIfNot("y", n.y(), 0)
                .putMap("inputs", n.inputs())
                .putMap("props", n.props())
                .putUnknown(n.unknown())
                .build();
    }

    private static UiValue writeEdge(GraphEdge e) {
        return new ObjWriter()
                .put("fromNode", e.fromNode())
                .put("fromPort", e.fromPort())
                .put("toNode", e.toNode())
                .put("toPort", e.toPort())
                .putUnknown(e.unknown())
                .build();
    }

    private static UiValue writePort(GraphPort p) {
        return new ObjWriter().put("name", p.name()).put("type", p.type()).putUnknown(p.unknown()).build();
    }
}
