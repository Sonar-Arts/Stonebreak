package com.openmason.main.systems.uiPreview.graph;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiReader;
import com.openmason.engine.ui.graph.edit.GraphEditor;
import com.openmason.engine.ui.runtime.UiDocumentSource;
import com.openmason.main.systems.uiPreview.graph.GraphCanvasView.NodeBox;
import com.openmason.main.systems.uiPreview.graph.GraphScene.Role;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The scene of a real graph (the engine's pause sample): boxes, roles, links, and the canvas hit tests over them. */
class GraphSceneTest {

    private static final Path SAMPLE = Path.of("../openmason-engine/src/test/resources/ui/script/graph_pause.omui");

    private static GraphScene scene(GraphEditor ed) {
        return GraphScene.build(ed, "", s -> s.length() * 7.0);
    }

    private static GraphEditor editor() throws Exception {
        assumeTrue(Files.exists(SAMPLE), "engine sample missing: " + SAMPLE.toAbsolutePath());
        OmuiArchive doc = OmuiReader.read(SAMPLE).archive();
        return GraphEditor.open(doc, "pause", UiDocumentSource.EMPTY);
    }

    @Test
    void everyNodeGetsABoxAndEveryResolvedLinkACurve() throws Exception {
        GraphEditor ed = editor();
        GraphScene s = scene(ed);
        assertEquals(ed.nodes("").size(), s.nodes().size());
        assertFalse(s.links().isEmpty());
        for (GraphScene.LinkView l : s.links()) {
            GraphScene.NodeView from = s.node(l.edge().fromNode());
            GraphScene.NodeView to = s.node(l.edge().toNode());
            GraphScene.Pin out = from.outputs().stream().filter(p -> p.spec().name().equals(l.edge().fromPort()))
                .findFirst().orElseThrow();
            double[] a = from.box().anchor(true, out.index());
            assertEquals(a[0], l.curve().x0(), 1e-9);
            assertEquals(a[1], l.curve().y0(), 1e-9);
            assertNotNull(to);
        }
    }

    @Test
    void nodesHaveRolesAndConnectedPinsAreMarked() throws Exception {
        GraphScene s = scene(editor());
        assertTrue(s.nodes().stream().anyMatch(n -> n.role() == Role.EVENT), "an event node");
        assertTrue(s.nodes().stream().anyMatch(n -> n.role() == Role.STATEMENT), "a statement node");
        assertTrue(s.nodes().stream().flatMap(n -> n.outputs().stream()).anyMatch(GraphScene.Pin::connected));
    }

    @Test
    void theCanvasFindsEachNodeAtItsCentre() throws Exception {
        GraphScene s = scene(editor());
        GraphCanvasView v = new GraphCanvasView();
        v.frameBoxes(s.boxes(), 1200, 800);
        for (NodeBox b : s.boxes()) {
            double cx = v.toScreenX(b.x() + b.width() / 2);
            double cy = v.toScreenY(b.y() + b.height() / 2);
            NodeBox hit = v.hitNode(s.boxes(), cx, cy);
            assertNotNull(hit);
            assertTrue(hit.contains(b.x() + b.width() / 2, b.y() + b.height() / 2));
        }
    }

    @Test
    void movingANodeThroughTheEditorMovesItsBoxAndLinks() throws Exception {
        GraphEditor ed = editor();
        GraphScene before = scene(ed);
        GraphScene.LinkView link = before.links().getFirst();
        String id = link.edge().fromNode();
        ed.moveNodes("", java.util.List.of(id), 30, 10, "t");
        ed.endDrag();
        GraphScene after = scene(ed);
        assertEquals(before.node(id).box().x() + 30, after.node(id).box().x(), 1e-9);
        GraphScene.LinkView moved = after.links().stream().filter(l -> l.edge().equals(link.edge())).findFirst().orElseThrow();
        assertEquals(link.curve().x0() + 30, moved.curve().x0(), 1e-9);
        ed.undo();
        assertEquals(before.node(id).box().x(), scene(ed).node(id).box().x(), 1e-9);
    }
}
