package com.stonebreak.blocks;

import com.stonebreak.blocks.waterSystem.*;
import com.stonebreak.player.Player;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.util.List;

/**
 * Water block class that serves as the main controller for water physics simulation.
 * This class coordinates all water-related systems through modular components:
 * - FlowSimulation: Handles water flow physics and pressure calculations
 * - WaveSimulation: Manages surface waves, ripples, and visual effects
 * - WaterParticles: Controls particle systems for splashes and bubbles
 * - WaterBlock: Data model for individual water blocks
 */
public class Water {

    // Water system modules
    private static FlowSimulation flowSimulation;
    private static WaveSimulation waveSimulation;
    private static WaterParticles waterParticles;

    // Initialize water system modules
    static {
        initializeWaterSystem();
    }

    /**
     * Initializes all water system modules.
     */
    private static void initializeWaterSystem() {
        flowSimulation = new FlowSimulation();
        waveSimulation = new WaveSimulation();
        waterParticles = new WaterParticles();
    }

    /**
     * Ensures water system is initialized.
     */
    private static void ensureInitialized() {
        if (flowSimulation == null || waveSimulation == null || waterParticles == null) {
            initializeWaterSystem();
        }
    }

    // === WATER SOURCE MANAGEMENT ===

    /**
     * Creates a water source at the specified position.
     * This should be called when a water block is placed in the world.
     *
     * @param x X coordinate of the water block
     * @param y Y coordinate of the water block
     * @param z Z coordinate of the water block
     */
    public static void addWaterSource(int x, int y, int z) {
        ensureInitialized();
        flowSimulation.addWaterSource(x, y, z);
        waveSimulation.createWaveData(x, y, z);
    }

    /**
     * Removes a water source at the specified position.
     * This should be called when a water block is broken or removed.
     *
     * @param x X coordinate of the water block
     * @param y Y coordinate of the water block
     * @param z Z coordinate of the water block
     */
    public static void removeWaterSource(int x, int y, int z) {
        ensureInitialized();
        flowSimulation.removeWaterSource(x, y, z);
        waveSimulation.removeWaveData(x, y, z);
    }

    // === WATER LEVEL AND STATE QUERIES ===

    /**
     * Gets the water level at a specific position (0.0 to 1.0).
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     * @return Water level from 0.0 (no water) to 1.0 (full water block)
     */
    public static float getWaterLevel(int x, int y, int z) {
        ensureInitialized();
        return flowSimulation.getWaterLevel(x, y, z);
    }

    /**
     * Checks if a position contains a water source block.
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     * @return true if the position contains a water source
     */
    public static boolean isWaterSource(int x, int y, int z) {
        ensureInitialized();
        return flowSimulation.isWaterSource(x, y, z);
    }

    /**
     * Gets a water block instance at the specified position.
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     * @return WaterBlock instance or null if none exists
     */
    public static WaterBlock getWaterBlock(int x, int y, int z) {
        ensureInitialized();
        return flowSimulation.getWaterBlock(x, y, z);
    }

    // === VISUAL HEIGHT AND SURFACE CALCULATIONS ===

    /**
     * Gets the visual height of water for rendering purposes.
     * This includes wave effects and surface displacement.
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     * @return Visual water height for rendering
     */
    public static float getWaterVisualHeight(int x, int y, int z) {
        ensureInitialized();
        float baseHeight = flowSimulation.getWaterVisualHeight(x, y, z);
        return waveSimulation.getWaterVisualHeight(x, y, z, baseHeight);
    }

    /**
     * Gets the water surface height at a position including wave effects.
     *
     * @param x X coordinate (can be fractional)
     * @param z Z coordinate (can be fractional)
     * @return Surface height including waves and ripples
     */
    public static float getWaterSurfaceHeight(float x, float z) {
        ensureInitialized();
        return waveSimulation.getWaterSurfaceHeight(x, z);
    }

    // === PARTICLE EFFECTS ===

    /**
     * Creates a splash effect when something enters water.
     *
     * @param position Position where the splash occurs
     * @param intensity Intensity of the splash (affects particle count and size)
     */
    public static void createSplash(Vector3f position, float intensity) {
        ensureInitialized();
        waterParticles.createSplash(position, intensity);
        waveSimulation.createRipple(position, intensity * 2, intensity * 5);
    }

    /**
     * Creates a ripple effect on the water surface.
     *
     * @param position Center position of the ripple
     * @param strength Strength of the ripple
     * @param maxRadius Maximum radius the ripple will expand to
     */
    public static void createRipple(Vector3f position, float strength, float maxRadius) {
        ensureInitialized();
        waveSimulation.createRipple(position, strength, maxRadius);
    }

    /**
     * Spawns a bubble particle at the specified position.
     *
     * @param position Position to spawn the bubble
     */
    public static void spawnBubble(Vector3f position) {
        ensureInitialized();
        waterParticles.spawnBubble(position);
    }

