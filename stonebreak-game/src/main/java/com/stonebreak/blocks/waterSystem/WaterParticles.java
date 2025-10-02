package com.stonebreak.blocks.waterSystem;

import com.stonebreak.player.Player;
import org.joml.Vector3f;

import java.util.*;

/**
 * Handles water particle systems including spray, splash, and bubble particles.
 * Manages particle physics, lifecycle, and visual effects.
 */
public class WaterParticles {

    // Particle system constants
    private static final int MAX_PARTICLES = 200;
    private static final int MAX_SPLASH_PARTICLES = 100;
    private static final float PARTICLE_LIFETIME = 2.0f;
    private static final float SPLASH_LIFETIME = 1.0f;
    private static final float PARTICLE_SPAWN_INTERVAL = 0.05f;
    private static final float PARTICLE_SIZE = 0.08f;
    private static final float BUBBLE_SIZE = 0.05f;

    private final List<WaterParticle> particles;
    private final List<WaterParticle> splashParticles;
    private final List<BubbleParticle> bubbles;
    private float timeSinceLastSpawn;
    private final Random random;

    public WaterParticles() {
        this.particles = Collections.synchronizedList(new ArrayList<>());
        this.splashParticles = Collections.synchronizedList(new ArrayList<>());
        this.bubbles = Collections.synchronizedList(new ArrayList<>());
        this.timeSinceLastSpawn = 0.0f;
        this.random = new Random();
    }

    /**
     * Updates all particle systems.
     *
     * @param player Current player for movement-based effects
     * @param deltaTime Time elapsed since last update
     */
    public void update(Player player, float deltaTime) {
        updateMovementParticles(player, deltaTime);
        updateSplashParticles(deltaTime);
        updateBubbles(deltaTime);
    }

    /**
     * Creates a splash effect when something enters water.
     *
     * @param position Position where the splash occurs
     * @param intensity Intensity of the splash (affects particle count and size)
     */
    public void createSplash(Vector3f position, float intensity) {
        // Create splash particles
        int count = (int)(intensity * 20);
        for (int i = 0; i < count && splashParticles.size() < MAX_SPLASH_PARTICLES; i++) {
            float angle = random.nextFloat() * (float)Math.PI * 2;
            float speed = random.nextFloat() * intensity * 2;

            Vector3f velocity = new Vector3f(
                (float)Math.cos(angle) * speed,
                random.nextFloat() * intensity * 3 + 1,
                (float)Math.sin(angle) * speed
            );

            WaterParticle particle = new WaterParticle(
                new Vector3f(position),
                velocity,
                SPLASH_LIFETIME,
                ParticleType.SPLASH
            );

            splashParticles.add(particle);
        }

        // Spawn bubbles
        for (int i = 0; i < intensity * 5 && bubbles.size() < 100; i++) {
            spawnBubble(position);
        }
    }

    /**
     * Spawns a bubble particle with random properties.
     *
     * @param position Position to spawn the bubble
     */
    public void spawnBubble(Vector3f position) {
        Vector3f pos = new Vector3f(
            position.x + (random.nextFloat() - 0.5f) * 0.5f,
            position.y - random.nextFloat() * 0.5f,
            position.z + (random.nextFloat() - 0.5f) * 0.5f
        );

        BubbleParticle bubble = new BubbleParticle(
            pos,
            BUBBLE_SIZE + random.nextFloat() * 0.03f,
            0.5f + random.nextFloat() * 0.3f
        );

        bubbles.add(bubble);
    }

    /**
     * Gets a copy of current particles for rendering.
     *
     * @return List of current water particles
     */
    public List<WaterParticle> getParticles() {
        return new ArrayList<>(particles);
    }

    /**
     * Gets a copy of current splash particles for rendering.
     *
     * @return List of current splash particles
     */
    public List<WaterParticle> getSplashParticles() {
        return new ArrayList<>(splashParticles);
    }

    /**
     * Gets a copy of current bubbles for rendering.
     *
     * @return List of current bubble particles
     */
    public List<BubbleParticle> getBubbles() {
        return new ArrayList<>(bubbles);
    }

    /**
     * Clears all particles.
     */
    public void clear() {
        particles.clear();
        splashParticles.clear();
        bubbles.clear();
    }

    /**
     * Updates water particles with enhanced physics based on player movement.
     */
    private void updateMovementParticles(Player player, float deltaTime) {
        // Spawn particles based on player movement
        if (player != null && player.isInWater()) {
            Vector3f velocity = player.getVelocity();
            float speed = velocity.length();

            if (speed > 0.5f) {
                timeSinceLastSpawn += deltaTime;

                if (timeSinceLastSpawn >= PARTICLE_SPAWN_INTERVAL && particles.size() < MAX_PARTICLES) {
                    spawnMovementParticles(player, speed);
                    timeSinceLastSpawn = 0.0f;
                }
            }

            // Spawn bubbles occasionally
            if (random.nextFloat() < 0.02f && bubbles.size() < 50) {
                spawnBubble(player.getPosition());
            }
        }

        // Update existing particles
        particles.removeIf(particle -> {
            particle.update(deltaTime);
            return particle.isDead();
        });
    }

    /**
     * Updates splash particles with gravity and spread.
     */
    private void updateSplashParticles(float deltaTime) {
        splashParticles.removeIf(particle -> {
            particle.update(deltaTime);
            particle.velocity.y -= 9.8f * deltaTime; // Gravity
            return particle.isDead();
        });
    }

