package com.stonebreak.blocks.waterSystem;

import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector3i;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles water wave simulation including surface waves, ripples, and height calculations.
 * Provides realistic wave dynamics with multiple octaves and seamless tiling.
 */
public class WaveSimulation {

    // Wave simulation constants
    private static final float WAVE_AMPLITUDE = 0.15f;
    private static final float WAVE_SPEED = 1.5f;
    private static final float RIPPLE_SPEED = 3.0f;
    private static final float RIPPLE_DECAY = 2.0f;

    // Visual enhancement constants
    private static final float CAUSTIC_INTENSITY = 0.3f;
    private static final float REFRACTION_STRENGTH = 0.02f;

    private final List<WaterRipple> ripples;
    private final Map<Vector3i, WaveData> waveField;
    private final Map<Vector3i, Float> heightCache;
    private final Set<Vector3i> dirtyBlocks;
    private boolean needsWaveUpdate;
    private float totalTime;

    public WaveSimulation() {
        this.ripples = Collections.synchronizedList(new ArrayList<>());
        this.waveField = new ConcurrentHashMap<>();
        this.heightCache = new ConcurrentHashMap<>();
        this.dirtyBlocks = Collections.synchronizedSet(new HashSet<>());
        this.needsWaveUpdate = false;
        this.totalTime = 0.0f;
    }

    /**
     * Updates wave simulation for realistic water surface.
     *
     * @param deltaTime Time elapsed since last update
     */
    public void update(float deltaTime) {
        totalTime += deltaTime;

        updateRipples(deltaTime);
        updateWaveField(deltaTime);

        // Clear per-frame caches – wave height depends on time, so cached heights
        // must be invalidated every update to avoid appearing static.
        if (needsWaveUpdate) {
            heightCache.clear();
            needsWaveUpdate = false;
        }

        // Mark for height cache update
        needsWaveUpdate = true;
    }

    /**
     * Gets the water surface height at a position including waves.
     *
     * @param x X coordinate
     * @param z Z coordinate
     * @return Surface height including waves and ripples
     */
    public float getWaterSurfaceHeight(float x, float z) {
        Vector3i blockPos = new Vector3i((int)x, 0, (int)z);

        // Check cache first
        Float cached = heightCache.get(blockPos);
        if (cached != null) {
            return cached;
        }

        // Calculate base height
        float baseHeight = 0.875f; // Default water height

        // Add wave displacement
        float waveHeight = calculateWaveHeight(x, z);

        // Add ripple effects
        float rippleHeight = calculateRippleHeight(x, z);

        float totalHeight = baseHeight + waveHeight + rippleHeight;
        heightCache.put(blockPos, totalHeight);

        return totalHeight;
    }

    /**
     * Gets the visual height of water for rendering with wave effects.
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     * @param baseHeight Base water height from flow simulation
     * @return Visual height including wave displacement
     */
    public float getWaterVisualHeight(int x, int y, int z, float baseHeight) {
        // Add wave effects to visual height
        float waveOffset = calculateWaveHeight(x + 0.5f, z + 0.5f) * 0.5f;
        return baseHeight + waveOffset;
    }

    /**
     * Creates a water ripple at the specified position.
     *
     * @param position Center position of the ripple
     * @param strength Strength of the ripple
     * @param maxRadius Maximum radius the ripple will expand to
     */
    public void createRipple(Vector3f position, float strength, float maxRadius) {
        if (ripples.size() < 50) {
            ripples.add(new WaterRipple(new Vector3f(position), strength, maxRadius));
        }
    }

    /**
     * Gets caustic light pattern intensity with seamless tiling.
     *
     * @param x X coordinate
     * @param z Z coordinate
     * @return Caustic intensity for lighting calculations
     */
    public float getCausticIntensity(float x, float z) {
        // Use seamless frequencies for animated caustic pattern
        final float seamlessScale = 0.5f; // Match wave calculation scale

        float pattern1 = (float)(Math.sin(x * seamlessScale * 6.0f + totalTime * 0.5) *
                                Math.cos(z * seamlessScale * 6.0f + totalTime * 0.3));
        float pattern2 = (float)(Math.sin(x * seamlessScale * 10.0f - totalTime * 0.7) *
                                Math.cos(z * seamlessScale * 8.0f - totalTime * 0.4));

        return (pattern1 + pattern2 * 0.5f) * CAUSTIC_INTENSITY + CAUSTIC_INTENSITY;
    }

    /**
     * Gets refraction offset for underwater distortion with seamless tiling.
     *
     * @param x X coordinate
     * @param z Z coordinate
     * @return Refraction offset vector for distortion
     */
    public Vector2f getRefractionOffset(float x, float z) {
        // Use seamless scale for refraction offset
        final float seamlessScale = 0.5f; // Match other calculations

        float offsetX = (float)Math.sin(x * seamlessScale * 4.0f + totalTime) * REFRACTION_STRENGTH;
        float offsetZ = (float)Math.cos(z * seamlessScale * 4.0f + totalTime * 0.8f) * REFRACTION_STRENGTH;

        return new Vector2f(offsetX, offsetZ);
    }

