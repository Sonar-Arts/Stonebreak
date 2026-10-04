package com.openmason.engine.format.omui;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code styles/<id>.uss.json}: a USS-like selector sheet. Selector syntax is validated by
 * {@link UiSelectors}; matching, specificity and cascade belong to the runtime (#287).
 *
 * @param id           in-archive id (matches the entry name)
 * @param variables    {@code --name} design tokens declared by this sheet
 * @param customStates custom pseudo-states usable as {@code :name} beyond the built-ins
 * @param rules        rules in source order (later wins on equal specificity)
 */
public record UiStyleSheet(String id, Map<String, UiValue> variables, List<String> customStates,
                           List<StyleRule> rules, Map<String, UiValue> unknown) {

    public UiStyleSheet {
        Objects.requireNonNull(id, "id");
        variables = Canon.values(variables);
        customStates = Canon.sortedUnique(customStates);
        rules = Canon.list(rules);
        unknown = Canon.unknown(unknown);
    }

    /**
     * @param selector    selector list ({@code Button.menu:hover, #resume})
     * @param style       declarations, key-sorted
     * @param transitions transitions this rule declares, sorted by property
     */
    public record StyleRule(String selector, Map<String, UiValue> style, List<StyleTransition> transitions,
                            Map<String, UiValue> unknown) {
        public StyleRule {
            Objects.requireNonNull(selector, "selector");
            style = Canon.values(style);
            transitions = Canon.sortedBy(transitions, StyleTransition::property);
            unknown = Canon.unknown(unknown);
        }
    }

    /** Durations and delays are seconds (binary64). */
    public record StyleTransition(String property, double duration, UiEasing easing, double delay,
                                  Map<String, UiValue> unknown) {
        public StyleTransition {
            Objects.requireNonNull(property, "property");
            easing = easing == null ? UiEasing.LINEAR : easing;
            duration = Canon.num(duration);
            delay = Canon.num(delay);
            unknown = Canon.unknown(unknown);
        }
    }
}
