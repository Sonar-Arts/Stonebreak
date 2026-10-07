package com.openmason.engine.ui.runtime.anim;

import com.openmason.engine.format.omui.UiStyleProperties;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.style.StyleValues;
import com.openmason.engine.ui.runtime.widget.PropertyDescriptor;
import com.openmason.engine.ui.runtime.widget.WidgetDescriptor;

import java.util.Set;

/**
 * The logical type of an animated channel (#295): how its values interpolate, whether moving it
 * is "motion" (reduced motion shortens it to nothing) and whether it relayouts every frame.
 *
 * <p>Interpolation is deterministic: numbers lerp in binary64, colours lerp each straight-alpha
 * ARGB byte and round, {@code visibility} shows the visible end for the whole transition, and
 * everything else ({@link Interp#DISCRETE}: keywords, assets and sprite frames, text) switches to
 * the next value when its segment ends.
 *
 * @param target binding-target syntax: {@code style:opacity}, {@code prop:text}
 */
public record AnimProperty(String target, Interp interp, boolean motion, boolean relayout) {

    public enum Interp { NUMBER, INTEGER, LENGTH, OPACITY, COLOR, VISIBILITY, DISCRETE }

    /** Style properties whose change moves something on screen (reduced motion skips them). */
    private static final Set<String> MOTION = Set.of("translate-x", "translate-y", "scale", "rotate", "left", "top",
        "right", "bottom", "width", "height", "margin-left", "margin-top", "margin-right", "margin-bottom",
        "flex-basis", "flex-grow");

    public static final String STYLE = "style:";
    public static final String PROP = "prop:";

    public boolean isStyle() {
        return target.startsWith(STYLE);
    }

    /** The property name without its {@code style:}/{@code prop:} prefix. */
    public String name() {
        return target.substring(target.indexOf(':') + 1);
    }

    /** True when values of this channel blend (everything but {@link Interp#DISCRETE}). */
    public boolean interpolates() {
        return interp != Interp.DISCRETE;
    }

    /** @return the style channel, or null for an unknown or custom property */
    public static AnimProperty style(String property) {
        UiStyleProperties.Spec spec = UiStyleProperties.spec(property);
        if (spec == null) {
            return null;
        }
        Interp interp = switch (spec.kind()) {
            case LENGTH, LENGTH_NO_AUTO -> Interp.LENGTH;
            case NUMBER -> "-sb-layer".equals(property) ? Interp.DISCRETE : Interp.NUMBER;
            case UNIT_INTERVAL -> Interp.OPACITY;
            case COLOR -> Interp.COLOR;
            case KEYWORD -> "visibility".equals(property) ? Interp.VISIBILITY : Interp.DISCRETE;
            case ASSET, STRING -> Interp.DISCRETE;
        };
        return new AnimProperty(STYLE + property, interp, MOTION.contains(property),
            StyleValues.LAYOUT.contains(property));
    }

    /** @return the widget-property channel, or null when {@code widget} has no such property */
    public static AnimProperty prop(String name, WidgetDescriptor widget) {
        PropertyDescriptor p = widget.property(name);
        if (p == null) {
            return null;
        }
        Interp interp = switch (p.type()) {
            case NUMBER -> Interp.NUMBER;
            case INT -> Interp.INTEGER;
            case COLOR -> Interp.COLOR;
            default -> Interp.DISCRETE;
        };
        return new AnimProperty(PROP + name, interp, false, false);
    }

    /** Resolves a binding-target string against {@code widget}; null when it cannot be animated. */
    public static AnimProperty of(String target, WidgetDescriptor widget) {
        if (target.startsWith(STYLE)) {
            return style(target.substring(STYLE.length()));
        }
        if (target.startsWith(PROP)) {
            return prop(target.substring(PROP.length()), widget);
        }
        return null;
    }

    // ── interpolation ───────────────────────────────────────────────────────

