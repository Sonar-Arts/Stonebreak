package com.stonebreak.mobs.entities;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.world.World;
import org.joml.Vector3f;

/**
 * Entity representing a dropped block that can be picked up by the player.
 * Uses 3D block rendering through the DropRenderer system.
 */
public class BlockDrop extends Entity {
    
    private final BlockType blockType;
    private float despawnTimer;
    private static final float DESPAWN_TIME = 300.0f; // 5 minutes
    private static final float PICKUP_RANGE = 1.5f;
    
    // Physics constants for drops
    private static final float DROP_BOUNCE = 0.3f;
    private static final float DROP_FRICTION = 0.8f;
    
    /**
     * Creates a new block drop at the specified position.
     */
    public BlockDrop(World world, Vector3f position, BlockType blockType) {
        super(world, position);
        this.blockType = blockType;
        this.despawnTimer = DESPAWN_TIME;
        
        // Set drop-specific properties from EntityType
        EntityType dropType = EntityType.BLOCK_DROP;
        this.health = dropType.getMaxHealth();
        this.maxHealth = dropType.getMaxHealth();
        this.width = dropType.getWidth();
        this.height = dropType.getHeight();
        this.length = dropType.getLength();
        
        // Add initial velocity for realistic drop behavior
        this.velocity.set(
            (float)(Math.random() - 0.5) * 2.0f, // Random X velocity
            2.0f,                                  // Upward Y velocity
            (float)(Math.random() - 0.5) * 2.0f   // Random Z velocity
        );
    }
    
    /**
     * Updates the block drop's physics, despawn timer, and pickup detection.
     */
    @Override
    public void update(float deltaTime) {
        if (!alive) return;
        
        // Update despawn timer
        despawnTimer -= deltaTime;
        if (despawnTimer <= 0) {
            alive = false;
            return;
        }
        
        // Apply physics with drop-specific modifications
        applyDropPhysics(deltaTime);
        
        // Check for player pickup
        checkPlayerPickup();
        
        // Prevent drops from falling into the void
        if (position.y < -10) {
            alive = false;
        }
    }
    
    /**
     * Applies physics specific to dropped items with bouncing and friction.
     */
    private void applyDropPhysics(float deltaTime) {
        age += deltaTime;
        
        // Apply gravity if not on ground
        if (!onGround && !inWater) {
            velocity.y += GRAVITY * deltaTime;
        }
        
        // Apply air resistance
        velocity.mul(AIR_RESISTANCE);
        
        // Update position based on velocity
        Vector3f movement = new Vector3f(velocity).mul(deltaTime);
        Vector3f oldPosition = new Vector3f(position);
        position.add(movement);
        
        // Simple collision detection with world
        checkWorldCollision(oldPosition);
        
        // Apply friction if on ground
        if (onGround) {
            velocity.x *= DROP_FRICTION;
            velocity.z *= DROP_FRICTION;
            
            // Stop very small movements
            if (Math.abs(velocity.x) < 0.01f) velocity.x = 0;
            if (Math.abs(velocity.z) < 0.01f) velocity.z = 0;
        }
    }
    
    /**
     * Simple collision detection with the world.
     */
    private void checkWorldCollision(Vector3f oldPosition) {
        // Check if we hit the ground (simplified)
        int blockX = (int) Math.floor(position.x);
        int blockY = (int) Math.floor(position.y - height/2);
        int blockZ = (int) Math.floor(position.z);
        
        // Check if there's a solid block below
        if (world != null) {
            BlockType blockBelow = world.getBlockAt(blockX, blockY, blockZ);
            if (blockBelow != null && blockBelow != BlockType.AIR && blockBelow != BlockType.WATER) {
                onGround = true;
                position.y = blockY + 1.0f + height/2; // Place on top of block
                
                // Bounce effect
                if (velocity.y < 0) {
                    velocity.y = -velocity.y * DROP_BOUNCE;
                    if (Math.abs(velocity.y) < 0.5f) {
                        velocity.y = 0; // Stop small bounces
                    }
                }
            } else {
                onGround = false;
            }
        }
    }
    
    /**
     * Checks if the player is close enough to pick up this drop.
     */
    private void checkPlayerPickup() {
        if (world == null) return;
        
        // TODO: Implement player pickup logic
        // This would need access to the player instance and inventory system
        // For now, this is a placeholder that would be implemented when
        // the full pickup system is created
    }
    
    /**
     * Block drops don't render themselves directly - they use the DropRenderer system.
     */
    @Override
    public void render(Renderer renderer) {
        // Rendering is handled by DropRenderer in the rendering pipeline
        // This method is kept for Entity interface compliance but is not used
    }
    
    @Override
    public EntityType getType() {
        return EntityType.BLOCK_DROP;
    }
    
    /**
     * Gets the block type that this drop represents.
     */
    public BlockType getBlockType() {
        return blockType;
    }
    
    /**
     * Gets the remaining despawn time in seconds.
     */
    public float getRemainingTime() {
        return despawnTimer;
    }
    
    /**
     * Checks if this drop can be picked up by a player at the given position.
     */
    public boolean canPickup(Vector3f playerPosition) {
        return position.distance(playerPosition) <= PICKUP_RANGE;
    }
    
    /**
     * Attempts to pick up this drop and add it to an inventory.
     * Returns true if successful, false if inventory is full or other issues.
     */
    public boolean pickup() {
        // TODO: Implement actual pickup logic with inventory system
        // For now, just mark as dead
        alive = false;
        return true;
    }
    
    @Override
    protected void onDeath() {
        // Clean up any resources if needed
        super.onDeath();
    }
    
    /**
     * Factory method to create a block drop with initial physics.
     */
    public static BlockDrop createDrop(World world, Vector3f position, BlockType blockType) {
        return new BlockDrop(world, position, blockType);
    }
    
    /**
     * Factory method to create a block drop with custom initial velocity.
     */
    public static BlockDrop createDropWithVelocity(World world, Vector3f position, BlockType blockType, Vector3f initialVelocity) {
        BlockDrop drop = new BlockDrop(world, position, blockType);
        drop.velocity.set(initialVelocity);
        return drop;
    }
}