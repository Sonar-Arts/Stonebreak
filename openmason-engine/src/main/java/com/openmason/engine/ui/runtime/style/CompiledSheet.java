package com.openmason.engine.ui.runtime.style;

import com.openmason.engine.format.omui.UiStyleSheet;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A style sheet with its selectors compiled once. Shared by every instance that uses the
 * sheet; immutable.
 *
 * @param id     the reference the document used ({@code pause} or a dependency id)
 */
public record CompiledSheet(String id, UiStyleSheet source, List<Rule> rules) {

    /**
     * @param index       source order inside the sheet (later wins on equal specificity)
     * @param selectors   the rule's selector list
     */
    public record Rule(int index, List<Selector> selectors, Map<String, UiValue> style,
                       List<UiStyleSheet.StyleTransition> transitions) {
        public Rule {
            selectors = List.copyOf(selectors);
            style = Map.copyOf(style);
            transitions = List.copyOf(transitions);
        }

        /** Highest specificity among the selectors that match, or -1. */
        public int match(Styleable element) {
            int best = -1;
            for (Selector s : selectors) {
                if (s.specificity() > best && SelectorMatcher.matches(s, element)) {
                    best = s.specificity();
                }
            }
            return best;
        }
    }

    public CompiledSheet {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(source, "source");
        rules = List.copyOf(rules);
    }

    /** Compiles {@code sheet}; a rule whose selector cannot compile is reported and skipped. */
    public static CompiledSheet compile(String id, UiStyleSheet sheet, Consumer<UiRuntimeDiagnostic> diagnostics) {
        List<Rule> rules = new ArrayList<>();
        var states = new HashSet<>(sheet.customStates());
        List<UiStyleSheet.StyleRule> source = sheet.rules();
        for (int i = 0; i < source.size(); i++) {
            UiStyleSheet.StyleRule rule = source.get(i);
            try {
                rules.add(new Rule(i, SelectorParser.parseList(rule.selector(), states), rule.style(),
                    rule.transitions()));
            } catch (IllegalArgumentException e) {
                diagnostics.accept(UiRuntimeDiagnostic.error(UiRuntimeDiagnostic.Code.STYLE_VALUE, "",
                    "sheet " + id + " rule " + i + ": " + e.getMessage()));
            }
        }
        return new CompiledSheet(id, sheet, rules);
    }
}
