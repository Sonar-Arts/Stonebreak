package com.openmason.engine.format.omui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Canonical-form helpers used by record constructors, so two records that mean the same
 * thing are {@code equals} and encode to the same bytes regardless of how they were built.
 */
final class Canon {

    private Canon() {
    }

    static List<String> sortedUnique(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        TreeSet<String> set = new TreeSet<>(UiValue.KEY_ORDER);
        set.addAll(values);
        return List.copyOf(set);
    }

    static <V> Map<String, V> sortedMap(Map<String, V> map) {
        if (map == null || map.isEmpty()) {
            return Map.of();
        }
        TreeMap<String, V> sorted = new TreeMap<>(UiValue.KEY_ORDER);
        sorted.putAll(map);
        return Collections.unmodifiableMap(sorted);
    }

    static <T> List<T> sortedBy(List<T> values, Function<T, String> key) {
        return sortedBy(values, Comparator.comparing(key, UiValue.KEY_ORDER));
    }

    static <T> List<T> sortedBy(List<T> values, Comparator<T> order) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<T> copy = new ArrayList<>(values);
        copy.sort(order);
        return List.copyOf(copy);
    }

    static <T> List<T> list(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    /** A free-form value map, key-sorted at every depth. */
    static Map<String, UiValue> values(Map<String, UiValue> map) {
        if (map == null || map.isEmpty()) {
            return Map.of();
        }
        return UiValue.Obj.sorted(map).fields();
    }

    static UiValue value(UiValue v) {
        return v == null ? null : UiValue.canonical(v);
    }

    static Map<String, UiValue> unknown(Map<String, UiValue> unknown) {
        return values(unknown);
    }

    /** Folds -0.0 to 0.0 so record equality matches the canonical bytes. */
    static double num(double v) {
        return v + 0.0;
    }
}
