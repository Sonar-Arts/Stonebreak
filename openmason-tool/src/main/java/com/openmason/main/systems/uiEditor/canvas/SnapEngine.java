package com.openmason.main.systems.uiEditor.canvas;

import java.util.ArrayList;
import java.util.List;

/**
 * Smart snapping for canvas drags: the moving box's edges and centre lines snap to the edges and
 * centre lines of nearby boxes (parent, siblings) and, failing that, to the layout grid. Works in
 * frame pixels; the threshold is passed in frame pixels too (the caller divides a screen distance
 * by the zoom). Returns the corrected delta plus guide lines to draw.
 */
public final class SnapEngine {

    /** A guide: a vertical ({@code vertical}) line at x = pos from y0..y1, or horizontal at y = pos. */
    public record Guide(boolean vertical, float pos, float from, float to) {
    }

    public record Result(float dx, float dy, List<Guide> guides) {
    }

    /** Snapping inputs that stay the same for a whole drag. */
    public record Settings(boolean edges, boolean grid, float gridStep, float threshold) {
        public static final Settings OFF = new Settings(false, false, 0, 0);
    }

    private SnapEngine() {
    }

    /** Snaps a whole-box move by {@code (dx, dy)}. */
    public static Result move(Box moving, float dx, float dy, List<Box> targets, Settings s) {
        Box at = moving.offset(dx, dy);
        Axis x = snapAxis(new float[]{at.l(), at.cx(), at.r()}, targets, true, s);
        Axis y = snapAxis(new float[]{at.t(), at.cy(), at.b()}, targets, false, s);
        Box snapped = at.offset(x.delta, y.delta);
        return new Result(dx + x.delta, dy + y.delta, guides(snapped, x, y, targets));
    }

    /** Snaps the edges a resize handle moves. */
    public static Result resize(Box start, ResizeHandle h, float dx, float dy, List<Box> targets, Settings s) {
        float ex = h.dx < 0 ? start.l() + dx : start.r() + dx;
        float ey = h.dy < 0 ? start.t() + dy : start.b() + dy;
        Axis x = h.dx == 0 ? Axis.NONE : snapAxis(new float[]{ex}, targets, true, s);
        Axis y = h.dy == 0 ? Axis.NONE : snapAxis(new float[]{ey}, targets, false, s);
        Box result = ResizeMath.resized(start, h, dx + x.delta, dy + y.delta);
        return new Result(dx + x.delta, dy + y.delta, guides(result, x, y, targets));
    }

    private record Axis(float delta, float line) {
        static final Axis NONE = new Axis(0, Float.NaN);
    }

    private static Axis snapAxis(float[] own, List<Box> targets, boolean xAxis, Settings s) {
        float best = Float.MAX_VALUE;
        float line = Float.NaN;
        if (s.edges()) {
            for (Box t : targets) {
                float[] lines = xAxis ? new float[]{t.l(), t.cx(), t.r()} : new float[]{t.t(), t.cy(), t.b()};
                for (float o : own) {
                    for (float l : lines) {
                        float d = l - o;
                        if (Math.abs(d) <= s.threshold() && Math.abs(d) < Math.abs(best)) {
                            best = d;
                            line = l;
                        }
                    }
                }
            }
        }
        if (Float.isNaN(line) && s.grid() && s.gridStep() > 0) {
            float o = own[0];
            float g = Math.round(o / s.gridStep()) * s.gridStep();
            if (Math.abs(g - o) <= Math.max(s.threshold(), s.gridStep() / 2f)) {
                return new Axis(g - o, Float.NaN); // grid snaps silently: no guide line
            }
        }
        return Float.isNaN(line) ? Axis.NONE : new Axis(best, line);
    }

    private static List<Guide> guides(Box moved, Axis x, Axis y, List<Box> targets) {
        List<Guide> out = new ArrayList<>();
        if (!Float.isNaN(x.line)) {
            float from = moved.t();
            float to = moved.b();
            for (Box t : targets) {
                if (near(t.l(), x.line) || near(t.cx(), x.line) || near(t.r(), x.line)) {
                    from = Math.min(from, t.t());
                    to = Math.max(to, t.b());
                }
            }
            out.add(new Guide(true, x.line, from, to));
        }
        if (!Float.isNaN(y.line)) {
            float from = moved.l();
            float to = moved.r();
            for (Box t : targets) {
                if (near(t.t(), y.line) || near(t.cy(), y.line) || near(t.b(), y.line)) {
                    from = Math.min(from, t.l());
                    to = Math.max(to, t.r());
                }
            }
            out.add(new Guide(false, y.line, from, to));
        }
        return out;
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) < 0.01f;
    }
}
