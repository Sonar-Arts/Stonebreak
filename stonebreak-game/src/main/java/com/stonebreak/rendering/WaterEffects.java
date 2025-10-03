package com.stonebreak.rendering;

import com.stonebreak.blocks.Water;
import com.stonebreak.blocks.waterSystem.WaterParticles;
import com.stonebreak.blocks.waterSystem.WaveSimulation;
import com.stonebreak.player.Player;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.util.List;

/**
 * Water effects system that delegates to the modular water physics system.
 * This class maintains backward compatibility with the existing rendering system
 * while the actual water physics are handled by the Water controller and its modules.
 *
 * @deprecated This class now serves as a delegation layer.
 * Use {@link Water} directly for new water physics functionality.
 */
public class WaterEffects {

    /**
     * Creates a new WaterEffects instance.
     * The actual water physics are handled by the modular Water system.
     */
    public WaterEffects() {
        // Water system is now handled by static modules in Water class
        // This constructor is kept for backward compatibility
    }

    /**
     * Main update method handling all water effects systems.
     * Delegates to the Water physics controller.
     *
     * @param player The player for movement-based effects
     * @param deltaTime Time elapsed since last update
     */
    public void update(Player player, float deltaTime) {
        Water.updatePhysics(player, deltaTime);
    }

    /**
     * Adds a water source at the specified position.
     * Delegates to Water controller.
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     */
    public void addWaterSource(int x, int y, int z) {
        Water.addWaterSource(x, y, z);
    }

    /**
     * Removes a water source at the specified position.
     * Delegates to Water controller.
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     */
    public void removeWaterSource(int x, int y, int z) {
        Water.removeWaterSource(x, y, z);
    }

    /**
     * Gets the water level at a specific position.
     * Delegates to Water controller.
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     * @return Water level from 0.0 to 1.0
     */
    public float getWaterLevel(int x, int y, int z) {
        return Water.getWaterLevel(x, y, z);
    }

    /**
     * Checks if a position contains a water source.
     * Delegates to Water controller.
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     * @return true if the position contains a water source
     */
    public boolean isWaterSource(int x, int y, int z) {
        return Water.isWaterSource(x, y, z);
    }

    /**
     * Gets the visual height of water for rendering.
     * Delegates to Water controller.
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     * @return Visual water height for rendering
     */
    public float getWaterVisualHeight(int x, int y, int z) {
        return Water.getWaterVisualHeight(x, y, z);
    }

    /**
     * Gets the water surface height at a position including wave effects.
     * Delegates to Water controller.
     *
     * @param x X coordinate
     * @param z Z coordinate
     * @return Surface height including waves and ripples
     */
    public float getWaterSurfaceHeight(float x, float z) {
        return Water.getWaterSurfaceHeight(x, z);
    }

    /**
     * Creates a splash effect when something enters water.
     * Delegates to Water controller.
     *
     * @param position Position where the splash occurs
     * @param intensity Intensity of the splash
     */
    public void createSplash(Vector3f position, float intensity) {
        Water.createSplash(position, intensity);
    }

    /**
     * Creates a ripple effect on the water surface.
     * Delegates to Water controller.
     *
     * @param position Center position of the ripple
     * @param strength Strength of the ripple
     * @param maxRadius Maximum radius the ripple will expand to
     */
    public void createRipple(Vector3f position, float strength, float maxRadius) {
        Water.createRipple(position, strength, maxRadius);
    }

    /**
     * Gets foam intensity at a position.
     * Delegates to Water controller.
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     * @return Foam intensity (0.0 to 1.0)
     */
    public float getFoamIntensity(float x, float y, float z) {
        return Water.getFoamIntensity(x, y, z);
    }

    /**
     * Gets caustic light pattern intensity.
     * Delegates to Water controller.
     *
     * @param x X coordinate
     * @param z Z coordinate
     * @return Caustic intensity for lighting calculations
     */
    public float getCausticIntensity(float x, float z) {
        return Water.getCausticIntensity(x, z);
    }

    /**
     * Gets refraction offset for underwater distortion.
     * Delegates to Water controller.
     *
     * @param x X coordinate
     * @param z Z coordinate
     * @return Refraction offset vector for distortion
     */
    public Vector2f getRefractionOffset(float x, float z) {
        return Water.getRefractionOffset(x, z);
    }

    /**
     * Detects existing water blocks in the world.
     * Delegates to Water controller.
     */
    public void detectExistingWater() {
        Water.detectExistingWater();
    }

    // === RENDERING DATA ACCESS ===
    // These methods provide access to rendering data for backward compatibility

    /**
     * Gets current water particles for rendering.
     * Delegates to Water controller.
     *
     * @return List of current water particles
     */
    public List<WaterParticle> getParticles() {
        // Convert from new particle system to legacy format
        return convertToLegacyParticles(Water.getParticles());
    }

    /**
     * Gets current splash particles for rendering.
     * Delegates to Water controller.
     *
     * @return List of current splash particles
     */
    public List<WaterParticle> getSplashParticles() {
        // Convert from new particle system to legacy format
        return convertToLegacyParticles(Water.getSplashParticles());
    }

