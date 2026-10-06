package com.openmason.main.systems.uiPreview.graph;

import com.openmason.engine.ui.graph.edit.GraphLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure geometry of the behavior-graph canvas (#291): the pan/zoom transform between graph
 * space (node coordinates of the document) and canvas-local screen space (origin at the canvas'
 * top-left), node box sizing, pin anchors, hit testing, box selection and link curves. No ImGui,
 * so all of it is unit-tested; {@link GraphCanvasPanel} only draws what this computes.
 */
public final class GraphCanvasView {

    public static final double HEADER = 26;
    public static final double ROW = 22;
    public static final double FOOTER = 6;
    public static final double MIN_WIDTH = 150;
    public static final double PIN_RADIUS = 6;
    public static final double MIN_ZOOM = 0.2;
    public static final double MAX_ZOOM = 2.5;
    public static final double COMMENT_TITLE = 24;
    public static final double COMMENT_GRIP = 16;
    private static final int CURVE_SAMPLES = 32;

    /** A node's rectangle in graph space plus its pin counts. */
    public record NodeBox(String id, double x, double y, double width, double height, int inputs, int outputs) {
        public static NodeBox of(String id, double x, double y, double width, int inputs, int outputs) {
            double h = HEADER + Math.max(1, Math.max(inputs, outputs)) * ROW + FOOTER;
            return new NodeBox(id, x, y, Math.max(MIN_WIDTH, width), h, inputs, outputs);
        }

        public boolean contains(double gx, double gy) {
            return gx >= x && gy >= y && gx <= x + width && gy <= y + height;
        }

        public boolean intersects(double x1, double y1, double x2, double y2) {
            return x <= x2 && x + width >= x1 && y <= y2 && y + height >= y1;
        }

        /** Pin position in graph space; {@code index} counts from the top of its column. */
        public double[] anchor(boolean output, int index) {
            return new double[]{output ? x + width : x, y + HEADER + (index + 0.5) * ROW};
        }
    }

    /** A pin under the pointer. */
    public record PinHit(String node, boolean output, int index) {
    }

    /** A cubic link curve in graph space. */
    public record Bezier(double x0, double y0, double x1, double y1, double x2, double y2, double x3, double y3) {

        /** Horizontal-tangent curve from an output anchor to an input anchor. */
        public static Bezier between(double ax, double ay, double bx, double by) {
            double off = Math.clamp(Math.abs(bx - ax) * 0.5, 40, 220);
            return new Bezier(ax, ay, ax + off, ay, bx - off, by, bx, by);
        }

        public double[] at(double t) {
            double u = 1 - t;
            double a = u * u * u;
            double b = 3 * u * u * t;
            double c = 3 * u * t * t;
            double d = t * t * t;
            return new double[]{a * x0 + b * x1 + c * x2 + d * x3, a * y0 + b * y1 + c * y2 + d * y3};
        }

        /** Distance from a point to the curve, from a polyline approximation. */
        public double distanceTo(double px, double py) {
            double best = Double.MAX_VALUE;
            double[] prev = at(0);
            for (int i = 1; i <= CURVE_SAMPLES; i++) {
                double[] cur = at(i / (double) CURVE_SAMPLES);
                best = Math.min(best, segmentDistance(px, py, prev[0], prev[1], cur[0], cur[1]));
                prev = cur;
            }
            return best;
        }
    }

    /** Which part of a comment frame is under the pointer. */
    public enum CommentPart { TITLE, RESIZE }

    public record CommentHit(GraphLayout.Comment comment, CommentPart part) {
    }

    private double panX;
    private double panY;
    private double zoom = 1;

    // ── transform ───────────────────────────────────────────────────────────

    public double panX() {
        return panX;
    }

    public double panY() {
        return panY;
    }

    public double zoom() {
        return zoom;
    }

    public double toScreenX(double gx) {
        return gx * zoom + panX;
    }

    public double toScreenY(double gy) {
        return gy * zoom + panY;
    }

    public double toGraphX(double sx) {
        return (sx - panX) / zoom;
    }

    public double toGraphY(double sy) {
        return (sy - panY) / zoom;
    }

    public void pan(double dxScreen, double dyScreen) {
        panX += dxScreen;
        panY += dyScreen;
    }

    public void setZoom(double z) {
        zoom = Math.clamp(z, MIN_ZOOM, MAX_ZOOM);
    }

    /** Scales by {@code factor} keeping the graph point under ({@code sx}, {@code sy}) fixed. */
    public void zoomAbout(double sx, double sy, double factor) {
        double gx = toGraphX(sx);
        double gy = toGraphY(sy);
        setZoom(zoom * factor);
        panX = sx - gx * zoom;
        panY = sy - gy * zoom;
    }

    /** Fits a graph-space rectangle into a {@code viewW x viewH} canvas (zoom never exceeds 1.25). */
    public void frame(double minX, double minY, double maxX, double maxY, double viewW, double viewH, double margin) {
        double w = Math.max(1, maxX - minX);
        double h = Math.max(1, maxY - minY);
        setZoom(Math.min(1.25, Math.min((viewW - 2 * margin) / w, (viewH - 2 * margin) / h)));
        panX = viewW / 2 - (minX + w / 2) * zoom;
        panY = viewH / 2 - (minY + h / 2) * zoom;
    }

    /** Frames every box; an empty list resets to the origin at zoom 1. */
    public void frameBoxes(List<NodeBox> boxes, double viewW, double viewH) {
        if (boxes.isEmpty()) {
            zoom = 1;
            panX = 40;
            panY = 40;
            return;
        }
        double x1 = Double.MAX_VALUE;
        double y1 = Double.MAX_VALUE;
        double x2 = -Double.MAX_VALUE;
        double y2 = -Double.MAX_VALUE;
        for (NodeBox b : boxes) {
            x1 = Math.min(x1, b.x());
            y1 = Math.min(y1, b.y());
            x2 = Math.max(x2, b.x() + b.width());
            y2 = Math.max(y2, b.y() + b.height());
        }
        frame(x1, y1, x2, y2, viewW, viewH, 60);
    }

    // ── hit testing (screen coordinates in, canvas-local) ───────────────────

    /** The topmost box under a screen point (later boxes draw on top), or null. */
    public NodeBox hitNode(List<NodeBox> boxes, double sx, double sy) {
        double gx = toGraphX(sx);
        double gy = toGraphY(sy);
        for (int i = boxes.size() - 1; i >= 0; i--) {
            if (boxes.get(i).contains(gx, gy)) {
                return boxes.get(i);
            }
        }
        return null;
    }

    /** The pin within {@code radiusPx} (screen pixels) of a point, preferring the topmost node. */
    public PinHit hitPin(List<NodeBox> boxes, double sx, double sy, double radiusPx) {
        double gx = toGraphX(sx);
        double gy = toGraphY(sy);
        double r = radiusPx / zoom;
        for (int i = boxes.size() - 1; i >= 0; i--) {
            NodeBox b = boxes.get(i);
            if (gy < b.y() - r || gy > b.y() + b.height() + r || gx < b.x() - r || gx > b.x() + b.width() + r) {
                continue;
            }
            for (int k = 0; k < b.inputs(); k++) {
                double[] a = b.anchor(false, k);
                if (Math.hypot(gx - a[0], gy - a[1]) <= r) {
                    return new PinHit(b.id(), false, k);
                }
            }
            for (int k = 0; k < b.outputs(); k++) {
                double[] a = b.anchor(true, k);
                if (Math.hypot(gx - a[0], gy - a[1]) <= r) {
                    return new PinHit(b.id(), true, k);
                }
            }
        }
        return null;
    }

    /** Ids of boxes intersecting the screen rectangle (corners in any order). */
    public List<String> boxSelect(List<NodeBox> boxes, double sx1, double sy1, double sx2, double sy2) {
        double x1 = toGraphX(Math.min(sx1, sx2));
        double x2 = toGraphX(Math.max(sx1, sx2));
        double y1 = toGraphY(Math.min(sy1, sy2));
        double y2 = toGraphY(Math.max(sy1, sy2));
        List<String> out = new ArrayList<>();
        for (NodeBox b : boxes) {
            if (b.intersects(x1, y1, x2, y2)) {
                out.add(b.id());
            }
        }
        return out;
    }

    /** Index of the curve nearest a screen point within {@code tolPx}, or -1. */
    public int hitLink(List<Bezier> curves, double sx, double sy, double tolPx) {
        double gx = toGraphX(sx);
        double gy = toGraphY(sy);
        double tol = tolPx / zoom;
        int best = -1;
        double bestD = tol;
        for (int i = 0; i < curves.size(); i++) {
            double d = curves.get(i).distanceTo(gx, gy);
            if (d <= bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /**
     * The comment title bar or resize grip under a point (the frame's body is not a hit: nodes
     * and empty-space dragging stay usable inside a frame). Topmost (last) frame wins.
     */
    public CommentHit hitComment(List<GraphLayout.Comment> comments, double sx, double sy) {
        double gx = toGraphX(sx);
        double gy = toGraphY(sy);
        double grip = COMMENT_GRIP / zoom;
        double title = COMMENT_TITLE;
        for (int i = comments.size() - 1; i >= 0; i--) {
            GraphLayout.Comment c = comments.get(i);
            if (!c.contains(gx, gy)) {
                continue;
            }
            if (gx >= c.x() + c.width() - grip && gy >= c.y() + c.height() - grip) {
                return new CommentHit(c, CommentPart.RESIZE);
            }
            if (gy <= c.y() + title) {
                return new CommentHit(c, CommentPart.TITLE);
            }
        }
        return null;
    }

    private static double segmentDistance(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        double len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : Math.clamp(((px - ax) * dx + (py - ay) * dy) / len2, 0, 1);
        return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }
}
