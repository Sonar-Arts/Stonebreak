package com.stonebreak.ui.terrainMapper.visualization.impl;

import com.stonebreak.ui.terrainMapper.visualization.NoiseVisualizer;
import com.stonebreak.world.generation.biomes.BiomeManager;
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

    private static Map<BiomeType, Integer> buildColorTable() {
        EnumMap<BiomeType, Integer> map = new EnumMap<>(BiomeType.class);
        map.put(BiomeType.PLAINS,          0xFF8DB600);
        map.put(BiomeType.DESERT,          0xFFEDC9AF);
        map.put(BiomeType.RED_SAND_DESERT, 0xFFCE6E3A);
        map.put(BiomeType.SNOWY_PLAINS,    0xFFEAF3F7);
        map.put(BiomeType.TUNDRA,          0xFFB5C4C4);
        map.put(BiomeType.TAIGA,           0xFF2F5339);
        map.put(BiomeType.STONY_PEAKS,     0xFF8A8A8A);
        map.put(BiomeType.BEACH,           0xFFE9DDB6);
        map.put(BiomeType.ICE_FIELDS,      0xFFC7E4EC);
        map.put(BiomeType.BADLANDS,        0xFFB85A2E);
        map.put(BiomeType.MEADOW,          0xFF9DC46B);
        return map;
    }
}
