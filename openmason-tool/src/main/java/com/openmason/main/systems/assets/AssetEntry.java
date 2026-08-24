package com.openmason.main.systems.assets;

import java.nio.file.Path;

/**
 * One SBO/SBE asset discovered in the game resource tree.
 *
 * @param id          manifest objectId (the primary address for asset_* tools)
 * @param displayName manifest objectName
 * @param kind        container format
 * @param type        SBO objectType (block/item/…) or SBE entityType (mob/…), lowercased
 * @param sourcePath  absolute file path (read-only; may point into a temp
 *                    classpath mirror when no dev tree exists)
 * @param folder      resource-relative folder (e.g. {@code sbo/blocks})
 */
public record AssetEntry(String id, String displayName, Kind kind, String type,
                         Path sourcePath, String folder, long sizeBytes, long modifiedMillis) {

    /** Container format of an asset. */
    public enum Kind {
        SBO, SBE;

        public String lower() {
            return name().toLowerCase();
        }
    }
}
