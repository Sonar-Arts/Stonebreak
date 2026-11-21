package com.stonebreak.ui.terrainMapper.visualization.impl.diffusion;

import com.stonebreak.ui.terrainMapper.visualization.NoiseVisualizer;
import com.stonebreak.ui.terrainMapper.visualization.PreviewChannel;
import com.stonebreak.world.generation.diffusion.biomes.BiomeManager;
import com.stonebreak.world.generation.biomes.BiomeType;

import java.util.EnumMap;
import java.util.Map;

/**
 * Categorical biome map. Sample value encodes the biome ordinal so the
 * renderer never needs to reach back into this class at draw time — color
 * and label resolution happen through the standard {@link NoiseVisualizer}
 * hooks and a cached ordinal->type table.
 */
public final class BiomeVisualizer implements NoiseVisualizer {

    private static final BiomeType[] BIOMES_BY_ORDINAL = BiomeType.values();
    private static final Map<BiomeType, Integer> COLORS = buildColorTable();

    private final BiomeManager biomes;

    public BiomeVisualizer(BiomeManager biomes) {
        this.biomes = biomes;
    }

    @Override public String displayName() { return "Biome"; }

    @Override
    public float sample(int worldX, int worldZ) {
        return biomes.getBiome(worldX, worldZ).ordinal();
    }

    @Override public PreviewChannel channel() { return PreviewChannel.BIOME; }

    @Override
    public float normalize(float raw) {
        // Pass-through so colorFor can recover the exact ordinal.
        return raw;
    }

    @Override
    public int colorFor(float ordinal) {
        BiomeType biome = biomeAt(ordinal);
        return COLORS.getOrDefault(biome, 0xFFFF00FF);
    }

    @Override
    public String formatValue(float ordinal) {
        return biomeAt(ordinal).name();
    }

    private static BiomeType biomeAt(float ordinal) {
        int i = Math.max(0, Math.min(BIOMES_BY_ORDINAL.length - 1, Math.round(ordinal)));
        return BIOMES_BY_ORDINAL[i];
    }

    /** One palette for both generators' maps (Standard's, which carries every biome: swamp and the Shade too). */
    private static Map<BiomeType, Integer> buildColorTable() {
        EnumMap<BiomeType, Integer> map = new EnumMap<>(BiomeType.class);
        for (BiomeType biome : BiomeType.values()) {
            map.put(biome, com.stonebreak.ui.terrainMapper.visualization.impl.BiomeVisualizer.colorOf(biome));
        }
        return map;
    }
}