    /**
     * {@code from → to} at eased fraction {@code f}. {@code f >= 1} is exactly {@code to}; a
     * {@code null} end means "nothing set" and is replaced by a neutral value of the other end.
     */
    public UiValue mix(UiValue from, UiValue to, float f) {
        if (from == null) {
            from = neutral(to);
        }
        if (to == null) {
            to = neutral(from);
        }
        if (f >= 1f) {
            return to;
        }
        if (f <= 0f) {
            return from;
        }
        return switch (interp) {
            case NUMBER, OPACITY -> from instanceof UiValue.Num a && to instanceof UiValue.Num b
                ? UiValue.of(lerp(a.value(), b.value(), f)) : from;
            case INTEGER -> from instanceof UiValue.Num a && to instanceof UiValue.Num b
                ? UiValue.of(Math.floor(lerp(a.value(), b.value(), f))) : from;
            case LENGTH -> length(from, to, f);
            case COLOR -> isColor(from) && isColor(to) ? color(from, to, f) : from;
            case VISIBILITY -> "visible".equals(StyleValues.keyword(from, "visible"))
                || "visible".equals(StyleValues.keyword(to, "visible")) ? UiValue.of("visible") : from;
            case DISCRETE -> from;
        };
    }

    /** The value something unset animates from or to: identity transforms, full opacity, clear colour. */
    public UiValue neutral(UiValue other) {
        String n = name();
        return switch (interp) {
            case OPACITY -> UiValue.of(1);
            case NUMBER, INTEGER -> UiValue.of("scale".equals(n) ? 1 : 0);
            case LENGTH -> other instanceof UiValue.Str s && s.value().endsWith("%") ? UiValue.of("0%") : UiValue.of(0);
            case COLOR -> isColor(other) ? transparent(other) : UiValue.of("#00000000");
            case VISIBILITY -> UiValue.of("visible");
            case DISCRETE -> other == null ? UiValue.NULL : other;
        };
    }

    private static double lerp(double a, double b, float f) {
        return a + (b - a) * f;
    }

    private static UiValue length(UiValue from, UiValue to, float f) {
        if (from instanceof UiValue.Num a && to instanceof UiValue.Num b) {
            return UiValue.of(lerp(a.value(), b.value(), f));
        }
        StyleValues.Length a = StyleValues.length(from);
        StyleValues.Length b = StyleValues.length(to);
        if (a.kind() == StyleValues.Length.Kind.PERCENT && b.kind() == StyleValues.Length.Kind.PERCENT) {
            return UiValue.of(percent(lerp(a.value(), b.value(), f)));
        }
        return from; // auto or mixed units: discrete
    }

    private static String percent(double v) {
        double r = Math.round(v * 1e4) / 1e4;
        return (r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r)) + "%";
    }

    static boolean isColor(UiValue v) {
        return v instanceof UiValue.Str s && s.value().startsWith("#");
    }

    private static UiValue color(UiValue from, UiValue to, float f) {
        int ca = StyleValues.color(from, 0);
        int cb = StyleValues.color(to, 0);
        int argb = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int x = (ca >>> shift) & 0xFF;
            int y = (cb >>> shift) & 0xFF;
            argb |= (Math.round(x + (y - x) * f) & 0xFF) << shift;
        }
        return hex(argb);
    }

    private static UiValue transparent(UiValue color) {
        return hex(StyleValues.color(color, 0) & 0x00FFFFFF);
    }

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
    /** R, G, B, A byte positions in ARGB. */
    private static final int[] HEX_SHIFTS = {16, 8, 0, 24};

    /** {@code #RRGGBBAA} without {@code String.format} (sampled every frame of a colour animation). */
    static UiValue hex(int argb) {
        char[] c = new char[9];
        c[0] = '#';
        for (int i = 0; i < 4; i++) {
            int b = (argb >>> HEX_SHIFTS[i]) & 0xFF;
            c[1 + i * 2] = HEX[b >>> 4];
            c[2 + i * 2] = HEX[b & 0xF];
        }
        return UiValue.of(new String(c));
    }

    /** The value a key track of this channel shows at clip time {@code t}, exactly as playback samples it. */
    public UiValue sample(java.util.List<com.openmason.engine.format.omui.UiAnimationClip.AnimKey> keys, double t) {
        return keys.isEmpty() ? null : ClipPlayback.sampleTrack(this, keys, t);
    }

    /** @return null when {@code value} suits this channel on {@code widget}, else the problem */
    public String problem(UiValue value, WidgetDescriptor widget) {
        if (isStyle()) {
            return UiStyleProperties.problem(name(), value);
        }
        PropertyDescriptor p = widget.property(name());
        return p == null ? widget.type() + " has no property " + name() : p.problem(value);
    }
}
