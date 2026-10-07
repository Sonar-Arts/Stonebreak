package com.openmason.engine.ui.fidelity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compares named rects ({@code [x, y, width, height]}, framebuffer pixels, origin top-left) of
 * a candidate against the legacy oracle under a {@link GeometryRule} (#296). Names are the ones
 * of {@code ui/fixtures/legacy-geometry.json} ({@code panel}, {@code resume}, {@code main0}, ...).
 */
public final class GeometryComparator {

    private static final String[] AXES = {"x", "y", "width", "height"};

    private GeometryComparator() {
    }

    /**
     * @param problems one line per missing, extra or out-of-tolerance rect
     * @param worst    largest absolute difference over every compared value
     */
    public record GeometryReport(List<String> problems, double worst) {

        public GeometryReport {
            problems = List.copyOf(problems);
        }

        public boolean passed() {
            return problems.isEmpty();
        }

        public String summary() {
            return passed() ? "geometry ok (worst " + fmt(worst) + " px)" : String.join("; ", problems);
        }
    }

    public static GeometryReport compare(Map<String, float[]> expected, Map<String, float[]> actual,
                                         int framebufferWidth, int framebufferHeight, GeometryRule rule) {
        Objects.requireNonNull(rule, "rule");
        List<String> problems = new ArrayList<>();
        double worst = 0;
        double tolX = rule.tolerance(framebufferWidth);
        double tolY = rule.tolerance(framebufferHeight);
        for (Map.Entry<String, float[]> e : expected.entrySet()) {
            float[] got = actual.get(e.getKey());
            if (got == null) {
                problems.add(e.getKey() + ": missing");
                continue;
            }
            float[] want = e.getValue();
            for (int i = 0; i < 4; i++) {
                double d = Math.abs((double) want[i] - got[i]);
                worst = Math.max(worst, d);
                double tol = (i & 1) == 0 ? tolX : tolY;
                if (d > tol) {
                    problems.add(e.getKey() + "." + AXES[i] + ": " + fmt(got[i]) + " vs legacy " + fmt(want[i]));
                }
            }
        }
        for (String name : actual.keySet()) {
            if (!expected.containsKey(name)) {
                problems.add(name + ": not in the legacy oracle");
            }
        }
        return new GeometryReport(problems, worst);
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.format(java.util.Locale.ROOT, "%.3f", v);
    }
}
