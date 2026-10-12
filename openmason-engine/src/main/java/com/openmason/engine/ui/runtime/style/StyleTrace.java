package com.openmason.engine.ui.runtime.style;

import com.openmason.engine.format.omui.UiValue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Why an element's style is what it is (#293): every sheet rule that matches it, in cascade
 * order, and for each declared property the layer and rule that won. Built by
 * {@link StyleResolver#trace} from the same matching and precedence as {@link StyleResolver#compute},
 * so the editor's "matched rules" view can never disagree with what the runtime paints.
 *
 * <p>Winners are the raw declarations ({@code var()} unresolved); the element's
 * {@link ComputedStyle} holds the resolved values.
 *
 * @param rules   matched rules, lowest precedence first
 * @param winners declared property to the declaration that won it, in first-declared order
 */
public record StyleTrace(List<MatchedRule> rules, Map<String, Declaration> winners) {

    /** Non-sheet layers above the cascade, lowest first (the order {@code UiElement} supplies them). */
    public enum Layer { RULE, INLINE, OVERRIDE, BINDING, LOCAL, ANIMATION }

    public static final StyleTrace EMPTY = new StyleTrace(List.of(), Map.of());

    /**
     * @param sheetId     the sheet reference ({@code pause} or a dependency id)
     * @param ruleIndex   the rule's position in its sheet
     * @param selector    the rule's whole selector list as authored
     * @param specificity specificity of the best matching selector of the list
     * @param rank        sheet rank: theme 0, components {@code 1000 - depth}, document 1000
     */
    public record MatchedRule(String sheetId, int ruleIndex, String selector, int specificity, int rank,
                              Map<String, UiValue> style) {
    }

    /** @param rule the winning rule when {@code layer} is {@link Layer#RULE}, else null */
    public record Declaration(Layer layer, MatchedRule rule, UiValue value) {
    }

    public StyleTrace {
        rules = List.copyOf(rules);
        winners = Collections.unmodifiableMap(new LinkedHashMap<>(winners));
    }

    /** True when {@code rule} sets at least one property that no later declaration overrides. */
    public boolean contributes(MatchedRule rule) {
        for (Declaration d : winners.values()) {
            if (d.rule() == rule) {
                return true;
            }
        }
        return false;
    }

    /** True when {@code property} of {@code rule} is overridden by a later rule or layer. */
    public boolean overridden(MatchedRule rule, String property) {
        Declaration d = winners.get(property);
        return d != null && d.rule() != rule;
    }
}
