package com.stonebreak.world.generation;

import com.stonebreak.world.generation.diffusion.DiffusionTerrainGenerator;

/**
 * Which terrain generator a world uses. Chosen once, in the terrain mapper, and persisted
 * with the world (see {@code WorldData#getGeneratorType}).
 */
public enum TerrainGeneratorType {

    /** The noise generator: local, instant, the default. */
    STANDARD("Standard Generation") {
        @Override
        public TerrainGenerator create(long seed) {
            return new TerrainGenerationSystem(seed);
        }
    },

    /**
     * Experimental: terrain from the diffusion model. Building one starts the model server
     * and bridge, which can take minutes on a cold machine.
     */
    DIFFUSION("Diffusion Generation") {
        @Override
        public TerrainGenerator create(long seed) {
            return new DiffusionTerrainGenerator(seed);
        }
    };

    private final String displayName;

    TerrainGeneratorType(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public abstract TerrainGenerator create(long seed);

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
