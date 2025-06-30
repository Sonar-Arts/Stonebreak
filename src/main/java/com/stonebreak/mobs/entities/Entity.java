package com.stonebreak.mobs.entities;

import org.joml.Vector3f;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.world.World;

/**
 * Base class for all living entities in the game world.
 * Provides fundamental properties and behaviors that all entities share.
 */
public abstract class Entity {
    // Transform and physics
    protected Vector3f position;
    protected Vector3f velocity; 
    protected Vector3f rotation;
    protected Vector3f scale;
    
    // Entity properties
    protected float health;
    protected float maxHealth;
    protected boolean onGround;
    protected boolean inWater;
    protected boolean alive;
    protected float age;
    
    // Physics constants
    protected static final float GRAVITY = -20.0f;
    protected static final float FRICTION = 0.95f;
    protected static final float AIR_RESISTANCE = 0.98f;
    protected static final float BOUNCE_DAMPENING = 0.6f;
    
    // World reference
    protected World world;
    
    // Entity dimensions (bounding box)
    protected float width;
    protected float height;
    protected float length;
    
    /**
     * Creates a new entity at the specified position.
     */
    public Entity(World world, Vector3f position) {
        this.world = world;
        this.position = new Vector3f(position);
        this.velocity = new Vector3f(0, 0, 0);
        this.rotation = new Vector3f(0, 0, 0);
        this.scale = new Vector3f(1, 1, 1);
        this.health = 10.0f;
        this.maxHealth = 10.0f;
        this.onGround = false;
        this.inWater = false;
        this.alive = true;
        this.age = 0.0f;
        this.width = 1.0f;
        this.height = 1.0f;
        this.length = 1.0f;
    }
    
    /**
     * Updates the entity's state, physics, and behavior.
     */
    public abstract void update(float deltaTime);
    
    /**
     * Renders the entity using the provided renderer.
     */
    public abstract void render(Renderer renderer);
    
    /**
     * Gets the entity's type identifier.
     */
    public abstract EntityType getType();
    
    /**
     * Gets the entity's bounding box for collision detection.
     * For living entities, the position represents body bottom and collision starts from leg bottom.
     * For other entities, the position represents the bottom center.
     */
    public BoundingBox getBoundingBox() {
        float bottomY = position.y;
        
        // For living entities, start collision from bottom of legs
        if (this instanceof LivingEntity livingEntity) {
            bottomY = position.y - livingEntity.getLegHeight();
        }
        
        return new BoundingBox(
            position.x - width / 2.0f,
            bottomY, // Start from leg bottom for living entities
            position.z - length / 2.0f,
            position.x + width / 2.0f,
            position.y + height,
            position.z + length / 2.0f
        );
    }
    
    /**
     * Applies basic physics to the entity.
     */
    protected void applyPhysics(float deltaTime) {
        age += deltaTime;
        
        // Apply gravity if not on ground
        if (!onGround && !inWater) {
            velocity.y += GRAVITY * deltaTime;
        }
        
        // Apply air resistance
        velocity.mul(AIR_RESISTANCE);
        
        // Update position based on velocity
        Vector3f movement = new Vector3f(velocity).mul(deltaTime);
        position.add(movement);
        
        // Apply friction if on ground
        if (onGround) {
            velocity.x *= FRICTION;
            velocity.z *= FRICTION;
        }
        
        // Stop very small movements
        if (Math.abs(velocity.x) < 0.01f) velocity.x = 0;
        if (Math.abs(velocity.z) < 0.01f) velocity.z = 0;
    }
    
    /**
     * Called when the entity takes damage.
     */
    public void damage(float amount) {
        if (!alive) return;
        
        health -= amount;
        if (health <= 0) {
            health = 0;
            alive = false;
            onDeath();
        }
    }
    
    /**
     * Heals the entity by the specified amount.
     */
    public void heal(float amount) {
        if (!alive) return;
        
        health = Math.min(health + amount, maxHealth);
    }
    
    /**
     * Called when the entity dies.
     */
    protected void onDeath() {
        // Override in subclasses
    }
    
    /**
     * Checks if the entity is at the specified position (within range).
     */
    public boolean isAt(Vector3f targetPosition, float range) {
        return position.distance(targetPosition) <= range;
    }
    
    /**
     * Gets the distance to another entity.
     */
    public float distanceTo(Entity other) {
        return position.distance(other.position);
    }
    
    /**
     * Gets the distance to a position.
     */
    public float distanceTo(Vector3f targetPosition) {
        return position.distance(targetPosition);
    }
    
    // Getters
    public Vector3f getPosition() { return new Vector3f(position); }
    public Vector3f getVelocity() { return new Vector3f(velocity); }
    public Vector3f getRotation() { return new Vector3f(rotation); }
    public Vector3f getScale() { return new Vector3f(scale); }
    public float getHealth() { return health; }
    public float getMaxHealth() { return maxHealth; }
    public boolean isOnGround() { return onGround; }
    public boolean isInWater() { return inWater; }
    public boolean isAlive() { return alive; }
    public float getAge() { return age; }
    public World getWorld() { return world; }
    public float getWidth() { return width; }
    public float getHeight() { return height; }
    public float getLength() { return length; }
    
    // Setters
    public void setPosition(Vector3f position) { this.position.set(position); }
    public void setVelocity(Vector3f velocity) { this.velocity.set(velocity); }
    public void setRotation(Vector3f rotation) { this.rotation.set(rotation); }
    public void setScale(Vector3f scale) { this.scale.set(scale); }
    public void setHealth(float health) { this.health = Math.min(health, maxHealth); }
    public void setMaxHealth(float maxHealth) { this.maxHealth = maxHealth; }
    public void setOnGround(boolean onGround) { this.onGround = onGround; }
    public void setInWater(boolean inWater) { this.inWater = inWater; }
    public void setAlive(boolean alive) { this.alive = alive; }
    
    /**
     * Simple bounding box class for collision detection.
     */
    public static class BoundingBox {
        public final float minX, minY, minZ;
        public final float maxX, maxY, maxZ;
        
        public BoundingBox(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }
        
        /**
         * Checks if this bounding box intersects with another.
         */
        public boolean intersects(BoundingBox other) {
            return minX < other.maxX && maxX > other.minX &&
                   minY < other.maxY && maxY > other.minY &&
                   minZ < other.maxZ && maxZ > other.minZ;
        }
        
        /**
         * Checks if a point is within this bounding box.
         */
        public boolean contains(Vector3f point) {
            return point.x >= minX && point.x <= maxX &&
                   point.y >= minY && point.y <= maxY &&
                   point.z >= minZ && point.z <= maxZ;
        }
    }
}