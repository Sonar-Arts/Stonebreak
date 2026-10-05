package com.openmason.engine.ui.runtime.style;

import com.openmason.engine.format.omui.UiValue;

import java.util.Set;

/** Typed reads of resolved style values (no {@code var()} left). */
public final class StyleValues {

    /** Properties whose computed value children inherit when they do not set it (USS set). */
    public static final Set<String> INHERITED = Set.of("color", "font", "font-size", "text-align", "visibility");

    /** Properties Yoga reads; a change re-pushes the element's layout record. */
    public static final Set<String> LAYOUT = Set.of(
        "display", "position", "flex-direction", "flex-wrap", "justify-content", "align-items", "align-self",
        "align-content", "flex-grow", "flex-shrink", "flex-basis", "width", "height", "min-width", "min-height",
        "max-width", "max-height", "margin-left", "margin-top", "margin-right", "margin-bottom", "padding-left",
        "padding-top", "padding-right", "padding-bottom", "border-left-width", "border-top-width",
        "border-right-width", "border-bottom-width", "left", "top", "right", "bottom", "row-gap", "column-gap",
        "aspect-ratio", "overflow");

    /** Properties that move painted geometry or paint order without a relayout. */
    public static final Set<String> VISUAL = Set.of("translate-x", "translate-y", "-sb-layer", "overflow");

    /** Properties that change a measured leaf's intrinsic size. */
    public static final Set<String> MEASURE = Set.of("font", "font-size");

    private StyleValues() {
    }

    /** A length: points, a percentage, {@code auto}, or unset. */
    public record Length(Kind kind, float value) {
        public enum Kind { UNSET, POINTS, PERCENT, AUTO }

        public static final Length UNSET = new Length(Kind.UNSET, Float.NaN);
        public static final Length AUTO = new Length(Kind.AUTO, Float.NaN);

        public static Length points(float v) {
            return new Length(Kind.POINTS, v);
        }

        public static Length percent(float v) {
            return new Length(Kind.PERCENT, v);
        }

        public boolean isSet() {
            return kind != Kind.UNSET;
        }
    }

    /** @return the length, or {@link Length#UNSET} when {@code v} is absent or not a length */
    public static Length length(UiValue v) {
        if (v instanceof UiValue.Num n) {
            return Length.points((float) n.value());
        }
        if (v instanceof UiValue.Str s) {
            String t = s.value();
            if (t.equals("auto")) {
                return Length.AUTO;
            }
            if (t.endsWith("%")) {
                try {
                    return Length.percent(Float.parseFloat(t.substring(0, t.length() - 1)));
                } catch (NumberFormatException e) {
                    return Length.UNSET;
                }
            }
        }
        return Length.UNSET;
    }

    /** {@code #RRGGBB} or {@code #RRGGBBAA} to ARGB; {@code fallback} otherwise. */
    public static int color(UiValue v, int fallback) {
        if (!(v instanceof UiValue.Str s) || !s.value().startsWith("#")) {
            return fallback;
        }
        String hex = s.value().substring(1);
        try {
            if (hex.length() == 6) {
                return 0xFF000000 | Integer.parseUnsignedInt(hex, 16);
            }
            if (hex.length() == 8) {
                int rgba = Integer.parseUnsignedInt(hex, 16);
                return rgba >>> 8 | rgba << 24;
            }
        } catch (NumberFormatException e) {
            return fallback;
        }
        return fallback;
    }

    public static double number(UiValue v, double fallback) {
        return v instanceof UiValue.Num n ? n.value() : fallback;
    }

    public static String keyword(UiValue v, String fallback) {
        return v instanceof UiValue.Str s ? s.value() : fallback;
    }

    /** True for a {@code var(--name)} reference. */
    public static boolean isVar(UiValue v) {
        return v instanceof UiValue.Str s && s.value().startsWith("var(--") && s.value().endsWith(")");
    }

    /** {@code --name} of a {@code var(--name)} reference. */
    public static String varName(UiValue v) {
        String s = ((UiValue.Str) v).value();
        return s.substring(4, s.length() - 1);
    }
}
