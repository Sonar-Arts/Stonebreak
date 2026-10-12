package com.openmason.engine.ui.assets.live;

import com.openmason.engine.format.omui.UiValue;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reverse dependencies between open documents and the shared assets they use, plus which
 * project paths each asset id lives (or would live) at. Keys are logical ids, so a component
 * is both a dependency of the screens that instance it and a document with dependencies of
 * its own: saving a texture reaches the component and, through it, every screen. Thread-safe.
 */
public final class DependencyIndex {

    private final Map<String, Set<String>> forward = new HashMap<>();
    private final Map<String, Set<String>> reverse = new HashMap<>();
    /** document → project path → asset ids recorded there. */
    private final Map<String, Map<String, Set<String>>> locations = new HashMap<>();

    /** Replaces what {@code document} depends on. */
    public synchronized void put(String document, Collection<String> dependencyIds) {
        remove(document);
        Set<String> deps = new HashSet<>(dependencyIds);
        forward.put(document, deps);
        for (String dep : deps) {
            reverse.computeIfAbsent(dep, k -> new HashSet<>()).add(document);
        }
    }

    /** Records that {@code assetId} resolves (or would resolve) at project path {@code path}. */
    public synchronized void location(String document, String assetId, String path) {
        locations.computeIfAbsent(document, k -> new HashMap<>()).computeIfAbsent(path, k -> new HashSet<>()).add(assetId);
    }

    public synchronized void remove(String document) {
        Set<String> deps = forward.remove(document);
        if (deps != null) {
            for (String dep : deps) {
                Set<String> users = reverse.get(dep);
                if (users != null && users.remove(document) && users.isEmpty()) {
                    reverse.remove(dep);
                }
            }
        }
        locations.remove(document);
    }

    /** Every document that depends on {@code assetId}, directly or through components, sorted. */
    public synchronized Set<String> dependents(String assetId) {
        Set<String> out = new TreeSet<>(UiValue.KEY_ORDER);
        Deque<String> todo = new ArrayDeque<>();
        todo.add(assetId);
        while (!todo.isEmpty()) {
            for (String user : reverse.getOrDefault(todo.poll(), Set.of())) {
                if (out.add(user)) {
                    todo.add(user);
                }
            }
        }
        return out;
    }

    /** Asset ids recorded at project path {@code path}. */
    public synchronized Set<String> idsAt(String path) {
        Set<String> out = new HashSet<>();
        for (Map<String, Set<String>> byPath : locations.values()) {
            out.addAll(byPath.getOrDefault(path, Set.of()));
        }
        return out;
    }
}
