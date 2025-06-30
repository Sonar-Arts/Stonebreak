package com.stonebreak.blocks;

import org.joml.Vector3f;
import com.stonebreak.world.World;
import com.stonebreak.player.Player;
import com.stonebreak.core.Game;

/**
 * Represents a 3D block drop entity that appears when blocks are broken.
 * Mimics Minecraft's item drop behavior with physics, spinning animation,
 * and pickup mechanics.
 */
public class BlockDrop {
    // Drop physics constants
    private static final float GRAVITY = -20.0f;
    private static final float BOUNCE_DAMPENING = 0.6f;
    private static final float FLOAT_HEIGHT = 0.25f; // How high above ground to float
    private static final float SPIN_SPEED = 120.0f; // degrees per second
    private static final float PICKUP_RANGE = 0.8f;   // Further reduced for testing
    private static final float ATTRACTION_RANGE = 1.5f; // Further reduced
    private static final float ATTRACTION_FORCE = 8.0f; // Further reduced
    private static final float DESPAWN_TIME = 300.0f; // 5 minutes in seconds
    private static final float MIN_VELOCITY = 0.01f; // Minimum velocity before stopping
    private static final float GROUND_FRICTION = 0.95f;
    private static final float AIR_RESISTANCE = 0.98f;
    
    // Drop properties
    private Vector3f position;
    private Vector3f velocity;
    private float rotationY; // Y-axis rotation in degrees
    private int blockTypeId;
    private int quantity;
    private float age; // How long this drop has existed
    private boolean onGround;
    private World world;
    
    // Visual properties
    private static final float DROP_SCALE = 0.25f; // 25% of block size
    
    public BlockDrop(World world, float x, float y, float z, int blockTypeId, int quantity) {
        this.world = world;
        this.position = new Vector3f(x, y, z);
        this.velocity = new Vector3f(
            (float)(Math.random() - 0.5) * 4.0f, // Random horizontal velocity
            2.0f + (float)Math.random() * 2.0f,   // Upward velocity (2-4)
            (float)(Math.random() - 0.5) * 4.0f  // Random horizontal velocity
        );
        this.rotationY = (float)(Math.random() * 360.0); // Random initial rotation
        this.blockTypeId = blockTypeId;
        this.quantity = quantity;
        this.age = 0.0f;
        this.onGround = false;
    }
    
    public BlockDrop(World world, float x, float y, float z, int blockTypeId, int quantity, Vector3f initialVelocity) {
        this.world = world;
        this.position = new Vector3f(x, y, z);
        this.velocity = new Vector3f(initialVelocity); // Use provided velocity
        this.rotationY = (float)(Math.random() * 360.0); // Random initial rotation
        this.blockTypeId = blockTypeId;
        this.quantity = quantity;
        this.age = 0.0f;
        this.onGround = false;
    }
    
