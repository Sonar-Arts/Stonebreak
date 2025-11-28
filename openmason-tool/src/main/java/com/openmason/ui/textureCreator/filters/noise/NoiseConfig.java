package com.openmason.ui.textureCreator.filters.noise;

/**
 * Immutable configuration for noise filter parameters.
 */
public class NoiseConfig {

    private final NoiseGenerator generator;
    private final float strength;
    private final boolean gradient;
    private final float scale;
    private final long seed;

    // Diffusion parameters
    private final float blur;
    private final int octaves;
    private final float spread;
    private final float edgeSoftness;

    public NoiseConfig(NoiseGenerator generator, float strength, boolean gradient, float scale, long seed,
                       float blur, int octaves, float spread, float edgeSoftness) {
        this.generator = generator;
        this.strength = Math.max(0.0f, Math.min(1.0f, strength));
        this.gradient = gradient;
        this.scale = Math.max(0.1f, scale);
        this.seed = seed;

        // Validate and clamp diffusion parameters
        this.blur = Math.max(0.0f, Math.min(1.0f, blur));
        this.octaves = Math.max(1, Math.min(8, octaves));
        this.spread = Math.max(0.0f, Math.min(1.0f, spread));
        this.edgeSoftness = Math.max(0.0f, Math.min(1.0f, edgeSoftness));
    }

    public NoiseGenerator getGenerator() {
        return generator;
    }

    public float getStrength() {
        return strength;
    }

    public boolean isGradient() {
        return gradient;
    }

    public float getScale() {
        return scale;
    }

    public long getSeed() {
        return seed;
    }

    public float getBlur() {
        return blur;
    }

    public int getOctaves() {
        return octaves;
    }

    public float getSpread() {
        return spread;
    }

    public float getEdgeSoftness() {
        return edgeSoftness;
    }

    public NoiseConfig withStrength(float strength) {
        return new NoiseConfig(generator, strength, gradient, scale, seed, blur, octaves, spread, edgeSoftness);
    }

    public NoiseConfig withGradient(boolean gradient) {
        return new NoiseConfig(generator, strength, gradient, scale, seed, blur, octaves, spread, edgeSoftness);
    }

    public NoiseConfig withScale(float scale) {
        return new NoiseConfig(generator, strength, gradient, scale, seed, blur, octaves, spread, edgeSoftness);
    }

    public NoiseConfig withGenerator(NoiseGenerator generator) {
        return new NoiseConfig(generator, strength, gradient, scale, seed, blur, octaves, spread, edgeSoftness);
    }

    public NoiseConfig withBlur(float blur) {
        return new NoiseConfig(generator, strength, gradient, scale, seed, blur, octaves, spread, edgeSoftness);
    }

    public NoiseConfig withOctaves(int octaves) {
        return new NoiseConfig(generator, strength, gradient, scale, seed, blur, octaves, spread, edgeSoftness);
    }

    public NoiseConfig withSpread(float spread) {
        return new NoiseConfig(generator, strength, gradient, scale, seed, blur, octaves, spread, edgeSoftness);
    }

    public NoiseConfig withEdgeSoftness(float edgeSoftness) {
        return new NoiseConfig(generator, strength, gradient, scale, seed, blur, octaves, spread, edgeSoftness);
    }

    public static NoiseConfig createDefault() {
        return new NoiseConfig(
                new SimplexNoiseGenerator(System.nanoTime()),
                0.5f,
                false,
                1.0f,
                System.nanoTime(),
                0.3f,  // blur
                4,     // octaves
                0.5f,  // spread
                0.2f   // edgeSoftness
        );
    }
}