    /**
     * Updates bubble particles with buoyancy.
     */
    private void updateBubbles(float deltaTime) {
        bubbles.removeIf(bubble -> {
            bubble.update(deltaTime);
            bubble.position.y += bubble.riseSpeed * deltaTime;

            // Check if bubble reached surface - simplified check
            // In full implementation, this would check against the world
            return bubble.lifetime <= 0;
        });
    }

    /**
     * Spawns movement particles with velocity based on player motion.
     */
    private void spawnMovementParticles(Player player, float speed) {
        Vector3f pos = new Vector3f(player.getPosition());
        Vector3f playerVel = player.getVelocity();

        // Spawn multiple particles in a spread pattern
        int count = Math.min(5, (int)(speed * 2));
        for (int i = 0; i < count; i++) {
            Vector3f offset = new Vector3f(
                (random.nextFloat() - 0.5f) * 0.5f,
                random.nextFloat() * 0.3f,
                (random.nextFloat() - 0.5f) * 0.5f
            );

            Vector3f velocity = new Vector3f(
                -playerVel.x * 0.3f + (random.nextFloat() - 0.5f) * 0.5f,
                random.nextFloat() * 0.8f + 0.2f,
                -playerVel.z * 0.3f + (random.nextFloat() - 0.5f) * 0.5f
            );

            WaterParticle particle = new WaterParticle(
                new Vector3f(pos).add(offset),
                velocity,
                PARTICLE_LIFETIME,
                ParticleType.SPRAY
            );

            particles.add(particle);
        }
    }

    /**
     * Creates a small splash effect for bubbles popping.
     */
    private void createSmallSplash(Vector3f position) {
        for (int i = 0; i < 3; i++) {
            Vector3f velocity = new Vector3f(
                (random.nextFloat() - 0.5f) * 0.3f,
                random.nextFloat() * 0.5f + 0.2f,
                (random.nextFloat() - 0.5f) * 0.3f
            );

            WaterParticle particle = new WaterParticle(
                new Vector3f(position),
                velocity,
                0.5f,
                ParticleType.DROPLET
            );

            splashParticles.add(particle);
        }
    }

    /**
     * Enhanced water particle with physics.
     */
    public static class WaterParticle {
        final Vector3f position;
        final Vector3f velocity;
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
            this.size = type == ParticleType.SPLASH ? PARTICLE_SIZE * 1.5f : PARTICLE_SIZE;
        }

        public void update(float deltaTime) {
            position.add(velocity.x * deltaTime, velocity.y * deltaTime, velocity.z * deltaTime);

            // Physics based on type
            switch (type) {
                case SPRAY -> {
                    velocity.mul(0.98f); // Air resistance
                    velocity.y -= 4.0f * deltaTime; // Light gravity
                }
                case SPLASH -> {
                    velocity.mul(0.95f);
                    velocity.y -= 9.8f * deltaTime; // Full gravity
                }
                case DROPLET -> {
                    velocity.mul(0.99f);
                    velocity.y -= 6.0f * deltaTime;
                }
            }

            lifetime -= deltaTime;
        }

        public boolean isDead() { return lifetime <= 0; }
        public Vector3f getPosition() { return position; }
        public Vector3f getVelocity() { return velocity; }
        public float getOpacity() { return Math.max(0, lifetime / initialLifetime); }
        public float getSize() { return size * getOpacity(); }
        public ParticleType getType() { return type; }
        public float getLifetime() { return lifetime; }
        public float getInitialLifetime() { return initialLifetime; }
    }

    /**
     * Bubble particle with buoyancy.
     */
    public static class BubbleParticle {
        final Vector3f position;
        private final float size;
        final float riseSpeed;
        float lifetime;
        private float wobble;

        public BubbleParticle(Vector3f position, float size, float riseSpeed) {
            this.position = position;
            this.size = size;
            this.riseSpeed = riseSpeed;
            this.lifetime = 5.0f;
            this.wobble = (float)(Math.random() * Math.PI * 2);
        }

        public void update(float deltaTime) {
            wobble += deltaTime * 3;
            position.x += Math.sin(wobble) * 0.02f;
            position.z += Math.cos(wobble * 0.7f) * 0.02f;
            lifetime -= deltaTime;
        }

        public Vector3f getPosition() { return position; }
        public float getSize() { return size; }
        public float getOpacity() { return Math.min(1, lifetime); }
        public float getRiseSpeed() { return riseSpeed; }
        public float getLifetime() { return lifetime; }
    }

    /**
     * Particle type enumeration.
     */
    public enum ParticleType {
        SPRAY,    // Light mist particles
        SPLASH,   // Heavy splash particles
        DROPLET   // Small water droplets
    }

    /**
     * Particle system constants for external access.
     */
    public static final class Constants {
        public static final int MAX_PARTICLES = WaterParticles.MAX_PARTICLES;
        public static final int MAX_SPLASH_PARTICLES = WaterParticles.MAX_SPLASH_PARTICLES;
        public static final float PARTICLE_LIFETIME = WaterParticles.PARTICLE_LIFETIME;
        public static final float SPLASH_LIFETIME = WaterParticles.SPLASH_LIFETIME;
        public static final float PARTICLE_SIZE = WaterParticles.PARTICLE_SIZE;
        public static final float BUBBLE_SIZE = WaterParticles.BUBBLE_SIZE;

        private Constants() {} // Utility class
    }
}