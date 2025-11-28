package com.openmason.main.systems.menus.textureCreator.filters.noise;

import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.filters.LayerFilter;
import com.openmason.main.systems.menus.textureCreator.selection.SelectionRegion;

/**
 * Filter that applies noise to a layer using various noise algorithms.
 * Supports both uniform and gradient noise modes.
 */
public class NoiseFilter implements LayerFilter {

    private final NoiseConfig config;

    public NoiseFilter(NoiseConfig config) {
        this.config = config;
    }

    @Override
    public String getName() {
        return "Noise (" + config.generator().getName() + ")";
    }

    @Override
    public String getDescription() {
        return "Applies " + config.generator().getName() + " noise to the layer";
    }

    @Override
    public void apply(PixelCanvas canvas, SelectionRegion selection) {
        int width = canvas.getWidth();
        int height = canvas.getHeight();

        // Generate noise into a temporary buffer
        float[][] noiseBuffer = new float[width][height];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // Skip if outside selection
                if (selection != null && !selection.contains(x, y)) {
                    noiseBuffer[x][y] = 0.5f; // Neutral value
                    continue;
                }

                // Generate base noise value
                noiseBuffer[x][y] = generateNoiseValue(x, y, width, height);
            }
        }

        // Apply diffusion effects to the noise buffer
        applyDiffusionEffects(noiseBuffer, width, height, selection);

        // Apply the diffused noise to the canvas
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // Skip if outside selection
                if (selection != null && !selection.contains(x, y)) {
                    continue;
                }

                // Get current pixel color
                int originalColor = canvas.getPixel(x, y);
                int[] rgba = PixelCanvas.unpackRGBA(originalColor);

                // Apply noise to RGB channels
                float noiseValue = noiseBuffer[x][y];
                int r = applyNoise(rgba[0], noiseValue);
                int g = applyNoise(rgba[1], noiseValue);
                int b = applyNoise(rgba[2], noiseValue);

                // Keep original alpha
                int newColor = PixelCanvas.packRGBA(r, g, b, rgba[3]);
                canvas.setPixel(x, y, newColor);
            }
        }
    }

    private float generateNoiseValue(int x, int y, int width, int height) {
        // Scale coordinates
        float sx = x * config.scale();
        float sy = y * config.scale();

        // Generate base noise
        float noise = config.generator().generate(sx, sy);

        // Apply gradient if enabled
        if (config.gradient()) {
            // Create gradient from top-left to bottom-right
            float gradientFactor = ((float) x / width + (float) y / height) * 0.5f;
            noise = lerp(noise, gradientFactor, 0.5f);
        }

        return noise;
    }

    private int applyNoise(int channelValue, float noiseValue) {
        // Map noise from [0, 1] to [-1, 1] for bidirectional effect
        float noise = (noiseValue * 2.0f - 1.0f) * config.strength() * 255.0f;

        // Apply noise to channel
        int result = (int) (channelValue + noise);

        // Clamp to valid range
        return Math.max(0, Math.min(255, result));
    }

    private float lerp(float a, float b, float t) {
        return a + t * (b - a);
    }

    /**
     * Apply diffusion effects to the noise buffer.
     */
    private void applyDiffusionEffects(float[][] noiseBuffer, int width, int height, SelectionRegion selection) {
        // Apply octaves (Fractal Brownian Motion)
        if (config.octaves() > 1) {
            applyOctaves(noiseBuffer, width, height, selection);
        }

        // Apply spread (contrast stretching)
        if (config.spread() != 0.5f) {
            applySpread(noiseBuffer, width, height, selection);
        }

        // Apply edge softness (smoothstep)
        if (config.edgeSoftness() > 0.0f) {
            applyEdgeSoftness(noiseBuffer, width, height, selection);
        }

        // Apply blur (box blur)
        if (config.blur() > 0.0f) {
            applyBlur(noiseBuffer, width, height, selection);
        }
    }

    /**
     * Apply Fractal Brownian Motion by layering multiple octaves of noise.
     */
    private void applyOctaves(float[][] noiseBuffer, int width, int height, SelectionRegion selection) {
        int octaves = config.octaves();
        float[][] tempBuffer = new float[width][height];

        // Copy original noise
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                tempBuffer[x][y] = noiseBuffer[x][y];
            }
        }

        float amplitude = 1.0f;
        float totalAmplitude = 1.0f;

        // Add additional octaves with decreasing amplitude and increasing frequency
        for (int octave = 1; octave < octaves; octave++) {
            amplitude *= 0.5f;
            totalAmplitude += amplitude;
            float frequency = (float) Math.pow(2.0, octave);

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (selection != null && !selection.contains(x, y)) {
                        continue;
                    }

                    float sx = x * config.scale() * frequency;
                    float sy = y * config.scale() * frequency;
                    float octaveNoise = config.generator().generate(sx, sy);
                    tempBuffer[x][y] += octaveNoise * amplitude;
                }
            }
        }

        // Normalize by total amplitude
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (selection != null && !selection.contains(x, y)) {
                    continue;
                }
                noiseBuffer[x][y] = tempBuffer[x][y] / totalAmplitude;
            }
        }
    }

    /**
     * Apply contrast stretching based on spread parameter.
     */
    private void applySpread(float[][] noiseBuffer, int width, int height, SelectionRegion selection) {
        float spread = config.spread();

        // Find min and max values
        float min = 1.0f;
        float max = 0.0f;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (selection != null && !selection.contains(x, y)) {
                    continue;
                }
                float value = noiseBuffer[x][y];
                if (value < min) min = value;
                if (value > max) max = value;
            }
        }

        // Apply spread (0.0 = collapse to average, 1.0 = full range)
        float range = max - min;
        float center = (min + max) * 0.5f;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (selection != null && !selection.contains(x, y)) {
                    continue;
                }

                float value = noiseBuffer[x][y];
                float normalized = (value - center) / (range + 0.0001f); // Avoid division by zero
                float scaled = normalized * spread;
                noiseBuffer[x][y] = Math.max(0.0f, Math.min(1.0f, center + scaled * range));
            }
        }
    }

    /**
     * Apply smoothstep to soften transitions between noise values.
     */
    private void applyEdgeSoftness(float[][] noiseBuffer, int width, int height, SelectionRegion selection) {
        float softness = config.edgeSoftness();

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (selection != null && !selection.contains(x, y)) {
                    continue;
                }

                float value = noiseBuffer[x][y];
                // Apply smoothstep interpolation
                float smoothed = smoothstep(value);
                // Blend between original and smoothed based on softness
                noiseBuffer[x][y] = lerp(value, smoothed, softness);
            }
        }
    }

    /**
     * Apply box blur to smooth the noise.
     */
    private void applyBlur(float[][] noiseBuffer, int width, int height, SelectionRegion selection) {
        float blur = config.blur();
        int radius = (int) Math.ceil(blur * 5.0f); // Max radius of 5 pixels

        if (radius <= 0) return;

        float[][] tempBuffer = new float[width][height];

        // Copy original buffer
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                tempBuffer[x][y] = noiseBuffer[x][y];
            }
        }

        // Apply box blur
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (selection != null && !selection.contains(x, y)) {
                    continue;
                }

                float sum = 0.0f;
                int count = 0;

                // Sample surrounding pixels
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        int nx = x + dx;
                        int ny = y + dy;

                        // Clamp to bounds
                        if (nx >= 0 && nx < width && ny >= 0 && ny < height) {
                            sum += tempBuffer[nx][ny];
                            count++;
                        }
                    }
                }

                // Average
                noiseBuffer[x][y] = sum / count;
            }
        }
    }

    /**
     * Smoothstep interpolation function.
     */
    private float smoothstep(float t) {
        t = Math.max(0.0f, Math.min(1.0f, t));
        return t * t * (3.0f - 2.0f * t);
    }

}
