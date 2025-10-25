package com.stonebreak.world.generation.biomes;

/**
 * Classifies biomes using a Whittaker diagram lookup table based on temperature and moisture.
 *
 * The Whittaker biome classification system uses two environmental factors:
 * - Temperature (Y-axis): Cold (0.0) to Hot (1.0)
 * - Moisture (X-axis): Dry (0.0) to Wet (1.0)
 *
 * This replaces the hard-coded if/else biome selection logic with a more maintainable
 * and extensible 2D lookup table. The table maps temperature/moisture ranges to specific
 * biome types, following ecological principles.
 *
 * Benefits over previous hard-coded approach:
 * - More accurate ecological distribution
 * - Easier to add new biomes (just update the table)
 * - Clearer biome boundaries and transitions
 * - No complex conditional logic
 * - Tunable via configuration (future enhancement)
 */
public class BiomeClassifier {

    /**
     * Number of temperature divisions in the Whittaker table.
     * Temperature range [0.0, 1.0] is divided into 6 zones:
     * Row 0: [0.0, 0.167) - Coldest
     * Row 1: [0.167, 0.333)
     * Row 2: [0.333, 0.5)
     * Row 3: [0.5, 0.667)
     * Row 4: [0.667, 0.833)
     * Row 5: [0.833, 1.0] - Hottest
     */
    private static final int TEMP_DIVISIONS = 6;

    /**
     * Number of moisture divisions in the Whittaker table.
     * Moisture range [0.0, 1.0] is divided into 6 zones:
     * Col 0: [0.0, 0.167) - Driest
     * Col 1: [0.167, 0.333)
     * Col 2: [0.333, 0.5)
     * Col 3: [0.5, 0.667)
     * Col 4: [0.667, 0.833)
     * Col 5: [0.833, 1.0] - Wettest
     */
    private static final int MOISTURE_DIVISIONS = 6;

    /**
     * Whittaker diagram lookup table: [temperature_index][moisture_index] -> BiomeType
     *
     * Table layout (reading left-to-right, bottom-to-top):
     * ```
     *           Dry →                                        → Wet
     *           0.0      0.2      0.4      0.6      0.8      1.0  (Moisture)
     * Hot  1.0  RED_S    DESERT   RED_S    RED_S    RED_S   PLAINS
     *      0.8  RED_S    DESERT   RED_S    RED_S   PLAINS   PLAINS
     *      0.6  DESERT   DESERT   PLAINS   PLAINS  PLAINS   PLAINS
     *      0.4  DESERT   DESERT   PLAINS   PLAINS  PLAINS   PLAINS
     *      0.2  PLAINS   PLAINS   PLAINS   SNOWY   SNOWY    SNOWY
     * Cold 0.0  PLAINS   PLAINS   SNOWY    SNOWY   SNOWY    SNOWY
     * ```
     *
     * Legend:
     * - RED_S = RED_SAND_DESERT (volcanic biome)
     * - SNOWY = SNOWY_PLAINS
     */
    private final BiomeType[][] whittakerTable;

    /**
     * Creates a new biome classifier with the Whittaker lookup table.
     */
    public BiomeClassifier() {
        this.whittakerTable = new BiomeType[TEMP_DIVISIONS][MOISTURE_DIVISIONS];
        initializeWhittakerTable();
    }

