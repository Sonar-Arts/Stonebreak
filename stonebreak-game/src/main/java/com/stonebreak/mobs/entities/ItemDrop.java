package com.stonebreak.mobs.entities;

import com.stonebreak.items.ItemType;
import com.stonebreak.items.ItemStack;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.world.World;
import org.joml.Vector3f;

/**
 * Entity representing a dropped item that can be picked up by the player.
 * Uses 3D sprite rendering through the DropRenderer system.
 */
public class ItemDrop extends Entity {
    
    private final ItemStack itemStack;
    private float despawnTimer;
    private static final float DESPAWN_TIME = 300.0f; // 5 minutes
    private static final float PICKUP_RANGE = 1.5f;
    
    // Physics constants for drops
    private static final float DROP_BOUNCE = 0.3f;
    private static final float DROP_FRICTION = 0.8f;
    
    /**
     * Creates a new item drop at the specified position.
     */
    public ItemDrop(World world, Vector3f position, ItemStack itemStack) {
        super(world, position);
        this.itemStack = itemStack;
        this.despawnTimer = DESPAWN_TIME;
        
        // Set drop-specific properties from EntityType
        EntityType dropType = EntityType.ITEM_DROP;
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
     * Creates a new item drop for a single item type.
     */
    public ItemDrop(World world, Vector3f position, ItemType itemType, int count) {
        this(world, position, new ItemStack(itemType, count));
    }
    
    /**
     * Updates the item drop's physics, despawn timer, and pickup detection.
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
            com.stonebreak.blocks.BlockType blockBelow = world.getBlockAt(blockX, blockY, blockZ);
            if (blockBelow != null && blockBelow != com.stonebreak.blocks.BlockType.AIR && blockBelow != com.stonebreak.blocks.BlockType.WATER) {
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
     * Item drops don't render themselves directly - they use the DropRenderer system.
     */
    @Override
    public void render(Renderer renderer) {
        // Rendering is handled by DropRenderer in the rendering pipeline
        // This method is kept for Entity interface compliance but is not used
    }
    
    @Override
    public EntityType getType() {
        return EntityType.ITEM_DROP;
    }
    
    /**
     * Gets the item stack that this drop represents.
     */
    public ItemStack getItemStack() {
        return itemStack;
    }
    
    /**
     * Gets the item type from the item stack.
     */
    public ItemType getItemType() {
        return itemStack != null ? itemStack.asItemType() : null;
    }
    
    /**
     * Gets the count of items in this drop.
     */
    public int getCount() {
        return itemStack != null ? itemStack.getCount() : 0;
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
    
    /**
     * Attempts to merge this drop with another item drop of the same type.
     * Returns true if successful, false if they can't be merged.
     */
    public boolean mergeWith(ItemDrop other) {
        if (other == null || !other.isAlive() || !this.isAlive()) {
            return false;
        }
        
        if (this.itemStack.canStackWith(other.itemStack)) {
            int totalCount = this.itemStack.getCount() + other.itemStack.getCount();
            int maxStack = this.itemStack.getMaxStackSize();
            
            if (totalCount <= maxStack) {
                // Merge completely
                this.itemStack.setCount(totalCount);
                other.alive = false;
                return true;
            } else {
                // Partial merge
                this.itemStack.setCount(maxStack);
                other.itemStack.setCount(totalCount - maxStack);
                return true;
            }
        }
        
        return false;
    }
    
    @Override
    protected void onDeath() {
        // Clean up any resources if needed
        super.onDeath();
    }
    
    /**
     * Factory method to create an item drop.
     */
    public static ItemDrop createDrop(World world, Vector3f position, ItemStack itemStack) {
        return new ItemDrop(world, position, itemStack);
    }
    
    /**
     * Factory method to create an item drop with custom initial velocity.
     */
    public static ItemDrop createDropWithVelocity(World world, Vector3f position, ItemStack itemStack, Vector3f initialVelocity) {
        ItemDrop drop = new ItemDrop(world, position, itemStack);
        drop.velocity.set(initialVelocity);
        return drop;
    }
    
    /**
     * Factory method to create a simple item drop from item type and count.
     */
    public static ItemDrop createDrop(World world, Vector3f position, ItemType itemType, int count) {
        return new ItemDrop(world, position, itemType, count);
    }
}