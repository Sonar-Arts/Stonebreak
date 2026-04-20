package com.stonebreak.world.generation.biomes;

import java.util.List;

/**
 * Classifies biomes using a Whittaker diagram lookup based on temperature and moisture.
 *
 * The table maps [temperature_row][moisture_col] -> BiomeType. Rows and columns are
 * both indexed from cold/dry (0) to hot/wet (5). Temperature and moisture inputs are
 * continuous values in [0, 1].
 *
 * Replaces hard-coded if/else biome selection with a 2D lookup, making biome
 * distribution ecologically meaningful and easy to retune.
 */
public final class BiomeClassifier {

    private static final int TEMP_DIVISIONS = 6;
    private static final int MOISTURE_DIVISIONS = 6;

    /**
     * Whittaker table: [temperature][moisture]. Static so every BiomeManager
     * shares the same table without re-allocating on each construction.
     *
     *            Dry →                                                          → Wet
     *            col0        col1         col2         col3         col4         col5
     * row5 Hot   BADLANDS    DESERT       RED_SAND     RED_SAND     RED_SAND     PLAINS
     * row4       BADLANDS    DESERT       RED_SAND     RED_SAND     PLAINS       PLAINS
     * row3       DESERT      DESERT       PLAINS       PLAINS       PLAINS       PLAINS
     * row2       STONY_PEAKS PLAINS       PLAINS       MEADOW       PLAINS       PLAINS
     * row1       TUNDRA      TUNDRA       TAIGA        SNOWY_PLAINS SNOWY_PLAINS ICE_FIELDS
     * row0 Cold  TUNDRA      TUNDRA       TUNDRA       ICE_FIELDS   ICE_FIELDS   ICE_FIELDS
     */
    private static final BiomeType[][] WHITTAKER = {
        { BiomeType.TUNDRA,          BiomeType.TUNDRA,       BiomeType.TUNDRA,          BiomeType.ICE_FIELDS,      BiomeType.ICE_FIELDS,      BiomeType.ICE_FIELDS },
        { BiomeType.TUNDRA,          BiomeType.TUNDRA,       BiomeType.TAIGA,           BiomeType.SNOWY_PLAINS,    BiomeType.SNOWY_PLAINS,    BiomeType.ICE_FIELDS },
        { BiomeType.STONY_PEAKS,     BiomeType.PLAINS,       BiomeType.PLAINS,          BiomeType.MEADOW,          BiomeType.PLAINS,          BiomeType.PLAINS },
        { BiomeType.DESERT,          BiomeType.DESERT,       BiomeType.PLAINS,          BiomeType.PLAINS,          BiomeType.PLAINS,          BiomeType.PLAINS },
        { BiomeType.BADLANDS,        BiomeType.DESERT,       BiomeType.RED_SAND_DESERT, BiomeType.RED_SAND_DESERT, BiomeType.PLAINS,          BiomeType.PLAINS },
        { BiomeType.BADLANDS,        BiomeType.DESERT,       BiomeType.RED_SAND_DESERT, BiomeType.RED_SAND_DESERT, BiomeType.RED_SAND_DESERT, BiomeType.PLAINS }
    };

    /**
     * Classifies a biome based on temperature and moisture in [0, 1].
     *
     * @param temperature 0 = coldest, 1 = hottest
     * @param moisture    0 = driest, 1 = wettest
     */
    public BiomeType classify(float temperature, float moisture) {
        int tempIndex = Math.min(TEMP_DIVISIONS - 1,
                Math.max(0, (int) (temperature * TEMP_DIVISIONS)));
        int moistureIndex = Math.min(MOISTURE_DIVISIONS - 1,
                Math.max(0, (int) (moisture * MOISTURE_DIVISIONS)));
        return WHITTAKER[tempIndex][moistureIndex];
    }

    /**
     * Whittaker classification constrained to {@code allowed}. If the natural
     * classification is disallowed, falls back to the nearest allowed cell in
     * (temperature, moisture) grid space using Chebyshev distance.
     */
    public BiomeType classifyWithFilter(float temperature, float moisture, List<BiomeType> allowed) {
        BiomeType natural = classify(temperature, moisture);
        if (allowed.contains(natural)) {
            return natural;
        }

        int centerT = Math.min(TEMP_DIVISIONS - 1, Math.max(0, (int) (temperature * TEMP_DIVISIONS)));
        int centerM = Math.min(MOISTURE_DIVISIONS - 1, Math.max(0, (int) (moisture * MOISTURE_DIVISIONS)));
        int maxR = Math.max(TEMP_DIVISIONS, MOISTURE_DIVISIONS);
        for (int r = 1; r < maxR; r++) {
            for (int dt = -r; dt <= r; dt++) {
                for (int dm = -r; dm <= r; dm++) {
                    if (Math.abs(dt) != r && Math.abs(dm) != r) continue;
                    int t = centerT + dt;
                    int m = centerM + dm;
                    if (t < 0 || t >= TEMP_DIVISIONS || m < 0 || m >= MOISTURE_DIVISIONS) continue;
                    BiomeType candidate = WHITTAKER[t][m];
                    if (allowed.contains(candidate)) {
                        return candidate;
                    }
                }
            }
        }
        return allowed.get(0);
    }
}
