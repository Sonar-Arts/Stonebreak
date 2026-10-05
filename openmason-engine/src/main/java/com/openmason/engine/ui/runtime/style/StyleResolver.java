package com.openmason.engine.ui.runtime.style;

import com.openmason.engine.format.omui.UiStyleProperties;
import com.openmason.engine.format.omui.UiStyleSheet;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The cascade (#287). For one element, in increasing precedence:
 *
 * <ol>
 *   <li>matched sheet rules, ordered by sheet rank (theme → component → document), then
 *       specificity, then sheet order, then rule order — interaction states are ordinary
 *       pseudo-class selectors inside this step;</li>
 *   <li>the element's higher layers, lowest first: inline style, instance overrides (inner
 *       instance before outer), binding values, local (script) writes, active animation
 *       channels — the caller supplies them in that order.</li>
 * </ol>
 *
 * Then {@code var()} references resolve against custom properties: inherited from the parent,
 * overlaid with the variables of sheets attached at this element, overlaid with the element's
 * own {@code --name} declarations. Inherited properties ({@link StyleValues#INHERITED}) take
 * the parent's value when undeclared. Values that do not fit their property are reported and
 * dropped, never guessed.
 */
public final class StyleResolver {

    private record Match(SheetBinding binding, CompiledSheet.Rule rule, int specificity) {
    }

    private static final Comparator<Match> PRECEDENCE = Comparator
        .comparingInt((Match m) -> m.binding.rank())
        .thenComparingInt(Match::specificity)
        .thenComparingInt(m -> m.binding.order())
        .thenComparingInt(m -> m.rule.index());

    private StyleResolver() {
    }

    /**
     * @param sheets      every sheet binding of the tree (out-of-scope ones are skipped)
     * @param layers      the element's non-sheet layers, lowest precedence first
     * @param parent      the parent's computed style ({@link ComputedStyle#INITIAL} for the root)
     * @param elementKey  for diagnostics
     */
    public static ComputedStyle compute(Styleable element, List<SheetBinding> sheets, List<Map<String, UiValue>> layers,
                                        ComputedStyle parent, String elementKey,
                                        Consumer<UiRuntimeDiagnostic> diagnostics) {
        List<Match> matches = new ArrayList<>();
        List<SheetBinding> attachedHere = new ArrayList<>();
        for (SheetBinding b : sheets) {
            if (b.scope() == element || (b.scope() == null && element.styleParent() == null)) {
                attachedHere.add(b);
            }
            if (!b.applies(element)) {
                continue;
            }
            for (CompiledSheet.Rule rule : b.sheet().rules()) {
                int specificity = rule.match(element);
                if (specificity >= 0) {
                    matches.add(new Match(b, rule, specificity));
                }
            }
        }
        matches.sort(PRECEDENCE);

        Map<String, UiValue> declared = new LinkedHashMap<>();
        Map<String, UiStyleSheet.StyleTransition> transitions = new HashMap<>();
        for (Match m : matches) {
            declared.putAll(m.rule.style());
            for (UiStyleSheet.StyleTransition t : m.rule.transitions()) {
                transitions.put(t.property(), t);
            }
        }
        for (Map<String, UiValue> layer : layers) {
            declared.putAll(layer);
        }

        Map<String, UiValue> rawCustoms = new LinkedHashMap<>(parent.customs());
        attachedHere.sort(Comparator.comparingInt(SheetBinding::rank).thenComparingInt(SheetBinding::order));
        for (SheetBinding b : attachedHere) {
            rawCustoms.putAll(b.sheet().source().variables());
        }
        declared.forEach((k, v) -> {
            if (UiStyleProperties.isCustom(k)) {
                rawCustoms.put(k, v);
            }
        });
        Map<String, UiValue> customs = new LinkedHashMap<>();
        for (String name : rawCustoms.keySet()) {
            UiValue v = resolveCustom(name, rawCustoms, new HashSet<>(), elementKey, diagnostics);
            if (v != null) {
                customs.put(name, v);
            }
        }

        Map<String, UiValue> values = new HashMap<>();
        declared.forEach((property, raw) -> {
            if (UiStyleProperties.isCustom(property)) {
                return;
            }
            UiValue v = raw;
            if (StyleValues.isVar(raw)) {
                v = customs.get(StyleValues.varName(raw));
                if (v == null) {
                    diagnostics.accept(UiRuntimeDiagnostic.warning(UiRuntimeDiagnostic.Code.UNRESOLVED_VARIABLE,
                        elementKey, property + ": " + ((UiValue.Str) raw).value() + " is not defined"));
                    return;
                }
            }
            String problem = UiStyleProperties.problem(property, v);
            if (problem != null || StyleValues.isVar(v)) {
                diagnostics.accept(UiRuntimeDiagnostic.warning(UiRuntimeDiagnostic.Code.STYLE_VALUE, elementKey,
                    property + ": " + (problem != null ? problem : "unresolved reference")));
                return;
            }
            values.put(property, v);
        });
        for (String property : StyleValues.INHERITED) {
            if (!values.containsKey(property) && parent.get(property) != null) {
                values.put(property, parent.get(property));
            }
        }
        return new ComputedStyle(values, customs, transitions);
    }

    private static UiValue resolveCustom(String name, Map<String, UiValue> raw, Set<String> visiting, String elementKey,
                                         Consumer<UiRuntimeDiagnostic> diagnostics) {
        UiValue v = raw.get(name);
        if (v == null || !StyleValues.isVar(v)) {
            return v;
        }
        if (!visiting.add(name)) {
            diagnostics.accept(UiRuntimeDiagnostic.warning(UiRuntimeDiagnostic.Code.VARIABLE_CYCLE, elementKey,
                "custom property " + name + " refers to itself through " + visiting));
            return null;
        }
        String target = StyleValues.varName(v);
        if (!raw.containsKey(target)) {
            diagnostics.accept(UiRuntimeDiagnostic.warning(UiRuntimeDiagnostic.Code.UNRESOLVED_VARIABLE, elementKey,
                name + ": var(" + target + ") is not defined"));
            return null;
        }
        return resolveCustom(target, raw, visiting, elementKey, diagnostics);
    }
}
