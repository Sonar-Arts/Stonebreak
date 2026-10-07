package com.openmason.engine.ui.runtime.layout;

import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic.Code;
import com.openmason.engine.ui.runtime.style.ComputedStyle;
import com.openmason.engine.ui.runtime.style.StyleValues.Length;

import java.util.function.Consumer;

/**
 * Per-element layout diagnostics Yoga resolves silently (#287 "report cyclic dependencies
 * per element"):
 *
 * <ul>
 *   <li><b>cyclic percentage</b> — a percentage size along an axis where the parent's size
 *       depends on its content, so the percentage refers to itself and Yoga treats it as
 *       {@code auto};</li>
 *   <li><b>conflicting constraints</b> — {@code min} larger than {@code max}, or an absolute
 *       element with both opposite insets and an explicit size (the far inset is ignored).</li>
 * </ul>
 *
 * The definiteness rules mirror {@code flex-1}: the root is the viewport; a point size is
 * definite; a percentage is definite when its parent is; an absolute element is definite on
 * an axis with both insets set; a stretched cross-axis child and a growing main-axis child
 * inherit the parent's definiteness.
 */
public final class LayoutChecks {

    private LayoutChecks() {
    }

    public static void check(UiElement el, Consumer<UiRuntimeDiagnostic> diagnostics) {
        ComputedStyle s = el.computedStyle();
        if (s.collapsed()) {
            return;
        }
        if (el.parent() != null) {
            for (boolean horizontal : new boolean[]{true, false}) {
                String dim = horizontal ? "width" : "height";
                if (s.length(dim).kind() == Length.Kind.PERCENT && !definite(el.parent(), horizontal)) {
                    diagnostics.accept(UiRuntimeDiagnostic.warning(Code.CYCLIC_PERCENTAGE, el.key(),
                        dim + ": " + s.length(dim).value() + "% of a parent whose " + dim
                            + " depends on its content; resolves as auto"));
                }
            }
        }
        for (String dim : new String[]{"width", "height"}) {
            Length min = s.length("min-" + dim);
            Length max = s.length("max-" + dim);
            if (min.kind() == max.kind() && min.kind() != Length.Kind.UNSET && min.kind() != Length.Kind.AUTO
                && min.value() > max.value()) {
                diagnostics.accept(UiRuntimeDiagnostic.warning(Code.CONFLICTING_CONSTRAINTS, el.key(),
                    "min-" + dim + " " + min.value() + " exceeds max-" + dim + " " + max.value() + "; min wins"));
            }
        }
        if (absolute(s)) {
            overConstrained(el, s, "left", "right", "width", diagnostics);
            overConstrained(el, s, "top", "bottom", "height", diagnostics);
        }
    }

    private static void overConstrained(UiElement el, ComputedStyle s, String near, String far, String dim,
                                        Consumer<UiRuntimeDiagnostic> diagnostics) {
        if (isLength(s.length(near)) && isLength(s.length(far)) && isLength(s.length(dim))) {
            diagnostics.accept(UiRuntimeDiagnostic.warning(Code.CONFLICTING_CONSTRAINTS, el.key(),
                near + ", " + far + " and " + dim + " are all set; " + far + " is ignored"));
        }
    }

    /** True when {@code el}'s size on the axis does not depend on its content. */
    static boolean definite(UiElement el, boolean horizontal) {
        ComputedStyle s = el.computedStyle();
        Length size = s.length(horizontal ? "width" : "height");
        if (size.kind() == Length.Kind.POINTS) {
            return true;
        }
        UiElement parent = el.parent();
        if (parent == null) {
            return true; // the viewport
        }
        if (size.kind() == Length.Kind.PERCENT) {
            return definite(parent, horizontal);
        }
        if (absolute(s)) {
            return isLength(s.length(horizontal ? "left" : "top")) && isLength(s.length(horizontal ? "right" : "bottom"))
                && definite(parent, horizontal);
        }
        String direction = parent.computedStyle().keyword("flex-direction", "column");
        boolean parentRow = direction.startsWith("row");
        boolean mainAxis = parentRow == horizontal;
        if (mainAxis) {
            return s.number("flex-grow", 0) > 0 && definite(parent, horizontal);
        }
        String align = s.keyword("align-self", "auto");
        if (align.equals("auto")) {
            align = parent.computedStyle().keyword("align-items", "stretch");
        }
        boolean autoMargin = s.length(horizontal ? "margin-left" : "margin-top").kind() == Length.Kind.AUTO
            || s.length(horizontal ? "margin-right" : "margin-bottom").kind() == Length.Kind.AUTO;
        return align.equals("stretch") && !autoMargin && definite(parent, horizontal);
    }

    private static boolean absolute(ComputedStyle s) {
        return "absolute".equals(s.keyword("position", "relative"));
    }

    private static boolean isLength(Length l) {
        return l.kind() == Length.Kind.POINTS || l.kind() == Length.Kind.PERCENT;
    }
}
