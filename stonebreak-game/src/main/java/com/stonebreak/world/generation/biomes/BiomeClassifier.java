package com.stonebreak.world.generation.biomes;

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
     *            Dry →                                                → Wet
     *            col0       col1       col2       col3       col4       col5
     * row5 Hot   RED_SAND   DESERT     RED_SAND   RED_SAND   RED_SAND   PLAINS
     * row4       RED_SAND   DESERT     RED_SAND   RED_SAND   PLAINS     PLAINS
     * row3       DESERT     DESERT     PLAINS     PLAINS     PLAINS     PLAINS
     * row2       DESERT     DESERT     PLAINS     PLAINS     PLAINS     PLAINS
     * row1       PLAINS     PLAINS     PLAINS     SNOWY      SNOWY      SNOWY
     * row0 Cold  PLAINS     PLAINS     SNOWY      SNOWY      SNOWY      SNOWY
     */
    private static final BiomeType[][] WHITTAKER = {
        { BiomeType.PLAINS,          BiomeType.PLAINS,  BiomeType.SNOWY_PLAINS,   BiomeType.SNOWY_PLAINS,   BiomeType.SNOWY_PLAINS,   BiomeType.SNOWY_PLAINS },
        { BiomeType.PLAINS,          BiomeType.PLAINS,  BiomeType.PLAINS,         BiomeType.SNOWY_PLAINS,   BiomeType.SNOWY_PLAINS,   BiomeType.SNOWY_PLAINS },
        { BiomeType.DESERT,          BiomeType.DESERT,  BiomeType.PLAINS,         BiomeType.PLAINS,         BiomeType.PLAINS,         BiomeType.PLAINS },
        { BiomeType.DESERT,          BiomeType.DESERT,  BiomeType.PLAINS,         BiomeType.PLAINS,         BiomeType.PLAINS,         BiomeType.PLAINS },
        { BiomeType.RED_SAND_DESERT, BiomeType.DESERT,  BiomeType.RED_SAND_DESERT, BiomeType.RED_SAND_DESERT, BiomeType.PLAINS,        BiomeType.PLAINS },
        { BiomeType.RED_SAND_DESERT, BiomeType.DESERT,  BiomeType.RED_SAND_DESERT, BiomeType.RED_SAND_DESERT, BiomeType.RED_SAND_DESERT, BiomeType.PLAINS }
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
}
