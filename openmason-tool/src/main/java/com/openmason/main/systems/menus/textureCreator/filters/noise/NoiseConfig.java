package com.openmason.main.systems.menus.textureCreator.filters.noise;

/**
 * Immutable configuration for noise filter parameters.
 *
 * @param blur Diffusion parameters
 */
public record NoiseConfig(NoiseGenerator generator, float strength, boolean gradient, float scale, float blur,
                          int octaves, float spread, float edgeSoftness) {

    public NoiseConfig(NoiseGenerator generator, float strength, boolean gradient, float scale,
                       float blur, int octaves, float spread, float edgeSoftness) {
        this.generator = generator;
        this.strength = Math.max(0.0f, Math.min(1.0f, strength));
        this.gradient = gradient;
        this.scale = Math.max(0.1f, scale);

        // Validate and clamp diffusion parameters
        this.blur = Math.max(0.0f, Math.min(1.0f, blur));
        this.octaves = Math.max(1, Math.min(8, octaves));
        this.spread = Math.max(0.0f, Math.min(1.0f, spread));
        this.edgeSoftness = Math.max(0.0f, Math.min(1.0f, edgeSoftness));
    }

}
