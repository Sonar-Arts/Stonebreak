package com.stonebreak;

import org.joml.Vector3f;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Manages water particles and effects.
 */
public class WaterEffects {
    private static final int MAX_PARTICLES = 50;
    private static final float PARTICLE_LIFETIME = 1.5f; // seconds
    private static final float PARTICLE_SPAWN_INTERVAL = 0.2f; // seconds
    private static final float PARTICLE_SIZE = 0.05f;
    
    private List<WaterParticle> particles;
    private Random random;
    private float timeSinceLastSpawn;
    
    public WaterEffects() {
        this.particles = new ArrayList<>();
        this.random = new Random();
        this.timeSinceLastSpawn = 0.0f;
    }
    
    /**
     * Updates all water particles and creates new ones if the player is moving in water.
     * @param player The player
     * @param deltaTime Time since last update
     */
    public void update(Player player, float deltaTime) {
        // Only spawn particles if the player is in water and moving
        if (player.isInWater() && (
            Math.abs(player.getVelocity().x) > 1.0f || 
            Math.abs(player.getVelocity().z) > 1.0f || 
            Math.abs(player.getVelocity().y) > 1.0f)) {
            
            timeSinceLastSpawn += deltaTime;
            
            // Spawn new particles at an interval
            if (timeSinceLastSpawn >= PARTICLE_SPAWN_INTERVAL && particles.size() < MAX_PARTICLES) {
                spawnParticle(player);
                timeSinceLastSpawn = 0.0f;
            }
        }
        
        // Update existing particles
        Iterator<WaterParticle> iterator = particles.iterator();
        while (iterator.hasNext()) {
            WaterParticle particle = iterator.next();
            particle.update(deltaTime);
            
            // Remove expired particles
            if (particle.getLifetime() <= 0) {
                iterator.remove();
            }
        }
    }
    
    /**
     * Spawns a water particle at the player's position.
     */
    private void spawnParticle(Player player) {
        Vector3f position = new Vector3f(player.getPosition());
        
        // Randomize position slightly around the player
        position.x += (random.nextFloat() - 0.5f) * 0.5f;
        position.y += random.nextFloat() * 0.5f; // Mostly above the player's feet
        position.z += (random.nextFloat() - 0.5f) * 0.5f;
        
        // Create a velocity that moves slightly upward and in a random direction
        Vector3f velocity = new Vector3f(
            (random.nextFloat() - 0.5f) * 0.5f,
            random.nextFloat() * 0.5f + 0.2f, // Mostly upward
            (random.nextFloat() - 0.5f) * 0.5f
        );
        
        WaterParticle particle = new WaterParticle(position, velocity, PARTICLE_LIFETIME);
        particles.add(particle);
    }
    
    /**
     * Gets all active water particles.
     */
    public List<WaterParticle> getParticles() {
        return particles;
    }
    
    /**
     * Represents a single water particle.
     */
    public static class WaterParticle {
        private Vector3f position;
        private Vector3f velocity;
        private float lifetime;
        private float initialLifetime;
        
        public WaterParticle(Vector3f position, Vector3f velocity, float lifetime) {
            this.position = position;
            this.velocity = velocity;
            this.lifetime = lifetime;
            this.initialLifetime = lifetime;
        }
        
        public void update(float deltaTime) {
            // Update position based on velocity
            position.x += velocity.x * deltaTime;
            position.y += velocity.y * deltaTime;
            position.z += velocity.z * deltaTime;
            
            // Slow down over time
            velocity.mul(0.95f);
            
            // Reduce lifetime
            lifetime -= deltaTime;
        }
        
        public Vector3f getPosition() {
            return position;
        }
        
        public float getLifetime() {
            return lifetime;
        }
        
        public float getSize() {
            return PARTICLE_SIZE;
        }
        
        /**
         * Gets the opacity of the particle based on its remaining lifetime.
         * Fades out as lifetime decreases.
         */
        public float getOpacity() {
            return lifetime / initialLifetime;
        }
    }
}
