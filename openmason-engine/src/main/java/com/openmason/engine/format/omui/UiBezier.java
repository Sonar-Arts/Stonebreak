package com.openmason.engine.format.omui;

import java.util.List;
import java.util.Locale;

/**
 * A CSS-style {@code cubic-bezier(x1, y1, x2, y2)} timing curve (#295): the segment's progress is
 * the curve's y at the point where its x equals the time fraction. On the wire it is
 * {@code "bezier": [x1, y1, x2, y2]} beside a key's or transition's {@code easing}, which it
 * overrides; a reader that predates it keeps it as an unknown field and falls back to the named
 * easing, so no format feature is needed.
 *
 * @param x1 first control point x, in [0, 1] (so x is monotonic and the curve a function of time)
 * @param y1 first control point y, in [{@link #MIN_Y}, {@link #MAX_Y}] (overshoot allowed)
 */
public record UiBezier(double x1, double y1, double x2, double y2) {

    public static final double MIN_Y = -10;
    public static final double MAX_Y = 10;

    public UiBezier {
        x1 = Canon.num(x1);
        y1 = Canon.num(y1);
        x2 = Canon.num(x2);
        y2 = Canon.num(y2);
    }

    /** @return null when the curve is valid, else the problem */
    public String problem() {
        if (!(x1 >= 0 && x1 <= 1 && x2 >= 0 && x2 <= 1)) {
            return "bezier x values must lie in [0, 1]";
        }
        if (!(y1 >= MIN_Y && y1 <= MAX_Y && y2 >= MIN_Y && y2 <= MAX_Y)) {
            return "bezier y values must lie in [" + (int) MIN_Y + ", " + (int) MAX_Y + "]";
        }
        return null;
    }

    /** Progress at time fraction {@code u} in [0, 1]: exact at both ends. */
    public double apply(double u) {
        if (u <= 0) {
            return 0;
        }
        if (u >= 1) {
            return 1;
        }
        return sample(y1, y2, solveT(u));
    }

    /** The curve parameter whose x is {@code u}: Newton steps, then bisection when they stall. */
    private double solveT(double u) {
        double t = u;
        for (int i = 0; i < 8; i++) {
            double err = sample(x1, x2, t) - u;
            if (Math.abs(err) < 1e-9) {
                return t;
            }
            double d = slope(x1, x2, t);
            if (Math.abs(d) < 1e-9) {
                break;
            }
            t -= err / d;
            if (t < 0 || t > 1) {
                break;
            }
        }
        double lo = 0;
        double hi = 1;
        t = u;
        for (int i = 0; i < 64; i++) {
            double x = sample(x1, x2, t);
            if (Math.abs(x - u) < 1e-9) {
                break;
            }
            if (x < u) {
                lo = t;
            } else {
                hi = t;
            }
            t = (lo + hi) / 2;
        }
        return t;
    }

    /** B(t) of the 1D cubic with end points 0 and 1. */
    private static double sample(double p1, double p2, double t) {
        double mt = 1 - t;
        return 3 * mt * mt * t * p1 + 3 * mt * t * t * p2 + t * t * t;
    }

    private static double slope(double p1, double p2, double t) {
        double mt = 1 - t;
        return 3 * mt * mt * p1 + 6 * mt * t * (p2 - p1) + 3 * t * t * (1 - p2);
    }

    /** Progress of a segment eased by {@code bezier} when present, else by {@code easing}. */
    public static float ease(UiEasing easing, UiBezier bezier, float u) {
        return bezier != null ? (float) bezier.apply(u) : easing.curve().apply(u);
    }

    public List<UiValue> wire() {
        return List.of(UiValue.of(x1), UiValue.of(y1), UiValue.of(x2), UiValue.of(y2));
    }

    /** {@code [x1, y1, x2, y2]} from the wire, or null when it is not four numbers. */
    public static UiBezier fromWire(UiValue v) {
        if (!(v instanceof UiValue.Arr a) || a.items().size() != 4) {
            return null;
        }
        double[] p = new double[4];
        for (int i = 0; i < 4; i++) {
            if (!(a.items().get(i) instanceof UiValue.Num n)) {
                return null;
            }
            p[i] = n.value();
        }
        return new UiBezier(p[0], p[1], p[2], p[3]);
    }

    /** CSS text {@code cubic-bezier(0.17, 0.67, 0.83, 0.67)}, or null when {@code text} is not one. */
    public static UiBezier parse(String text) {
        String s = text.trim().toLowerCase(Locale.ROOT);
        if (!s.startsWith("cubic-bezier(") || !s.endsWith(")")) {
            return null;
        }
        String[] parts = s.substring(13, s.length() - 1).split(",");
        if (parts.length != 4) {
            return null;
        }
        try {
            return new UiBezier(Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim()),
                Double.parseDouble(parts[2].trim()), Double.parseDouble(parts[3].trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "cubic-bezier(%s, %s, %s, %s)", n(x1), n(y1), n(x2), n(y2));
    }

    private static String n(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