    // === VISUAL EFFECTS ===

    /**
     * Gets foam intensity at a position for rendering effects.
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     * @return Foam intensity (0.0 to 1.0)
     */
    public static float getFoamIntensity(float x, float y, float z) {
        ensureInitialized();
        return flowSimulation.getFoamIntensity(x, y, z);
    }

    /**
     * Gets caustic light pattern intensity for underwater lighting effects.
     *
     * @param x X coordinate
     * @param z Z coordinate
     * @return Caustic intensity for lighting calculations
     */
    public static float getCausticIntensity(float x, float z) {
        ensureInitialized();
        return waveSimulation.getCausticIntensity(x, z);
    }

    /**
     * Gets refraction offset for underwater distortion effects.
     *
     * @param x X coordinate
     * @param z Z coordinate
     * @return Refraction offset vector for distortion
     */
    public static Vector2f getRefractionOffset(float x, float z) {
        ensureInitialized();
        return waveSimulation.getRefractionOffset(x, z);
    }

    // === SYSTEM UPDATES ===

    /**
     * Updates water physics simulation.
     * This is typically called from the game loop.
     *
     * @param player Current player for movement-based effects
     * @param deltaTime Time elapsed since last update
     */
    public static void updatePhysics(Player player, float deltaTime) {
        ensureInitialized();
        flowSimulation.update(deltaTime);
        waveSimulation.update(deltaTime);
        waterParticles.update(player, deltaTime);
    }

    /**
     * Detects and registers existing water blocks in the world.
     * This should be called during world initialization.
     */
    public static void detectExistingWater() {
        ensureInitialized();
        flowSimulation.detectExistingWater();
    }

    // === RENDERING DATA ACCESS ===

    /**
     * Gets current water particles for rendering.
     *
     * @return List of current water particles
     */
    public static List<WaterParticles.WaterParticle> getParticles() {
        ensureInitialized();
        return waterParticles.getParticles();
    }

    /**
     * Gets current splash particles for rendering.
     *
     * @return List of current splash particles
     */
    public static List<WaterParticles.WaterParticle> getSplashParticles() {
        ensureInitialized();
        return waterParticles.getSplashParticles();
    }

    /**
     * Gets current bubble particles for rendering.
     *
     * @return List of current bubble particles
     */
    public static List<WaterParticles.BubbleParticle> getBubbles() {
        ensureInitialized();
        return waterParticles.getBubbles();
    }

    /**
     * Gets current water ripples for rendering.
     *
     * @return List of current water ripples
     */
    public static List<WaveSimulation.WaterRipple> getRipples() {
        ensureInitialized();
        return waveSimulation.getRipples();
    }

    // === SYSTEM MANAGEMENT ===

    /**
     * Clears all water simulation data.
     * This should be called when changing worlds or resetting the game.
     */
    public static void clearAll() {
        ensureInitialized();
        flowSimulation.detectExistingWater(); // This clears the flow simulation
        waveSimulation.clear();
        waterParticles.clear();
    }

    // === CONSTANTS ACCESS ===

    /**
     * Water block constants.
     */
    public static final class BlockConstants {
        public static final int MAX_WATER_LEVEL = WaterBlock.MAX_WATER_LEVEL;

        private BlockConstants() {} // Utility class
    }

    /**
     * Wave simulation constants.
     */
    public static final class WaveConstants {
        public static final float WAVE_AMPLITUDE = WaveSimulation.Constants.WAVE_AMPLITUDE;
        public static final float WAVE_SPEED = WaveSimulation.Constants.WAVE_SPEED;
        public static final float RIPPLE_SPEED = WaveSimulation.Constants.RIPPLE_SPEED;
        public static final float RIPPLE_DECAY = WaveSimulation.Constants.RIPPLE_DECAY;
        public static final float CAUSTIC_INTENSITY = WaveSimulation.Constants.CAUSTIC_INTENSITY;
        public static final float REFRACTION_STRENGTH = WaveSimulation.Constants.REFRACTION_STRENGTH;

        private WaveConstants() {} // Utility class
    }

    /**
     * Particle system constants.
     */
    public static final class ParticleConstants {
        public static final int MAX_PARTICLES = WaterParticles.Constants.MAX_PARTICLES;
        public static final int MAX_SPLASH_PARTICLES = WaterParticles.Constants.MAX_SPLASH_PARTICLES;
        public static final float PARTICLE_LIFETIME = WaterParticles.Constants.PARTICLE_LIFETIME;
        public static final float SPLASH_LIFETIME = WaterParticles.Constants.SPLASH_LIFETIME;
        public static final float PARTICLE_SIZE = WaterParticles.Constants.PARTICLE_SIZE;
        public static final float BUBBLE_SIZE = WaterParticles.Constants.BUBBLE_SIZE;

        private ParticleConstants() {} // Utility class
    }
}