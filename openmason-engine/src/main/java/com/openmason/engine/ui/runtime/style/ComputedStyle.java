package com.openmason.engine.ui.runtime.style;

import com.openmason.engine.format.omui.UiStyleProperties;
import com.openmason.engine.format.omui.UiStyleSheet;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The resolved style of one element: every declared or inherited property with
 * {@code var()} substituted, the custom properties visible to its subtree, and the
 * transition each property would animate with. Immutable; recomputed on invalidation.
 */
public final class ComputedStyle {

    public static final ComputedStyle INITIAL = new ComputedStyle(Map.of(), Map.of(), Map.of());

    private final Map<String, UiValue> values;
    private final Map<String, UiValue> customs;
    private final Map<String, UiStyleSheet.StyleTransition> transitions;

    public ComputedStyle(Map<String, UiValue> values, Map<String, UiValue> customs,
                         Map<String, UiStyleSheet.StyleTransition> transitions) {
        this.values = Map.copyOf(values);
        this.customs = Map.copyOf(customs);
        this.transitions = Map.copyOf(transitions);
    }

    /** @return the property's value, or {@code null} when neither declared nor inherited */
    public UiValue get(String property) {
        return values.get(property);
    }

    public Map<String, UiValue> values() {
        return values;
    }

    /** Custom properties ({@code --name}) visible at this element, already resolved. */
    public Map<String, UiValue> customs() {
        return customs;
    }

    /** The transition the winning rules declare for {@code property} ({@code all} included), or null. */
    public UiStyleSheet.StyleTransition transition(String property) {
        UiStyleSheet.StyleTransition t = transitions.get(property);
        return t != null ? t : transitions.get("all");
    }

    public StyleValues.Length length(String property) {
        return StyleValues.length(values.get(property));
    }

    public double number(String property, double fallback) {
        return StyleValues.number(values.get(property), fallback);
    }

    public String keyword(String property, String fallback) {
        return StyleValues.keyword(values.get(property), fallback);
    }

    public int color(String property, int fallbackArgb) {
        return StyleValues.color(values.get(property), fallbackArgb);
    }

    /** {@code display: none}: the element and its subtree take no space and never paint or hit. */
    public boolean collapsed() {
        return "none".equals(keyword("display", "flex"));
    }

    /** {@code visibility: hidden}: keeps its space, never paints or hits. Inherited. */
    public boolean hidden() {
        return "hidden".equals(keyword("visibility", "visible"));
    }

    /** {@code picking-mode: ignore}: paints, but pointer hits pass through to what is below. */
    public boolean pickingIgnored() {
        return "ignore".equals(keyword("picking-mode", "position"));
    }

    /**
     * {@code pointer-events: none} (inherited, so a whole subtree unless a descendant sets
     * {@code auto}): never hit; pointer input goes to what is below.
     */
    public boolean pointerEventsNone() {
        return "none".equals(keyword("pointer-events", "auto"));
    }

    /**
     * This style with animated values on top (#295): each value replaces the property's, after
     * {@code var()} resolves against this style's customs. A value that does not fit is reported
     * and skipped. Inherited properties of children follow the result.
     */
    public ComputedStyle overlay(Map<String, UiValue> animated, String elementKey,
                                 Consumer<UiRuntimeDiagnostic> report) {
        if (animated.isEmpty()) {
            return this;
        }
        Map<String, UiValue> out = new HashMap<>(values);
        animated.forEach((property, raw) -> {
            UiValue v = StyleValues.isVar(raw) ? customs.get(StyleValues.varName(raw)) : raw;
            String problem = v == null ? "unresolved " + raw : UiStyleProperties.problem(property, v);
            if (problem != null) {
                report.accept(UiRuntimeDiagnostic.warning(UiRuntimeDiagnostic.Code.STYLE_VALUE, elementKey,
                    "animated " + property + ": " + problem));
                return;
            }
            out.put(property, v);
        });
        return new ComputedStyle(out, customs, transitions);
    }

    /**
     * This style with {@code parent}'s inherited properties ({@link StyleValues#INHERITED}) filled
     * in where this one does not declare them. Returns {@code this} when nothing is added.
     */
    public ComputedStyle inheriting(ComputedStyle parent) {
        Map<String, UiValue> out = null;
        for (String property : StyleValues.INHERITED) {
            if (!values.containsKey(property)) {
                UiValue v = parent.values.get(property);
                if (v != null) {
                    if (out == null) {
                        out = new HashMap<>(values);
                    }
                    out.put(property, v);
                }
            }
        }
        return out == null ? this : new ComputedStyle(out, customs, transitions);
    }

    /** Names of properties whose value differs from {@code other} (customs excluded). */
    public Set<String> changedProperties(ComputedStyle other) {
        Set<String> changed = new HashSet<>();
        values.forEach((k, v) -> {
            if (!v.equals(other.values.get(k))) {
                changed.add(k);
            }
        });
        other.values.keySet().forEach(k -> {
            if (!values.containsKey(k)) {
                changed.add(k);
            }
        });
        return changed;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ComputedStyle c && values.equals(c.values) && customs.equals(c.customs)
            && transitions.equals(c.transitions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(values, customs, transitions);
    }

    @Override
    public String toString() {
        return "ComputedStyle" + values;
    }
}
