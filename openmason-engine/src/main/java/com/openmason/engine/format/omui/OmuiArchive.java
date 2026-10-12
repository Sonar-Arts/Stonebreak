package com.openmason.engine.format.omui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * A whole {@code .omui} document as decoded data: the authoritative, editable source. Two
 * archives are {@code equals} exactly when they are semantically the same document, which is
 * what the round-trip tests compare.
 *
 * @param styles       style sheets by id ({@code styles/<id>.uss.json})
 * @param graphs       behavior graphs by id ({@code graphs/<id>.graph.json})
 * @param animations   clips by id ({@code animations/<id>.anim.json})
 * @param stateMachines UI state machines by id ({@code animations/<id>.states.json}, feature
 *                     {@code ui-states}, #295)
 * @param scripts      embedded Lua source by id ({@code scripts/<id>.lua}); never executed by
 *                     the format layer
 * @param dependencies the dependency table ({@code dependencies.json})
 * @param assets       embedded asset bytes by entry name ({@code assets/...})
 * @param editor       editor-only entries by entry name ({@code editor/...}), carried verbatim
 *                     and ignored by runtimes
 * @param extraEntries entries this reader does not recognize, carried verbatim
 */
public record OmuiArchive(UiManifest manifest, UiDocument document, Map<String, UiStyleSheet> styles,
                          Map<String, UiGraph> graphs, Map<String, UiAnimationClip> animations,
                          Map<String, UiStateMachine> stateMachines, Map<String, String> scripts, UiDependencies dependencies, Map<String, UiBytes> assets,
                          Map<String, UiBytes> editor, Map<String, UiBytes> extraEntries) {

    /** {@code dependencies.json}: rows sorted by id, plus preserved unknown root fields. */
    public record UiDependencies(List<UiDependency> entries, Map<String, UiValue> unknown) {
        public static final UiDependencies EMPTY = new UiDependencies(List.of(), Map.of());

        public UiDependencies {
            entries = Canon.sortedBy(entries, UiDependency::id);
            unknown = Canon.unknown(unknown);
        }

        public UiDependency find(String id) {
            for (UiDependency d : entries) {
                if (d.id().equals(id)) {
                    return d;
                }
            }
            return null;
        }
    }

    public OmuiArchive {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(document, "document");
        styles = keyed(styles, UiStyleSheet::id);
        graphs = keyed(graphs, UiGraph::id);
        animations = keyed(animations, UiAnimationClip::id);
        stateMachines = keyed(stateMachines, UiStateMachine::id);
        scripts = Canon.sortedMap(scripts);
        dependencies = dependencies == null ? UiDependencies.EMPTY : dependencies;
        assets = prefixed(assets, OmuiFormat.ASSETS_DIR);
        editor = prefixed(editor, OmuiFormat.EDITOR_DIR);
        extraEntries = Canon.sortedMap(extraEntries);
    }

    /** A document with only a manifest and a tree. */
    public static OmuiArchive of(UiManifest manifest, UiDocument document) {
        return new OmuiArchive(manifest, document, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), UiDependencies.EMPTY,
                Map.of(), Map.of(), Map.of());
    }

    public OmuiArchive withManifest(UiManifest m) {
        return new OmuiArchive(m, document, styles, graphs, animations, stateMachines, scripts, dependencies, assets, editor,
                extraEntries);
    }

    public OmuiArchive withDocument(UiDocument d) {
        return new OmuiArchive(manifest, d, styles, graphs, animations, stateMachines, scripts, dependencies, assets, editor,
                extraEntries);
    }

    public OmuiArchive withStyle(UiStyleSheet sheet) {
        return new OmuiArchive(manifest, document, put(styles, sheet.id(), sheet), graphs, animations, stateMachines, scripts,
                dependencies, assets, editor, extraEntries);
    }

    public OmuiArchive withGraph(UiGraph graph) {
        return new OmuiArchive(manifest, document, styles, put(graphs, graph.id(), graph), animations, stateMachines, scripts,
                dependencies, assets, editor, extraEntries);
    }

    public OmuiArchive withAnimation(UiAnimationClip clip) {
        return new OmuiArchive(manifest, document, styles, graphs, put(animations, clip.id(), clip), stateMachines, scripts,
                dependencies, assets, editor, extraEntries);
    }

    public OmuiArchive withStateMachine(UiStateMachine machine) {
        return new OmuiArchive(manifest, document, styles, graphs, animations,
                put(stateMachines, machine.id(), machine), scripts, dependencies, assets, editor, extraEntries);
    }

    /** Without the clip {@code id} (unchanged when absent). */
    public OmuiArchive withoutAnimation(String id) {
        Map<String, UiAnimationClip> copy = new LinkedHashMap<>(animations);
        copy.remove(id);
        return new OmuiArchive(manifest, document, styles, graphs, copy, stateMachines, scripts, dependencies, assets,
                editor, extraEntries);
    }

    /** Without the state machine {@code id} (unchanged when absent). */
    public OmuiArchive withoutStateMachine(String id) {
        Map<String, UiStateMachine> copy = new LinkedHashMap<>(stateMachines);
        copy.remove(id);
        return new OmuiArchive(manifest, document, styles, graphs, animations, copy, scripts, dependencies, assets,
                editor, extraEntries);
    }

    public OmuiArchive withScript(String id, String source) {
        return new OmuiArchive(manifest, document, styles, graphs, animations, stateMachines, put(scripts, id, source),
                dependencies, assets, editor, extraEntries);
    }

    public OmuiArchive withDependencies(UiDependencies deps) {
        return new OmuiArchive(manifest, document, styles, graphs, animations, stateMachines, scripts, deps, assets, editor,
                extraEntries);
    }

    public OmuiArchive withAsset(String entry, UiBytes bytes) {
        return new OmuiArchive(manifest, document, styles, graphs, animations, stateMachines, scripts, dependencies,
                put(assets, entry, bytes), editor, extraEntries);
    }

    public OmuiArchive withEditorEntry(String entry, UiBytes bytes) {
        return new OmuiArchive(manifest, document, styles, graphs, animations, stateMachines, scripts, dependencies, assets,
                put(editor, entry, bytes), extraEntries);
    }

    private static <V> Map<String, V> put(Map<String, V> map, String key, V value) {
        Map<String, V> copy = new LinkedHashMap<>(map);
        copy.put(key, value);
        return copy;
    }

    private static <V> Map<String, V> keyed(Map<String, V> map, Function<V, String> id) {
        if (map != null) {
            map.forEach((k, v) -> {
                if (!k.equals(id.apply(v))) {
                    throw new IllegalArgumentException("Key '" + k + "' does not match id '" + id.apply(v) + "'");
                }
            });
        }
        return Canon.sortedMap(map);
    }

    private static <V> Map<String, V> prefixed(Map<String, V> map, String prefix) {
        if (map != null) {
            for (String k : map.keySet()) {
                if (!k.startsWith(prefix)) {
                    throw new IllegalArgumentException("Entry '" + k + "' must live under " + prefix);
                }
            }
        }
        return Canon.sortedMap(map);
    }
}