    /**
     * Creates initial wave data for a water block.
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     */
    public void createWaveData(int x, int y, int z) {
        Vector3i pos = new Vector3i(x, y, z);
        waveField.put(pos, new WaveData());
    }

    /**
     * Removes wave data for a water block.
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     */
    public void removeWaveData(int x, int y, int z) {
        Vector3i pos = new Vector3i(x, y, z);
        waveField.remove(pos);
    }

    /**
     * Gets a copy of current ripples for rendering.
     *
     * @return List of current water ripples
     */
    public List<WaterRipple> getRipples() {
        return new ArrayList<>(ripples);
    }

    /**
     * Clears all wave simulation data.
     */
    public void clear() {
        ripples.clear();
        waveField.clear();
        heightCache.clear();
        dirtyBlocks.clear();
    }

    /**
     * Calculates wave height using multiple octaves with seamless tiling.
     */
    private float calculateWaveHeight(float x, float z) {
        float height = 0;

        // Use consistent frequencies that ensure seamless tiling
        final float seamlessScale = 0.5f; // Scale for world-space seamless tiling

        // Primary wave - consistent with texture generation
        height += Math.sin(x * seamlessScale + totalTime * WAVE_SPEED) *
                 Math.cos(z * seamlessScale * 0.8f + totalTime * WAVE_SPEED * 0.9f) *
                 WAVE_AMPLITUDE;

        // Secondary wave - smaller, faster, still seamless
        height += Math.sin(x * seamlessScale * 2.0f + z * seamlessScale * 1.5f + totalTime * WAVE_SPEED * 2.0f) *
                 WAVE_AMPLITUDE * 0.3f;

        // Tertiary wave - detail with seamless frequency
        height += Math.sin(x * seamlessScale * 4.0f + totalTime * 3.0f) *
                 Math.cos(z * seamlessScale * 3.0f + totalTime * 2.5f) *
                 WAVE_AMPLITUDE * 0.1f;

        return height;
    }

    /**
     * Calculates ripple displacement at a position.
     */
    private float calculateRippleHeight(float x, float z) {
        float height = 0;

        for (WaterRipple ripple : ripples) {
            float dist = (float)Math.sqrt(
                Math.pow(x - ripple.center.x, 2) +
                Math.pow(z - ripple.center.z, 2)
            );

            if (dist < ripple.radius && dist > ripple.radius - 1.0f) {
                float wave = (float)Math.sin((dist - ripple.radius) * Math.PI) *
                            ripple.strength *
                            (1.0f - ripple.radius / ripple.maxRadius);
                height += wave;
            }
        }

        return height;
    }

    /**
     * Updates water ripples with expansion and decay.
     */
    private void updateRipples(float deltaTime) {
        ripples.removeIf(ripple -> {
            ripple.update(deltaTime);
            return ripple.isDead();
        });
    }

    /**
     * Updates wave field for surface animation.
     */
    private void updateWaveField(float deltaTime) {
        // Update wave field based on wind and time
        for (Map.Entry<Vector3i, WaveData> entry : waveField.entrySet()) {
            WaveData wave = entry.getValue();
            wave.update(deltaTime, totalTime);
        }
    }

    /**
     * Water ripple effect.
     */
    public static class WaterRipple {
        final Vector3f center;
        float radius;
        final float maxRadius;
        float strength;

        public WaterRipple(Vector3f center, float strength, float maxRadius) {
            this.center = center;
            this.strength = strength;
            this.maxRadius = maxRadius;
            this.radius = 0;
        }

        public void update(float deltaTime) {
            radius += RIPPLE_SPEED * deltaTime;
            strength *= Math.pow(0.5f, deltaTime * RIPPLE_DECAY);
        }

        public boolean isDead() {
            return radius > maxRadius || strength < 0.01f;
        }

        public Vector3f getCenter() { return center; }
        public float getRadius() { return radius; }
        public float getStrength() { return strength; }
        public float getMaxRadius() { return maxRadius; }
    }

    /**
     * Wave data for surface animation.
     */
    private static class WaveData {
        private float phase = 0;
        private float amplitude = 1.0f;

        void update(float deltaTime, float totalTime) {
            // Wave data update logic can be extended here if needed
            phase += deltaTime * WAVE_SPEED;
        }

        public float getPhase() { return phase; }
        public float getAmplitude() { return amplitude; }
    }

    /**
     * Wave simulation constants for external access.
     */
    public static final class Constants {
        public static final float WAVE_AMPLITUDE = WaveSimulation.WAVE_AMPLITUDE;
        public static final float WAVE_SPEED = WaveSimulation.WAVE_SPEED;
        public static final float RIPPLE_SPEED = WaveSimulation.RIPPLE_SPEED;
        public static final float RIPPLE_DECAY = WaveSimulation.RIPPLE_DECAY;
        public static final float CAUSTIC_INTENSITY = WaveSimulation.CAUSTIC_INTENSITY;
        public static final float REFRACTION_STRENGTH = WaveSimulation.REFRACTION_STRENGTH;

        private Constants() {} // Utility class
    }
}