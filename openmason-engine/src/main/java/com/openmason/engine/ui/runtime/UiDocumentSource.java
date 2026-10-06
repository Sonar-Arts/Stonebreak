package com.openmason.engine.ui.runtime;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiStyleSheet;

import java.util.Map;

/**
 * Where a runtime finds the dependencies a document references by id: component documents
 * and shared style sheets. The game backs it with the SBUI dependency table and mounted
 * packs (#285 {@code AssetResolver}); the editor with the project; tests with maps.
 * In-archive parts (a document's own {@code styles/}) never go through here.
 */
public interface UiDocumentSource {

    /** The component archive for a dependency id, or {@code null} when it cannot be found. */
    OmuiArchive component(String dependencyId);

    /** A shared style sheet (dependency kind {@code stylesheet}), or {@code null}. */
    UiStyleSheet styleSheet(String dependencyId);

    /**
     * Source text of a shared Lua module (dependency kind {@code script}, #292), or {@code null}
     * when it cannot be found. Binary chunks are never returned.
     */
    default String script(String dependencyId) {
        return null;
    }

    /**
     * Generated Lua of a behavior graph shipped in an SBUI {@code derived/} cache (#291), or
     * null. The runtime uses it only while its compiler version and source hash still match
     * the graph; otherwise it compiles the graph itself.
     */
    default DerivedLua derivedGraph(String documentId, String graphId) {
        return null;
    }

    /** A derived graph chunk and the keys it was built for. */
    record DerivedLua(String lua, String sourceSha256, String compiler, String compilerVersion) {
    }

    UiDocumentSource EMPTY = of(Map.of(), Map.of());

    static UiDocumentSource of(Map<String, OmuiArchive> components, Map<String, UiStyleSheet> sheets) {
        return of(components, sheets, Map.of());
    }

    static UiDocumentSource of(Map<String, OmuiArchive> components, Map<String, UiStyleSheet> sheets,
                               Map<String, String> scripts) {
        Map<String, OmuiArchive> c = Map.copyOf(components);
        Map<String, UiStyleSheet> s = Map.copyOf(sheets);
        Map<String, String> l = Map.copyOf(scripts);
        return new UiDocumentSource() {
            @Override
            public String script(String dependencyId) {
                return l.get(dependencyId);
            }

            @Override
            public OmuiArchive component(String dependencyId) {
                return c.get(dependencyId);
            }

            @Override
            public UiStyleSheet styleSheet(String dependencyId) {
                return s.get(dependencyId);
            }
        };
    }
}
