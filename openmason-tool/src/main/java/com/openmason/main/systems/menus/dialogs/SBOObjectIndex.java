package com.openmason.main.systems.menus.dialogs;

import com.openmason.main.systems.assets.AssetCatalog;
import com.openmason.main.systems.assets.AssetEntry;

import java.nio.file.Path;
import java.util.List;

/**
 * SBO view over the shared {@link AssetCatalog}. Kept as a thin façade so the
 * recipe ingredient picker (and any other "pick an object" UI) keeps its
 * historical shape while scanning/caching lives in one place.
 */
public final class SBOObjectIndex {

    private SBOObjectIndex() {}

    public record Entry(String objectId, String displayName, String objectType, Path sourcePath) {
        public boolean isBlock() { return "block".equalsIgnoreCase(objectType); }
        public boolean isItem()  { return "item".equalsIgnoreCase(objectType); }
    }

    /** Look up an entry by exact objectId, or null when unknown. */
    public static Entry find(String objectId) {
        if (objectId == null || objectId.isEmpty()) return null;
        for (Entry e : listAll()) {
            if (e.objectId().equals(objectId)) return e;
        }
        return null;
    }

    public static List<Entry> listAll() {
        return AssetCatalog.shared().listAll(false).stream()
                .filter(e -> e.kind() == AssetEntry.Kind.SBO)
                .map(e -> new Entry(e.id(), e.displayName(), e.type(), e.sourcePath()))
                .toList();
    }

    public static void refresh() {
        AssetCatalog.shared().listAll(true);
    }
}
