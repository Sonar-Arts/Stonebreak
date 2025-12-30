package com.stonebreak.world.structure;

/**
 * Enumeration of structure types that can be found in the world.
 *
 * <p>This enum is used by the {@link StructureFinder} service to delegate
 * to the appropriate {@link finders.StructureFinderStrategy} implementation.</p>
 *
 * <p>Currently supported:
 * <ul>
 *     <li>{@link #LAKE} - Elevated lakes and ponds (y >= 65)</li>
 * </ul>
 * </p>
 *
 * <p>Future structure types:
 * <ul>
 *     <li>VILLAGE - Settlements in plains and deserts</li>
 *     <li>TEMPLE - Ancient structures in various biomes</li>
 *     <li>STRONGHOLD - Underground fortress</li>
 *     <li>MINESHAFT - Abandoned mine tunnels</li>
 * </ul>
 * </p>
 */
public enum StructureType {
    /**
     * Elevated lake or pond generated via basin detection.
     * Requirements: terrain >= 65, humidity >= 0.3, basin depth >= 3
     */
    LAKE,

    /**
     * Village settlement (not yet implemented).
     */
    VILLAGE,

    /**
     * Ancient temple structure (not yet implemented).
     */
    TEMPLE
}
