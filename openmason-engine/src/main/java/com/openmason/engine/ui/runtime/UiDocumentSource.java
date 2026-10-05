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

    UiDocumentSource EMPTY = of(Map.of(), Map.of());

    static UiDocumentSource of(Map<String, OmuiArchive> components, Map<String, UiStyleSheet> sheets) {
        Map<String, OmuiArchive> c = Map.copyOf(components);
        Map<String, UiStyleSheet> s = Map.copyOf(sheets);
        return new UiDocumentSource() {
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
