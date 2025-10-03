package com.stonebreak.blocks;

import com.stonebreak.blocks.waterSystem.WaterBlock;
import com.stonebreak.blocks.waterSystem.WaterParticles;
import com.stonebreak.blocks.waterSystem.WaterSystem;
import com.stonebreak.blocks.waterSystem.WaveSimulation;
import com.stonebreak.core.Game;
import com.stonebreak.player.Player;
import com.stonebreak.world.World;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.util.List;

/**
 * Lightweight facade that exposes water-related helpers. The simulation itself
 * lives in {@link WaterSystem} on the active {@link World}; this class simply
 * forwards queries and keeps the visual helpers (waves/particles) alive.
 */
public final class Water {

    private static WaveSimulation waveSimulation = new WaveSimulation();
    private static WaterParticles waterParticles = new WaterParticles();

    private Water() {
    }

    private static void ensureVisualSystemsInitialized() {
        if (waveSimulation == null) {
            waveSimulation = new WaveSimulation();
        }
        if (waterParticles == null) {
            waterParticles = new WaterParticles();
        }
    }

    private static WaterSystem waterSystem() {
        World world = Game.getWorld();
        return world != null ? world.getWaterSystem() : null;
    }

    // === WATER STATE AND SOURCES ===

    public static void addWaterSource(int x, int y, int z) {
        WaterSystem system = waterSystem();
        if (system != null) {
            system.queueUpdate(x, y, z);
        }
        ensureVisualSystemsInitialized();
        waveSimulation.createWaveData(x, y, z);
    }

    public static void removeWaterSource(int x, int y, int z) {
        WaterSystem system = waterSystem();
        if (system != null) {
            system.queueUpdate(x, y, z);
        }
        ensureVisualSystemsInitialized();
        waveSimulation.removeWaveData(x, y, z);
    }

    public static float getWaterLevel(int x, int y, int z) {
        WaterSystem system = waterSystem();
        return system != null ? system.getWaterLevel(x, y, z) : 0.0f;
    }

    public static boolean isWaterSource(int x, int y, int z) {
        WaterSystem system = waterSystem();
        return system != null && system.isWaterSource(x, y, z);
    }

    public static WaterBlock getWaterBlock(int x, int y, int z) {
        WaterSystem system = waterSystem();
        return system != null ? system.getWaterBlock(x, y, z) : null;
    }

    // === VISUAL HEIGHT AND SURFACE CALCULATIONS ===

    public static float getWaterVisualHeight(int x, int y, int z) {
        ensureVisualSystemsInitialized();
        float baseLevel = getWaterLevel(x, y, z);
        return waveSimulation.getWaterVisualHeight(x, y, z, baseLevel);
    }

    public static float getWaterSurfaceHeight(float x, float z) {
        ensureVisualSystemsInitialized();
        return waveSimulation.getWaterSurfaceHeight(x, z);
    }

    // === PARTICLE AND RIPPLE EFFECTS ===

    public static void createSplash(Vector3f position, float intensity) {
        ensureVisualSystemsInitialized();
        waterParticles.createSplash(position, intensity);
        waveSimulation.createRipple(position, intensity * 2.0f, intensity * 5.0f);
    }

    public static void createRipple(Vector3f position, float strength, float maxRadius) {
        ensureVisualSystemsInitialized();
        waveSimulation.createRipple(position, strength, maxRadius);
    }

    public static void spawnBubble(Vector3f position) {
        ensureVisualSystemsInitialized();
        waterParticles.spawnBubble(position);
    }

    // === VISUAL EFFECT QUERIES ===

    public static float getFoamIntensity(float x, float y, float z) {
        WaterSystem system = waterSystem();
        return system != null ? system.getFoamIntensity(x, y, z) : 0.0f;
    }

    public static float getCausticIntensity(float x, float z) {
        ensureVisualSystemsInitialized();
        return waveSimulation.getCausticIntensity(x, z);
    }

    public static Vector2f getRefractionOffset(float x, float z) {
        ensureVisualSystemsInitialized();
        return waveSimulation.getRefractionOffset(x, z);
    }

    // === SYSTEM UPDATES ===

    public static void updatePhysics(Player player, float deltaTime) {
        ensureVisualSystemsInitialized();
        waveSimulation.update(deltaTime);
        waterParticles.update(player, deltaTime);
    }

    public static void detectExistingWater() {
        WaterSystem system = waterSystem();
        if (system != null) {
            // World/WaterSystem automatically tracks chunk loads; nothing else required here.
        }
    }

    public static void onBlockBroken(int x, int y, int z) {
        WaterSystem system = waterSystem();
        if (system != null) {
            system.queueUpdate(x, y, z);
        }
    }

    public static void onBlockPlaced(int x, int y, int z) {
        WaterSystem system = waterSystem();
        if (system != null) {
            system.queueUpdate(x, y, z);
        }
    }

    // === RENDERING DATA ACCESS ===

    public static List<WaterParticles.WaterParticle> getParticles() {
        ensureVisualSystemsInitialized();
        return waterParticles.getParticles();
    }

    public static List<WaterParticles.WaterParticle> getSplashParticles() {
        ensureVisualSystemsInitialized();
        return waterParticles.getSplashParticles();
    }

    public static List<WaterParticles.BubbleParticle> getBubbles() {
        ensureVisualSystemsInitialized();
        return waterParticles.getBubbles();
    }

    public static List<WaveSimulation.WaterRipple> getRipples() {
        ensureVisualSystemsInitialized();
        return waveSimulation.getRipples();
    }

    // === SYSTEM MANAGEMENT ===

    public static void clearAll() {
        ensureVisualSystemsInitialized();
        waveSimulation.clear();
        waterParticles.clear();
    }

    // === CONSTANTS ACCESS ===

    public static final class BlockConstants {
        public static final int MAX_WATER_LEVEL = WaterBlock.MAX_LEVEL;
        public static final int SOURCE_LEVEL = WaterBlock.SOURCE_LEVEL;

        private BlockConstants() {
        }
    }

    public static final class WaveConstants {
        public static final float WAVE_AMPLITUDE = WaveSimulation.Constants.WAVE_AMPLITUDE;
        public static final float WAVE_SPEED = WaveSimulation.Constants.WAVE_SPEED;
        public static final float RIPPLE_SPEED = WaveSimulation.Constants.RIPPLE_SPEED;
        public static final float RIPPLE_DECAY = WaveSimulation.Constants.RIPPLE_DECAY;
        public static final float CAUSTIC_INTENSITY = WaveSimulation.Constants.CAUSTIC_INTENSITY;
        public static final float REFRACTION_STRENGTH = WaveSimulation.Constants.REFRACTION_STRENGTH;

        private WaveConstants() {
        }
    }

    public static final class ParticleConstants {
        public static final int MAX_PARTICLES = WaterParticles.Constants.MAX_PARTICLES;
        public static final int MAX_SPLASH_PARTICLES = WaterParticles.Constants.MAX_SPLASH_PARTICLES;
        public static final float PARTICLE_LIFETIME = WaterParticles.Constants.PARTICLE_LIFETIME;
        public static final float SPLASH_LIFETIME = WaterParticles.Constants.SPLASH_LIFETIME;
        public static final float PARTICLE_SIZE = WaterParticles.Constants.PARTICLE_SIZE;
        public static final float BUBBLE_SIZE = WaterParticles.Constants.BUBBLE_SIZE;

        private ParticleConstants() {
        }
    }
}
