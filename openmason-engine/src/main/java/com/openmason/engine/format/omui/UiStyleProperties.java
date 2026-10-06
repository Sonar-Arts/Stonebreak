package com.openmason.engine.format.omui;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The style properties of schema 1.0 and the JSON value each accepts. Layout properties are
 * style properties (Unity convention) and follow {@code flex-1} (Yoga v3.2.1, frozen by #283).
 *
 * <p>Value grammar: a JSON number is a length in logical pixels (or a plain number for
 * unitless properties); a string is a keyword, {@code N%}, {@code #RRGGBB[AA]},
 * {@code var(--token)} or, for asset properties, a reference. Custom properties
 * ({@code --name}) accept any value. Unknown property names are a warning, not an error.
 */
public final class UiStyleProperties {

    public enum Kind {
        /** number (px), {@code N%}, {@code auto} */
        LENGTH,
        /** number (px), {@code N%} — no {@code auto} (padding, border, gap, insets for border) */
        LENGTH_NO_AUTO,
        /** plain number */
        NUMBER,
        /** number in [0, 1] */
        UNIT_INTERVAL,
        COLOR,
        /** in-archive or dependency reference, or {@code none} */
        ASSET,
        STRING,
        KEYWORD
    }

    public record Spec(Kind kind, Set<String> keywords) {
    }

    private static final Pattern PERCENT = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?%");
    private static final Pattern COLOR = Pattern.compile("#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})");
    private static final Pattern VAR = Pattern.compile("var\\(--[A-Za-z0-9_-]+\\)");

    private static final Set<String> ALIGN = Set.of("auto", "flex-start", "center", "flex-end", "stretch",
            "baseline", "space-between", "space-around", "space-evenly");

    private static final Map<String, Spec> SPECS = new HashMap<>();

    static {
        keyword("display", "flex", "none");
        keyword("position", "relative", "absolute");
        keyword("flex-direction", "column", "column-reverse", "row", "row-reverse");
        keyword("flex-wrap", "nowrap", "wrap", "wrap-reverse");
        keyword("justify-content", "flex-start", "center", "flex-end", "space-between", "space-around",
                "space-evenly");
        SPECS.put("align-items", new Spec(Kind.KEYWORD, ALIGN));
        SPECS.put("align-self", new Spec(Kind.KEYWORD, ALIGN));
        SPECS.put("align-content", new Spec(Kind.KEYWORD, ALIGN));
        plain(Kind.NUMBER, "flex-grow", "flex-shrink", "aspect-ratio", "scale", "rotate");
        plain(Kind.LENGTH, "flex-basis", "width", "height", "min-width", "min-height", "max-width", "max-height",
                "margin-left", "margin-top", "margin-right", "margin-bottom", "left", "top", "right", "bottom");
        plain(Kind.LENGTH_NO_AUTO, "padding-left", "padding-top", "padding-right", "padding-bottom",
                "border-left-width", "border-top-width", "border-right-width", "border-bottom-width",
                "row-gap", "column-gap", "translate-x", "translate-y", "font-size", "border-radius",
                "transform-origin-x", "transform-origin-y");
        plain(Kind.UNIT_INTERVAL, "opacity");
        plain(Kind.COLOR, "color", "background-color", "border-color", "-sb-tint");
        plain(Kind.ASSET, "background-image", "font");
        keyword("visibility", "visible", "hidden");
        keyword("overflow", "visible", "hidden", "scroll"); // scroll needs the ui-scroll feature
        plain(Kind.NUMBER, "-sb-layer");
        keyword("picking-mode", "position", "ignore");
        keyword("text-align", "left", "center", "right");
        keyword("-sb-image-scale", "stretch", "nine-slice", "tile", "integer");
        keyword("-sb-sampling", "nearest", "linear");
    }

    private UiStyleProperties() {
    }

    private static void keyword(String name, String... words) {
        SPECS.put(name, new Spec(Kind.KEYWORD, Set.of(words)));
    }

    private static void plain(Kind kind, String... names) {
        for (String n : names) {
            SPECS.put(n, new Spec(kind, Set.of()));
        }
    }

    /** @return the spec, or {@code null} for unknown and custom ({@code --}) properties */
    public static Spec spec(String property) {
        return SPECS.get(property);
    }

    /** Every built-in property name, sorted (editor pickers). */
    public static java.util.SortedSet<String> names() {
        return java.util.Collections.unmodifiableSortedSet(new java.util.TreeSet<>(SPECS.keySet()));
    }

    public static boolean isKnown(String property) {
        return SPECS.containsKey(property) || isCustom(property);
    }

    public static boolean isCustom(String property) {
        return property.startsWith("--") && property.length() > 2 && UiSelectors.isIdent(property);
    }

    /** @return {@code null} when {@code value} suits {@code property}, else the problem */
    public static String problem(String property, UiValue value) {
        Spec spec = SPECS.get(property);
        if (spec == null) {
            return null; // custom or unknown: not checked here
        }
        if (value instanceof UiValue.Str s && VAR.matcher(s.value()).matches()) {
            return null;
        }
        boolean ok = switch (spec.kind()) {
            case LENGTH -> value instanceof UiValue.Num
                    || value instanceof UiValue.Str s && (s.value().equals("auto") || PERCENT.matcher(s.value()).matches());
            case LENGTH_NO_AUTO -> value instanceof UiValue.Num
                    || value instanceof UiValue.Str s && PERCENT.matcher(s.value()).matches();
            case NUMBER -> value instanceof UiValue.Num;
            case UNIT_INTERVAL -> value instanceof UiValue.Num n && n.value() >= 0 && n.value() <= 1;
            case COLOR -> value instanceof UiValue.Str s && COLOR.matcher(s.value()).matches();
            case ASSET, STRING -> value instanceof UiValue.Str;
            case KEYWORD -> value instanceof UiValue.Str s && spec.keywords().contains(s.value());
        };
        if (ok) {
            return null;
        }
        return switch (spec.kind()) {
            case KEYWORD -> "expected one of " + new TreeSet<>(spec.keywords());
            case LENGTH -> "expected a number (px), \"N%\" or \"auto\"";
            case LENGTH_NO_AUTO -> "expected a number (px) or \"N%\"";
            case UNIT_INTERVAL -> "expected a number in [0, 1]";
            case COLOR -> "expected \"#RRGGBB\" or \"#RRGGBBAA\"";
            default -> "expected a " + spec.kind().name().toLowerCase(Locale.ROOT);
        };
    }
}
