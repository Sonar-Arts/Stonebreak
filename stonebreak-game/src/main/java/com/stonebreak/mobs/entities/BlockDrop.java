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
    
    // Visual compression for nearby same-type drops
    private static final float COMPRESSION_RANGE = 2.0f; // Range to compress drops
    private int stackCount = 1; // How many items this drop represents visually
    private boolean isCompressed = false; // Whether this drop is part of a compressed group
    private BlockDrop parentDrop = null; // Parent drop if this one is hidden
    
    // Physics constants for drops (reworked for moderately floaty effect)
    private static final float DROP_GRAVITY = 12.0f; // Moderate downward acceleration
    private static final float DROP_AIR_RESISTANCE = 0.995f; // Much less air resistance than default 0.98f
    private static final float DROP_BOUNCE = 0.4f; // Slightly more bouncy
    private static final float DROP_FRICTION = 0.9f; // Less friction when on ground
    
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
        
        // Update visual compression with nearby drops
        updateCompression();
        
        // Prevent drops from falling into the void
        if (position.y < -10) {
            alive = false;
        }
    }
    
    /**
     * Applies physics specific to dropped items with custom floaty behavior.
     */
    private void applyDropPhysics(float deltaTime) {
        age += deltaTime;
        
        // Apply custom light gravity for extremely floaty effect
        if (!onGround && !inWater) {
            velocity.y -= DROP_GRAVITY * deltaTime;
        }
        
        // Apply minimal air resistance for floaty movement
        velocity.mul(DROP_AIR_RESISTANCE);
        
        // Update position based on velocity
        Vector3f movement = new Vector3f(velocity).mul(deltaTime);
        Vector3f oldPosition = new Vector3f(position);
        position.add(movement);
        
        // Simple collision detection with world
        checkWorldCollision(oldPosition);
        
        // Apply reduced friction if on ground
        if (onGround) {
            velocity.x *= DROP_FRICTION;
            velocity.z *= DROP_FRICTION;
            
            // Stop very small movements (lower threshold for floaty effect)
            if (Math.abs(velocity.x) < 0.005f) velocity.x = 0;
            if (Math.abs(velocity.z) < 0.005f) velocity.z = 0;
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
                
                // Custom bounce effect for floaty drops
                if (velocity.y < 0) {
                    velocity.y = -velocity.y * DROP_BOUNCE;
                    if (Math.abs(velocity.y) < 0.2f) {
                        velocity.y = 0; // Stop small bounces (lower threshold)
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
        
        // Get player from game instance
        com.stonebreak.core.Game game = com.stonebreak.core.Game.getInstance();
        if (game == null) return;
        
        com.stonebreak.player.Player player = game.getPlayer();
        if (player == null) return;
        
        // Check if player is within pickup range
        Vector3f playerPosition = player.getPosition();
        float distance = position.distance(playerPosition);
        
        if (distance <= PICKUP_RANGE) {
            // Try to add item(s) to player inventory
            com.stonebreak.items.Inventory inventory = player.getInventory();
            if (inventory != null) {
                // Create ItemStack for this block (including compressed count)
                com.stonebreak.items.ItemStack itemStack = new com.stonebreak.items.ItemStack(blockType, stackCount);
                
                // Try to add to inventory
                if (inventory.addItem(itemStack)) {
                    // Successfully picked up - play sound and mark as dead
                    com.stonebreak.audio.SoundSystem soundSystem = game.getSoundSystem();
                    if (soundSystem != null) {
                        soundSystem.playSound("blockpickup");
                    }
                    alive = false;
                } else {
                    // Try to add as many as possible
                    int addedCount = 0;
                    for (int i = 0; i < stackCount; i++) {
                        com.stonebreak.items.ItemStack singleItem = new com.stonebreak.items.ItemStack(blockType, 1);
                        if (inventory.addItem(singleItem)) {
                            addedCount++;
                        } else {
                            break; // Inventory is full
                        }
                    }
                    
                    if (addedCount > 0) {
                        // Play sound for partial pickup
                        com.stonebreak.audio.SoundSystem soundSystem = game.getSoundSystem();
                        if (soundSystem != null) {
                            soundSystem.playSound("blockpickup");
                        }
                        
                        stackCount -= addedCount;
                        if (stackCount <= 0) {
                            alive = false;
                        }
                    }
                }
                // If inventory is full, drop stays in world
            }
        }
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
     * Attempts to pick up this drop and add it to a player's inventory.
     * Returns true if successful, false if inventory is full or other issues.
     */
    public boolean pickup(com.stonebreak.player.Player player) {
        if (player == null || !alive) {
            return false;
        }
        
        com.stonebreak.items.Inventory inventory = player.getInventory();
        if (inventory == null) {
            return false;
        }
        
        // Create ItemStack for this block
        com.stonebreak.items.ItemStack itemStack = new com.stonebreak.items.ItemStack(blockType, 1);
        
        // Try to add to inventory
        if (inventory.addItem(itemStack)) {
            // Successfully picked up - play sound and mark as dead
            com.stonebreak.core.Game game = com.stonebreak.core.Game.getInstance();
            if (game != null) {
                com.stonebreak.audio.SoundSystem soundSystem = game.getSoundSystem();
                if (soundSystem != null) {
                    soundSystem.playSound("blockpickup");
                }
            }
            alive = false;
            return true;
        }
        
        return false; // Inventory was full
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
    
    /**
     * Updates visual compression by checking for nearby drops of the same type.
     */
    private void updateCompression() {
        if (world == null || isCompressed) return;
        
        // Get entity manager to find nearby drops
        com.stonebreak.core.Game game = com.stonebreak.core.Game.getInstance();
        if (game == null) return;
        
        com.stonebreak.mobs.entities.EntityManager entityManager = game.getEntityManager();
        if (entityManager == null) return;
        
        // Reset stack count
        stackCount = 1;
        
        // Find nearby drops of the same type
        java.util.List<Entity> entities = entityManager.getAllEntities();
        for (Entity entity : entities) {
            if (entity instanceof BlockDrop otherDrop && entity != this && entity.isAlive()) {
                if (otherDrop.blockType == this.blockType && !otherDrop.isCompressed) {
                    float distance = position.distance(otherDrop.position);
                    if (distance <= COMPRESSION_RANGE) {
                        // Compress the other drop into this one
                        otherDrop.isCompressed = true;
                        otherDrop.parentDrop = this;
                        stackCount++;
                    }
                }
            }
        }
    }
    
    /**
     * Gets the visual stack count for rendering.
     */
    public int getStackCount() {
        return stackCount;
    }
    
    /**
     * Returns true if this drop should be rendered (not compressed into another).
     */
    public boolean shouldRender() {
        return !isCompressed;
    }
    
    /**
     * When this drop is picked up, handle distributing the compressed drops.
     */
    @Override
    protected void onDeath() {
        // If this drop had compressed drops, handle their pickup too
        if (stackCount > 1) {
            com.stonebreak.core.Game game = com.stonebreak.core.Game.getInstance();
            if (game != null) {
                com.stonebreak.mobs.entities.EntityManager entityManager = game.getEntityManager();
                if (entityManager != null) {
                    // Find and remove all compressed drops
                    java.util.List<Entity> entities = new java.util.ArrayList<>(entityManager.getAllEntities());
                    for (Entity entity : entities) {
                        if (entity instanceof BlockDrop otherDrop && otherDrop.parentDrop == this) {
                            otherDrop.alive = false;
                        }
                    }
                }
            }
        }
        
        super.onDeath();
    }
}