    /**
     * Initializes the Whittaker diagram lookup table with biome distributions.
     *
     * Phase 4: Expanded to 10 biomes with ecological distribution across climate zones.
     *
     * Table is organized as [temperature_row][moisture_column]:
     * - Row 0 (coldest): 0.0 - 0.167 temperature
     * - Row 5 (hottest): 0.833 - 1.0 temperature
     * - Col 0 (driest): 0.0 - 0.167 moisture
     * - Col 5 (wettest): 0.833 - 1.0 moisture
     *
     * Ecological zones:
     * - Cold Zone (Rows 0-1): TUNDRA (dry) → TAIGA (moderate) → SNOWY_PLAINS (moist) → ICE_FIELDS (very wet)
     * - Temperate Zone (Rows 2-3): STONY_PEAKS/GRAVEL_BEACH (dry/coastal) → PLAINS (moderate-wet)
     * - Hot Zone (Rows 4-5): BADLANDS (very dry) → DESERT (dry) → RED_SAND_DESERT (moderate-hot) → PLAINS (hot-wet)
     */
    private void initializeWhittakerTable() {
        // Row 0 (coldest): Temperature [0.0, 0.167)
        // Cold regions: tundra (dry) → ice fields (very wet)
        whittakerTable[0][0] = BiomeType.TUNDRA;         // Cold + Very Dry - Frozen wasteland
        whittakerTable[0][1] = BiomeType.TUNDRA;         // Cold + Dry - Permafrost plains
        whittakerTable[0][2] = BiomeType.TUNDRA;         // Cold + Moderate-Dry - Sparse tundra
        whittakerTable[0][3] = BiomeType.ICE_FIELDS;     // Cold + Moderate-Wet - Glacial transition
        whittakerTable[0][4] = BiomeType.ICE_FIELDS;     // Cold + Wet - Glacier fields
        whittakerTable[0][5] = BiomeType.ICE_FIELDS;     // Cold + Very Wet - Ice sheets

        // Row 1 (cool): Temperature [0.167, 0.333)
        // Cool regions: tundra/taiga transition → snowy plains → ice fields
        whittakerTable[1][0] = BiomeType.TUNDRA;         // Cool + Very Dry - Barren tundra
        whittakerTable[1][1] = BiomeType.TUNDRA;         // Cool + Dry - Sparse tundra
        whittakerTable[1][2] = BiomeType.TAIGA;          // Cool + Moderate-Dry - Boreal forest
        whittakerTable[1][3] = BiomeType.SNOWY_PLAINS;   // Cool + Moderate-Wet - Snowy grasslands
        whittakerTable[1][4] = BiomeType.SNOWY_PLAINS;   // Cool + Wet - Snow-covered plains
        whittakerTable[1][5] = BiomeType.ICE_FIELDS;     // Cool + Very Wet - Glacial edges

        // Row 2 (temperate-cool): Temperature [0.333, 0.5)
        // Temperate regions: stony peaks/beach → plains
        whittakerTable[2][0] = BiomeType.STONY_PEAKS;    // Temperate-Cool + Very Dry - Rocky mountains
        whittakerTable[2][1] = BiomeType.GRAVEL_BEACH;   // Temperate-Cool + Dry - Coastal shores
        whittakerTable[2][2] = BiomeType.PLAINS;         // Temperate-Cool + Moderate-Dry - Grasslands
        whittakerTable[2][3] = BiomeType.GRAVEL_BEACH;   // Temperate-Cool + Moderate-Wet - Sandy beaches
        whittakerTable[2][4] = BiomeType.PLAINS;         // Temperate-Cool + Wet - Lush plains
        whittakerTable[2][5] = BiomeType.PLAINS;         // Temperate-Cool + Very Wet - Wetland plains

        // Row 3 (temperate-warm): Temperature [0.5, 0.667)
        // Warm temperate regions: desert → plains
        whittakerTable[3][0] = BiomeType.DESERT;         // Temperate-Warm + Very Dry - Arid desert
        whittakerTable[3][1] = BiomeType.DESERT;         // Temperate-Warm + Dry - Sandy desert
        whittakerTable[3][2] = BiomeType.PLAINS;         // Temperate-Warm + Moderate-Dry - Savanna-like
        whittakerTable[3][3] = BiomeType.PLAINS;         // Temperate-Warm + Moderate-Wet - Temperate grasslands
        whittakerTable[3][4] = BiomeType.PLAINS;         // Temperate-Warm + Wet - Fertile plains
        whittakerTable[3][5] = BiomeType.PLAINS;         // Temperate-Warm + Very Wet - Wetlands

        // Row 4 (hot): Temperature [0.667, 0.833)
        // Hot regions: badlands/desert → red sand desert → plains
        whittakerTable[4][0] = BiomeType.BADLANDS;       // Hot + Very Dry - Eroded badlands
        whittakerTable[4][1] = BiomeType.DESERT;         // Hot + Dry - Hot desert
        whittakerTable[4][2] = BiomeType.RED_SAND_DESERT; // Hot + Moderate-Dry - Volcanic desert
        whittakerTable[4][3] = BiomeType.RED_SAND_DESERT; // Hot + Moderate-Wet - Volcanic hills
        whittakerTable[4][4] = BiomeType.PLAINS;         // Hot + Wet - Hot grasslands
        whittakerTable[4][5] = BiomeType.PLAINS;         // Hot + Very Wet - Tropical grasslands

        // Row 5 (hottest): Temperature [0.833, 1.0]
        // Hottest regions: badlands → volcanic deserts → tropical plains
        whittakerTable[5][0] = BiomeType.BADLANDS;       // Very Hot + Very Dry - Mesa badlands
        whittakerTable[5][1] = BiomeType.DESERT;         // Very Hot + Dry - Scorching desert
        whittakerTable[5][2] = BiomeType.RED_SAND_DESERT; // Very Hot + Moderate-Dry - Volcanic wasteland
        whittakerTable[5][3] = BiomeType.RED_SAND_DESERT; // Very Hot + Moderate-Wet - Volcanic terrain
        whittakerTable[5][4] = BiomeType.RED_SAND_DESERT; // Very Hot + Wet - Hot volcanic region
        whittakerTable[5][5] = BiomeType.PLAINS;         // Very Hot + Very Wet - Tropical plains
    }

    /**
     * Classifies a biome based on temperature and moisture values.
     *
     * Converts continuous temperature/moisture values to discrete table indices
     * and performs a lookup in the Whittaker diagram.
     *
     * @param temperature Temperature value in range [0.0, 1.0] (0 = coldest, 1 = hottest)
     * @param moisture    Moisture value in range [0.0, 1.0] (0 = driest, 1 = wettest)
     * @return The biome type at this temperature/moisture combination
     */
    public BiomeType classify(float temperature, float moisture) {
        // Clamp values to valid range [0.0, 1.0]
        temperature = Math.max(0.0f, Math.min(1.0f, temperature));
        moisture = Math.max(0.0f, Math.min(1.0f, moisture));

        // Convert continuous values to discrete table indices
        // Multiply by (divisions - 1) to map [0.0, 1.0] to [0, divisions-1]
        int tempIndex = (int) (temperature * (TEMP_DIVISIONS - 1));
        int moistureIndex = (int) (moisture * (MOISTURE_DIVISIONS - 1));

        // Ensure indices are within bounds (handles edge case of exactly 1.0)
        tempIndex = Math.min(tempIndex, TEMP_DIVISIONS - 1);
        moistureIndex = Math.min(moistureIndex, MOISTURE_DIVISIONS - 1);

        return whittakerTable[tempIndex][moistureIndex];
    }

    /**
     * Gets the number of temperature divisions in the table.
     * Useful for debugging or visualization tools.
     *
     * @return Number of temperature divisions
     */
    public int getTemperatureDivisions() {
        return TEMP_DIVISIONS;
    }

    /**
     * Gets the number of moisture divisions in the table.
     * Useful for debugging or visualization tools.
     *
     * @return Number of moisture divisions
     */
    public int getMoistureDivisions() {
        return MOISTURE_DIVISIONS;
    }
}
