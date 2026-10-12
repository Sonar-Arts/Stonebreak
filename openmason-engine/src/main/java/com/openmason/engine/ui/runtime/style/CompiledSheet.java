package com.openmason.engine.ui.runtime.style;

import com.openmason.engine.format.omui.UiStyleSheet;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A style sheet with its selectors compiled once. Shared by every instance that uses the
 * sheet; immutable.
 *
 * <p>Rules are also indexed by the most selective token of each selector's subject compound
 * ({@code #name}, else the first {@code .class}, else the type, else universal), so resolving an
 * element only tests selectors that could possibly match it ({@link #candidates}) instead of every
 * rule of every sheet.
 */
public final class CompiledSheet {

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

    /** One selector of one rule, filed under its subject's most selective token. */
    public record Entry(Rule rule, Selector selector) {
    }

    private final String id;
    private final UiStyleSheet source;
    private final List<Rule> rules;
    private final Map<String, List<Entry>> byName = new HashMap<>();
    private final Map<String, List<Entry>> byClass = new HashMap<>();
    private final Map<String, List<Entry>> byType = new HashMap<>();
    private final List<Entry> universal = new ArrayList<>();

    /** @param id the reference the document used ({@code pause} or a dependency id) */
    public CompiledSheet(String id, UiStyleSheet source, List<Rule> rules) {
        this.id = Objects.requireNonNull(id, "id");
        this.source = Objects.requireNonNull(source, "source");
        this.rules = List.copyOf(rules);
        for (Rule r : this.rules) {
            for (Selector s : r.selectors()) {
                Selector.Compound subject = s.subject();
                Entry e = new Entry(r, s);
                if (!subject.names().isEmpty()) {
                    byName.computeIfAbsent(subject.names().getFirst(), k -> new ArrayList<>()).add(e);
                } else if (!subject.classes().isEmpty()) {
                    byClass.computeIfAbsent(subject.classes().getFirst(), k -> new ArrayList<>()).add(e);
                } else if (subject.type() != null) {
                    byType.computeIfAbsent(subject.type(), k -> new ArrayList<>()).add(e);
                } else {
                    universal.add(e);
                }
            }
        }
    }

    public String id() {
        return id;
    }

    public UiStyleSheet source() {
        return source;
    }

    public List<Rule> rules() {
        return rules;
    }

    /**
     * Feeds {@code sink} every selector that could match {@code element}: those filed under its
     * name, any of its classes, its type, and the universal ones. A rule may arrive once per
     * selector; the caller keeps the best specificity. Elements that cannot list their classes
     * ({@link Styleable#styleClasses()} null) get every selector.
     */
    public void candidates(Styleable element, Consumer<Entry> sink) {
        Iterable<String> classes = element.styleClasses();
        if (classes == null) {
            for (Rule r : rules) {
                for (Selector s : r.selectors()) {
                    sink.accept(new Entry(r, s));
                }
            }
            return;
        }
        String name = element.styleName();
        if (name != null) {
            feed(byName.get(name), sink);
        }
        if (!byClass.isEmpty()) {
            for (String c : classes) {
                feed(byClass.get(c), sink);
            }
        }
        String type = element.styleType();
        if (type != null) {
            feed(byType.get(type), sink);
        }
        feed(universal, sink);
    }

    private static void feed(List<Entry> entries, Consumer<Entry> sink) {
        if (entries != null) {
            for (int i = 0; i < entries.size(); i++) {
                sink.accept(entries.get(i));
            }
        }
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

    @Override
    public boolean equals(Object o) {
        return o instanceof CompiledSheet c && id.equals(c.id) && source.equals(c.source) && rules.equals(c.rules);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, source, rules);
    }

    @Override
    public String toString() {
        return "CompiledSheet[" + id + ", " + rules.size() + " rules]";
    }
}