    /**
     * Updates the drop's physics, animation, and lifetime.
     */
    public void update(float deltaTime) {
        age += deltaTime;
        
        // Check if should despawn
        if (age >= DESPAWN_TIME) {
            return; // Will be removed by the manager
        }
        
        // Update rotation
        rotationY += SPIN_SPEED * deltaTime;
        if (rotationY >= 360.0f) {
            rotationY -= 360.0f;
        }
        
        // Apply gravity if not on ground
        if (!onGround) {
            velocity.y += GRAVITY * deltaTime;
        }
        
        // Check for player attraction
        Player player = Game.getPlayer();
        if (player != null) {
            Vector3f playerPos = player.getPosition();
            float distanceToPlayer = position.distance(playerPos);
            
            // Attraction to player when close
            if (distanceToPlayer <= ATTRACTION_RANGE && distanceToPlayer > PICKUP_RANGE) {
                Vector3f attraction = new Vector3f(playerPos).sub(position).normalize();
                float attractionStrength = ATTRACTION_FORCE / (distanceToPlayer * distanceToPlayer);
                velocity.add(attraction.mul(attractionStrength * deltaTime));
            }
        }
        
        // Apply air resistance
        velocity.mul(AIR_RESISTANCE);
        
        // Update position
        Vector3f oldPosition = new Vector3f(position);
        position.add(new Vector3f(velocity).mul(deltaTime));
        
        // Ground collision detection
        float groundY = getGroundHeight(position.x, position.z);
        if (position.y <= groundY + FLOAT_HEIGHT) {
            if (!onGround && velocity.y < 0) {
                // Just hit the ground, bounce
                velocity.y = Math.abs(velocity.y) * BOUNCE_DAMPENING;
                
                // If bounce is too small, stop bouncing
                if (velocity.y < 1.0f) {
                    velocity.y = 0;
                    onGround = true;
                }
            }
            
            // Keep floating at correct height
            position.y = groundY + FLOAT_HEIGHT;
            
            // Apply ground friction if on ground
            if (onGround) {
                velocity.x *= GROUND_FRICTION;
                velocity.z *= GROUND_FRICTION;
                
                // Stop very small movements
                if (Math.abs(velocity.x) < MIN_VELOCITY) velocity.x = 0;
                if (Math.abs(velocity.z) < MIN_VELOCITY) velocity.z = 0;
            }
        } else {
            onGround = false;
        }
        
        // Wall collision - basic implementation
        handleWallCollisions(oldPosition);
    }
    
    /**
     * Gets the ground height at the given x,z coordinates.
     * Searches downward from the drop's current position to find the first solid surface.
     */
    private float getGroundHeight(float x, float z) {
        int blockX = (int)Math.floor(x);
        int blockZ = (int)Math.floor(z);
        
        // Start searching from the drop's current Y position downward
        int startY = Math.min(255, (int)Math.floor(position.y));
        
        // Find the first solid block below the drop
        for (int y = startY; y >= 0; y--) {
            BlockType blockType = world.getBlockAt(blockX, y, blockZ);
            if (blockType != BlockType.AIR && blockType != BlockType.WATER) {
                return y + 1.0f; // Top surface of the block
            }
        }
        
        return 0.0f; // Bedrock level
    }
    
    /**
     * Handles basic wall collision by reverting position if inside a solid block.
     */
    private void handleWallCollisions(Vector3f oldPosition) {
        int blockX = (int)Math.floor(position.x);
        int blockY = (int)Math.floor(position.y);
        int blockZ = (int)Math.floor(position.z);
        
        BlockType blockType = world.getBlockAt(blockX, blockY, blockZ);
        if (blockType != BlockType.AIR && blockType != BlockType.WATER) {
            // Revert to old position and dampen velocity
            position.set(oldPosition);
            velocity.x *= -0.5f;
            velocity.z *= -0.5f;
        }
    }
    
    /**
     * Checks if this drop can be picked up by the player.
     */
    public boolean canPickup(Player player) {
        if (player == null) return false;
        
        Vector3f playerPos = player.getPosition();
        float distance = position.distance(playerPos);
        
        return distance <= PICKUP_RANGE && age > 1.0f; // 1 second delay before pickup
    }
    
    /**
     * Attempts to merge this drop with another of the same type.
     * Returns true if merge was successful.
     */
    public boolean mergeWith(BlockDrop other) {
        if (other.blockTypeId == this.blockTypeId && 
            position.distance(other.position) <= 1.0f) {
            this.quantity += other.quantity;
            return true;
        }
        return false;
    }
    
    // Getters
    public Vector3f getPosition() { return new Vector3f(position); }
    public float getRotationY() { return rotationY; }
    public int getBlockTypeId() { return blockTypeId; }
    public int getQuantity() { return quantity; }
    public float getAge() { return age; }
    public boolean shouldDespawn() { return age >= DESPAWN_TIME; }
    public float getScale() { return DROP_SCALE; }
    
    // Setters
    public void setQuantity(int quantity) { this.quantity = quantity; }
}