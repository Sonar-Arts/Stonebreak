package com.stonebreak.world.generation;


/**
 * Which terrain generator a world uses. Chosen once, in the terrain mapper, and persisted
 * with the world (see {@code WorldData#getGeneratorType}).
 */
public enum TerrainGeneratorType {

    /** The noise generator: local, instant, the default. */
    STANDARD("Standard Generation"),

    /**
     * Experimental: terrain from DaedalusTGM-Exp, served by TGMPipe
     * ({@code Models/}). Building one starts TGMPipe if it is not already running. The constant keeps Heracles' name: it is what world.json
     * and the handshake carry.
     */
    DIFFUSION("DaedalusTGM-Exp");

    private final String displayName;

    TerrainGeneratorType(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** The native terrain of a world of this kind at the default 1:4 scale. */
    public NativeTerrain create(long seed) {
        return NativeTerrain.create(seed, this, NativeCubeGenerator.TerrainScalePreset.ONE_TO_FOUR, null);
    }

    /** The native terrain of a world of this kind. */
    public NativeTerrain create(long seed, NativeCubeGenerator.TerrainScalePreset scale,
                                com.stonebreak.world.voxel.BlockStateRegistry states) {
        return NativeTerrain.create(seed, this, scale, states);
    }

    /** The native terrain of a world of this kind with the climate {@code layout} (DIFFUSION worlds only). */
    public NativeTerrain create(long seed, NativeCubeGenerator.TerrainScalePreset scale, ClimateLayout layout,
                                com.stonebreak.world.voxel.BlockStateRegistry states) {
        return NativeTerrain.create(seed, this, scale, layout, states);
    }

    /** Parses a persisted or networked name; anything unrecognised falls back to {@code fallback}. */
    public static TerrainGeneratorType parse(String name, TerrainGeneratorType fallback) {
        if (name != null) {
            for (TerrainGeneratorType type : values()) {
                if (type.name().equalsIgnoreCase(name.trim())) {
                    return type;
                }
            }
        }
        return fallback;
    }
}