    /**
     * Gets current bubble particles for rendering.
     * Delegates to Water controller.
     *
     * @return List of current bubble particles
     */
    public List<BubbleParticle> getBubbles() {
        // Convert from new particle system to legacy format
        return convertToLegacyBubbles(Water.getBubbles());
    }

    /**
     * Gets current water ripples for rendering.
     * Delegates to Water controller.
     *
     * @return List of current water ripples
     */
    public List<WaterRipple> getRipples() {
        // Convert from new wave system to legacy format
        return convertToLegacyRipples(Water.getRipples());
    }

    // === CONVERSION METHODS FOR BACKWARD COMPATIBILITY ===

    /**
     * Converts new particle system particles to legacy format.
     */
    private List<WaterParticle> convertToLegacyParticles(List<WaterParticles.WaterParticle> newParticles) {
        return newParticles.stream().map(p -> new WaterParticle(
            p.getPosition(),
            p.getVelocity(),
            p.getLifetime(),
            convertParticleType(p.getType())
        )).toList();
    }

    /**
     * Converts new bubble particles to legacy format.
     */
    private List<BubbleParticle> convertToLegacyBubbles(List<WaterParticles.BubbleParticle> newBubbles) {
        return newBubbles.stream().map(b -> new BubbleParticle(
            b.getPosition(),
            b.getSize(),
            b.getRiseSpeed()
        )).toList();
    }

    /**
     * Converts new ripples to legacy format.
     */
    private List<WaterRipple> convertToLegacyRipples(List<WaveSimulation.WaterRipple> newRipples) {
        return newRipples.stream().map(r -> new WaterRipple(
            r.getCenter(),
            r.getStrength(),
            r.getMaxRadius()
        )).toList();
    }

    /**
     * Converts new particle type to legacy particle type.
     */
    private ParticleType convertParticleType(WaterParticles.ParticleType newType) {
        return switch (newType) {
            case SPRAY -> ParticleType.SPRAY;
            case SPLASH -> ParticleType.SPLASH;
            case DROPLET -> ParticleType.DROPLET;
        };
    }

    // === LEGACY CLASSES FOR BACKWARD COMPATIBILITY ===

    /**
     * Legacy water particle class for backward compatibility.
     * @deprecated Use {@link WaterParticles.WaterParticle} instead.
     */
    @Deprecated
    public static class WaterParticle {
        private final Vector3f position;
        public final Vector3f velocity;
        private float lifetime;
        private final float initialLifetime;
        private final ParticleType type;
        private final float size;

        public WaterParticle(Vector3f position, Vector3f velocity, float lifetime, ParticleType type) {
            this.position = position;
            this.velocity = velocity;
            this.lifetime = lifetime;
            this.initialLifetime = lifetime;
            this.type = type;
            this.size = type == ParticleType.SPLASH ? 0.12f : 0.08f;
        }

        public void update(float deltaTime) {
            // Legacy update method - actual updates handled by new system
            lifetime -= deltaTime;
        }

        public boolean isDead() { return lifetime <= 0; }
        public Vector3f getPosition() { return position; }
        public float getOpacity() { return Math.max(0, lifetime / initialLifetime); }
        public float getSize() { return size * getOpacity(); }
        public ParticleType getType() { return type; }
    }

    /**
     * Legacy bubble particle class for backward compatibility.
     * @deprecated Use {@link WaterParticles.BubbleParticle} instead.
     */
    @Deprecated
    public static class BubbleParticle {
        public final Vector3f position;
        private final float size;
        public final float riseSpeed;
        public float lifetime;

        public BubbleParticle(Vector3f position, float size, float riseSpeed) {
            this.position = position;
            this.size = size;
            this.riseSpeed = riseSpeed;
            this.lifetime = 5.0f;
        }

        public void update(float deltaTime) {
            // Legacy update method - actual updates handled by new system
            lifetime -= deltaTime;
        }

        public Vector3f getPosition() { return position; }
        public float getSize() { return size; }
        public float getOpacity() { return Math.min(1, lifetime); }
    }

    /**
     * Legacy water ripple class for backward compatibility.
     * @deprecated Use {@link WaveSimulation.WaterRipple} instead.
     */
    @Deprecated
    public static class WaterRipple {
        public final Vector3f center;
        public float radius;
        public final float maxRadius;
        public float strength;

        public WaterRipple(Vector3f center, float strength, float maxRadius) {
            this.center = center;
            this.strength = strength;
            this.maxRadius = maxRadius;
            this.radius = 0;
        }

        public void update(float deltaTime) {
            // Legacy update method - actual updates handled by new system
        }

        public boolean isDead() { return radius > maxRadius || strength < 0.01f; }
    }

    /**
     * Legacy particle type enumeration for backward compatibility.
     * @deprecated Use {@link WaterParticles.ParticleType} instead.
     */
    @Deprecated
    public enum ParticleType {
        SPRAY,    // Light mist particles
        SPLASH,   // Heavy splash particles
        DROPLET   // Small water droplets
    }
}