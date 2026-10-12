package com.openmason.main.systems.uiPreview.graph;

import com.openmason.engine.ui.graph.edit.GraphLayout;
import com.openmason.main.systems.uiPreview.graph.GraphCanvasView.Bezier;
import com.openmason.main.systems.uiPreview.graph.GraphCanvasView.CommentHit;
import com.openmason.main.systems.uiPreview.graph.GraphCanvasView.CommentPart;
import com.openmason.main.systems.uiPreview.graph.GraphCanvasView.NodeBox;
import com.openmason.main.systems.uiPreview.graph.GraphCanvasView.PinHit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphCanvasViewTest {

    private static final double EPS = 1e-9;

    private static List<NodeBox> boxes() {
        return List.of(
            NodeBox.of("a", 0, 0, 160, 1, 2),
            NodeBox.of("b", 300, 40, 160, 3, 1));
    }

    @Test
    void transformRoundTrips() {
        GraphCanvasView v = new GraphCanvasView();
        v.pan(37, -12);
        v.setZoom(1.7);
        double sx = v.toScreenX(123.5);
        double sy = v.toScreenY(-48);
        assertEquals(123.5, v.toGraphX(sx), EPS);
        assertEquals(-48, v.toGraphY(sy), EPS);
    }

    @Test
    void zoomAboutKeepsThePointFixed() {
        GraphCanvasView v = new GraphCanvasView();
        v.pan(20, 30);
        double gx = v.toGraphX(250);
        double gy = v.toGraphY(180);
        v.zoomAbout(250, 180, 1.5);
        assertEquals(1.5, v.zoom(), EPS);
        assertEquals(250, v.toScreenX(gx), EPS);
        assertEquals(180, v.toScreenY(gy), EPS);
    }

    @Test
    void zoomIsClamped() {
        GraphCanvasView v = new GraphCanvasView();
        v.zoomAbout(0, 0, 1000);
        assertEquals(GraphCanvasView.MAX_ZOOM, v.zoom(), EPS);
        v.zoomAbout(0, 0, 1e-6);
        assertEquals(GraphCanvasView.MIN_ZOOM, v.zoom(), EPS);
    }

    @Test
    void boxSizingFollowsPinCounts() {
        NodeBox b = NodeBox.of("n", 0, 0, 10, 4, 2);
        assertEquals(GraphCanvasView.HEADER + 4 * GraphCanvasView.ROW + GraphCanvasView.FOOTER, b.height(), EPS);
        assertEquals(GraphCanvasView.MIN_WIDTH, b.width(), EPS);
        assertEquals(GraphCanvasView.HEADER + GraphCanvasView.ROW + GraphCanvasView.FOOTER,
            NodeBox.of("e", 0, 0, 200, 0, 0).height(), EPS);
    }

    @Test
    void portAnchorsSitOnTheEdgesAtRowCentres() {
        NodeBox b = NodeBox.of("n", 10, 20, 200, 2, 2);
        double[] in1 = b.anchor(false, 1);
        double[] out0 = b.anchor(true, 0);
        assertEquals(10, in1[0], EPS);
        assertEquals(20 + GraphCanvasView.HEADER + 1.5 * GraphCanvasView.ROW, in1[1], EPS);
        assertEquals(210, out0[0], EPS);
        assertEquals(20 + GraphCanvasView.HEADER + 0.5 * GraphCanvasView.ROW, out0[1], EPS);
    }

    @Test
    void hitNodePrefersTheTopmostBox() {
        GraphCanvasView v = new GraphCanvasView();
        List<NodeBox> overlap = List.of(NodeBox.of("under", 0, 0, 200, 1, 1), NodeBox.of("over", 50, 10, 200, 1, 1));
        assertEquals("over", v.hitNode(overlap, 60, 30).id());
        assertEquals("under", v.hitNode(overlap, 10, 30).id());
        assertNull(v.hitNode(overlap, 900, 900));
    }

    @Test
    void hitTestingFollowsPanAndZoom() {
        GraphCanvasView v = new GraphCanvasView();
        v.pan(100, 50);
        v.setZoom(2);
        List<NodeBox> b = boxes();
        double[] anchor = b.get(0).anchor(true, 1);
        PinHit pin = v.hitPin(b, v.toScreenX(anchor[0]) + 3, v.toScreenY(anchor[1]) - 2, 10);
        assertNotNull(pin);
        assertEquals(new PinHit("a", true, 1), pin);
        assertNull(v.hitPin(b, v.toScreenX(anchor[0]) + 40, v.toScreenY(anchor[1]), 10));
        assertEquals("a", v.hitNode(b, v.toScreenX(80), v.toScreenY(40)).id());
    }

    @Test
    void hitPinSeparatesInputsAndOutputs() {
        GraphCanvasView v = new GraphCanvasView();
        List<NodeBox> b = boxes();
        double[] in = b.get(1).anchor(false, 2);
        double[] out = b.get(1).anchor(true, 0);
        assertEquals(new PinHit("b", false, 2), v.hitPin(b, in[0], in[1], 8));
        assertEquals(new PinHit("b", true, 0), v.hitPin(b, out[0], out[1], 8));
    }

    @Test
    void boxSelectCollectsIntersectingNodes() {
        GraphCanvasView v = new GraphCanvasView();
        List<NodeBox> b = boxes();
        assertEquals(List.of("a"), v.boxSelect(b, -10, -10, 50, 50));
        assertEquals(List.of("a", "b"), v.boxSelect(b, 470, 100, -5, -5)); // corners in any order
        assertTrue(v.boxSelect(b, 600, 600, 700, 700).isEmpty());
        v.setZoom(0.5);
        assertEquals(List.of("b"), v.boxSelect(b, v.toScreenX(310), v.toScreenY(50), v.toScreenX(320), v.toScreenY(60)));
    }

    @Test
    void linksAreHitNearTheCurveOnly() {
        GraphCanvasView v = new GraphCanvasView();
        Bezier c = Bezier.between(0, 0, 300, 100);
        double[] mid = c.at(0.5);
        assertEquals(0, v.hitLink(List.of(c), mid[0] + 2, mid[1] + 2, 7));
        assertEquals(-1, v.hitLink(List.of(c), mid[0] + 40, mid[1] - 40, 7));
        Bezier other = Bezier.between(0, 200, 300, 300);
        assertEquals(1, v.hitLink(List.of(c, other), other.at(0.3)[0], other.at(0.3)[1], 7));
    }

    @Test
    void bezierEndpointsAreTheAnchors() {
        Bezier c = Bezier.between(10, 20, 210, 120);
        assertEquals(10, c.at(0)[0], EPS);
        assertEquals(20, c.at(0)[1], EPS);
        assertEquals(210, c.at(1)[0], EPS);
        assertEquals(120, c.at(1)[1], EPS);
        assertEquals(0, c.distanceTo(10, 20), 1e-6);
    }

    @Test
    void commentTitleAndGripAreHitButTheBodyIsNot() {
        GraphCanvasView v = new GraphCanvasView();
        GraphLayout.Comment c = new GraphLayout.Comment("c1", "", 100, 100, 300, 200, "x", "");
        List<GraphLayout.Comment> cs = List.of(c);
        CommentHit title = v.hitComment(cs, 150, 110);
        assertEquals(CommentPart.TITLE, title.part());
        CommentHit grip = v.hitComment(cs, 395, 295);
        assertEquals(CommentPart.RESIZE, grip.part());
        assertNull(v.hitComment(cs, 250, 200), "the body leaves nodes and empty space usable");
        assertNull(v.hitComment(cs, 50, 50));
    }

    @Test
    void frameFitsTheBoxesIntoTheView() {
        GraphCanvasView v = new GraphCanvasView();
        List<NodeBox> b = boxes();
        v.frameBoxes(b, 800, 600);
        for (NodeBox n : b) {
            assertTrue(v.toScreenX(n.x()) >= 0 && v.toScreenX(n.x() + n.width()) <= 800, "x fits");
            assertTrue(v.toScreenY(n.y()) >= 0 && v.toScreenY(n.y() + n.height()) <= 600, "y fits");
        }
        v.frameBoxes(List.of(), 800, 600);
        assertEquals(1, v.zoom(), EPS);
    }
}
