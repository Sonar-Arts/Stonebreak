package com.stonebreak.world.generation.diffusion;

/**
 * The named per-block surface channels a TGMPipe v3 tile can carry (contract: Python
 * {@code terrain_slm/world/surface_channels.py}; native {@code SBW_SURF_*} / {@code sbw_column_profile.surface}).
 * One byte per block column: {@code physical = offset + byte * scale}, {@link #NO_DATA} (255) = no data, in which case
 * every consumer behaves as it did before the channel existed. The wire carries channels by NAME; names this enum does
 * not know are ignored, so the service can add channels before the game reads them.
 *
 * <p>{@link #slot()} is the channel's fixed index in the native column profile's surface block (16 slots).
 * Adding a channel = a constant here with the slot of the Python {@code Channel} and the native {@code SBW_SURF_*}.
 */
public enum SurfaceChannel {
    // vegetation node (ETH canopy height 2020 + ESA WorldCover 2021)
    CANOPY_COVER("canopy_cover", 0),
    CANOPY_HEIGHT("canopy_height", 1),
    CLEARING("clearing", 2),
    GROUND_GRASS("ground_grass", 3),
    GROUND_SHRUB("ground_shrub", 4),
    GROUND_MOSS("ground_moss", 5),
    GROUND_BARE("ground_bare", 6),
    GROUND_WETLAND("ground_wetland", 7),
    // land surface materials + boulders
    ROCK_EXPOSURE("rock_exposure", 8),
    SCREE("scree", 9),
    BOULDER_DENSITY("boulder_density", 10),
    BOULDER_SIZE("boulder_size", 11),
    SURFACE_ROUGHNESS("surface_roughness", 12);

    /** Byte value meaning "no data for this column". */
    public static final int NO_DATA = 255;
    /** Slots in the native column profile's surface block ({@code SBW_SURFACE_SLOTS}). */
    public static final int SLOTS = 16;

    private static final SurfaceChannel[] VALUES = values();

    private final String wireName;
    private final int slot;

    SurfaceChannel(String wireName, int slot) {
        this.wireName = wireName;
        this.slot = slot;
    }

    public String wireName() {
        return wireName;
    }

    public int slot() {
        return slot;
    }

    /** The channel with this wire name, or null (unknown channels are ignored). */
    public static SurfaceChannel byWireName(String name) {
        for (SurfaceChannel c : VALUES) {
            if (c.wireName.equals(name)) {
                return c;
            }
        }
        return null;
    }
}
