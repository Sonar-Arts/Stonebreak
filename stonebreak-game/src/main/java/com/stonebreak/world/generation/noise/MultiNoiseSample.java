package com.stonebreak.world.generation.noise;

/**
 * The biome selector's inputs for one column: the distilled terrain shape's fields
 * ({@link com.stonebreak.world.generation.heightmap.DistilledShape.Column}) plus climate.
 *
 * @param land        continent weight, 0 = open ocean in the controls, 1 = continent
 * @param mountain    mountain-range body (along-chain weighted), 0..1
 * @param hilly       hill-country province weight, 0..1
 * @param rough       terrain roughness, 0..1 (0 on open ocean)
 * @param height      the column's shaped height (no detail fuzz), Standard frame
 * @param temperature 0..1, altitude-chilled
 * @param moisture    0..1
 */
public record MultiNoiseSample(
    float land,
    float mountain,
    float hilly,
    float rough,
    int height,
    float temperature,
    float moisture
) {
}
