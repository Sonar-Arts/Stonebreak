package com.openmason.main.systems.menus.dialogs;


import com.openmason.main.systems.assets.AssetCatalog;
import com.openmason.main.systems.assets.AssetEntry;

import java.nio.file.Path;
import java.util.List;

/**
 * SBE view over the shared {@link AssetCatalog} — enumerates the
 * {@code objectId}s already in use (every {@code sbe/} sub-folder, not just
 * Mobs) so authoring UIs can show them and help avoid collisions.
 */
public final class SBEObjectIndex {

    private SBEObjectIndex() {}

    /** One registered SBE entity. */
    public record Entry(String objectId, String objectName, String entityType, Path sourcePath) {}

    public static List<Entry> listAll() {
        return AssetCatalog.shared().listAll(false).stream()
                .filter(e -> e.kind() == AssetEntry.Kind.SBE)
                .map(e -> new Entry(e.id(), e.displayName(), e.type(), e.sourcePath()))
                .toList();
    }

    public static void refresh() {
        AssetCatalog.shared().listAll(true);
    }
}
