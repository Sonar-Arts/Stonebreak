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
 * {@code var(--token)} or, for asset properties, a reference. Box lengths (size, position, margin,
 * padding, border widths, gaps, {@code border-radius}), {@code font-size} and the shadow offsets also
 * take {@code Ndpx}: device pixels that the UI scale never multiplies, the constants legacy renderers
 * drew with (feature {@code ui-masonry}). Custom properties
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
        KEYWORD,
        /**
         * A theme entry name (#384): {@code auto}/{@code none} or a bare engine name or a namespaced
         * game name ({@code game:hotbar}). The format checks the spelling; whether the host's theme
         * has the entry is a runtime diagnostic ({@code UNKNOWN_SURFACE}/{@code UNKNOWN_SYMBOL}).
         */
        NAME
    }

    public record Spec(Kind kind, Set<String> keywords) {
    }

    private static final Pattern PERCENT = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?%");
    private static final Pattern COLOR = Pattern.compile("#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})");
    private static final Pattern VAR = Pattern.compile("var\\(--[A-Za-z0-9_-]+\\)");
    /** A theme entry name: an optional {@code namespace:} then a lower-case hyphenated identifier. */
    private static final Pattern THEME_NAME = Pattern.compile("([a-z0-9_.-]{1,64}:)?[a-z][a-z0-9-]{0,63}");
    private static final Pattern DEVICE = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?dpx");

    /** Lengths that also accept {@code Ndpx} (box geometry; not text or transforms). */
    private static final Set<String> DEVICE_LENGTHS = Set.of("flex-basis", "width", "height", "min-width",
            "min-height", "max-width", "max-height", "margin-left", "margin-top", "margin-right", "margin-bottom",
            "left", "top", "right", "bottom", "padding-left", "padding-top", "padding-right", "padding-bottom",
            "border-left-width", "border-top-width", "border-right-width", "border-bottom-width", "row-gap",
            "column-gap", "border-radius", "-sb-baseline", "line-height", "font-size", "-sb-shadow-offset-x",
            "-sb-shadow-offset-y", "-sb-shadow-blur", "translate-x", "translate-y", "-sb-tooltip-offset");

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
        keyword("-sb-sampling", "nearest", "linear", "nearest-raw");
        // ui-text: wrapping, truncation and line limits of labels
        keyword("white-space", "nowrap", "normal", "pre-wrap");
        keyword("text-overflow", "clip", "ellipsis");
        plain(Kind.NUMBER, "-sb-max-lines");
        // ui-cursor: pointer-anchored cursor layer and click-through subtrees
        keyword("-sb-anchor", "none", "pointer");
        keyword("pointer-events", "auto", "none");
        // ui-masonry: the house look as style (#297); the names come from the host's theme (#384)
        plain(Kind.NAME, "-sb-surface");
        keyword("-sb-text-effect", "shadow", "none", "title");
        keyword("-sb-pixel-grid", "device", "none");
        keyword("-sb-font-grid", "half", "none");
        // ui-masonry (#299): an explicit first text baseline, and the theme's vector symbols on any element
        plain(Kind.LENGTH_NO_AUTO, "-sb-baseline");
        plain(Kind.NAME, "-sb-symbol");
        // ui-masonry (#299): a drop shadow under the element's own paint (the logo's)
        plain(Kind.COLOR, "-sb-shadow-color");
        plain(Kind.LENGTH_NO_AUTO, "-sb-shadow-offset-x", "-sb-shadow-offset-y", "-sb-shadow-blur");
        // ui-masonry (#299): the legacy text field look (underscore caret, a stroke centred on the edge)
        keyword("-sb-caret", "bar", "underscore");
        keyword("-sb-border-align", "inside", "center");
        // ui-masonry (#299): an anti-aliased child clip (the legacy scroll viewport's) at fractional edges
        keyword("-sb-clip", "hard", "antialias");
        // ui-masonry (#301): the colour of a label's shadow passes, as the legacy world markers draw them
        plain(Kind.COLOR, "-sb-text-shadow-color");
        // ui-masonry (#301): on a document's root, how far a pointer tooltip sits from the pointer (default 15)
        plain(Kind.LENGTH_NO_AUTO, "-sb-tooltip-offset");
        // ui-text (#299): the pitch of a label's lines
        plain(Kind.LENGTH_NO_AUTO, "line-height");
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

    /** True for a well-formed theme entry name ({@link Kind#NAME}), keywords included. */
    public static boolean isThemeName(String value) {
        return value != null && THEME_NAME.matcher(value).matches();
    }

    /** {@code auto} and {@code none}: {@link Kind#NAME} keywords no theme may register. */
    public static boolean isReservedThemeName(String value) {
        return "auto".equals(value) || "none".equals(value);
    }

    /** True for an {@code Ndpx} device-pixel length. */
    public static boolean isDeviceLength(String value) {
        return DEVICE.matcher(value).matches();
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
        if (value instanceof UiValue.Str s && isDeviceLength(s.value())) {
            return DEVICE_LENGTHS.contains(property) ? null : "device pixels (\"Ndpx\") are not allowed here";
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
            case NAME -> value instanceof UiValue.Str s && isThemeName(s.value());
        };
        if (ok) {
            return null;
        }
        return switch (spec.kind()) {
            case KEYWORD -> "expected one of " + new TreeSet<>(spec.keywords());
            case NAME -> "expected a theme name (\"name\" or \"namespace:name\")";
            case LENGTH -> "expected a number (px), \"N%\" or \"auto\"";
            case LENGTH_NO_AUTO -> "expected a number (px) or \"N%\"";
            case UNIT_INTERVAL -> "expected a number in [0, 1]";
            case COLOR -> "expected \"#RRGGBB\" or \"#RRGGBBAA\"";
            default -> "expected a " + spec.kind().name().toLowerCase(Locale.ROOT);
        };
    }
}
