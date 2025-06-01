package com.stonebreak;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.joml.Vector3f;

/**
 * Manages all block drops in the world - updating physics, handling pickups,
 * and rendering.
 */
public class BlockDropManager {
    private List<BlockDrop> drops;
    private World world;
    
    public BlockDropManager(World world) {
        this.world = world;
        this.drops = new ArrayList<>();
    }
    
    /**
     * Creates a new block drop at the specified location.
     */
    public void spawnDrop(float x, float y, float z, int blockTypeId, int quantity) {
        // Check if there's an existing drop nearby to merge with
        for (BlockDrop existingDrop : drops) {
            if (existingDrop.getBlockTypeId() == blockTypeId) {
                float distance = existingDrop.getPosition().distance(x, y, z);
                if (distance <= 1.0f) {
                    // Merge with existing drop
                    existingDrop.setQuantity(existingDrop.getQuantity() + quantity);
                    return;
                }
            }
        }
        
        // Create new drop if no merge possible
        BlockDrop newDrop = new BlockDrop(world, x, y, z, blockTypeId, quantity);
        drops.add(newDrop);
    }
    
    /**
     * Creates a new block drop at the specified location with custom velocity (for throwing).
     */
    public void spawnDropWithVelocity(float x, float y, float z, int blockTypeId, int quantity, Vector3f velocity) {
        // Don't merge drops with custom velocity as they are likely thrown items
        // that should maintain their individual trajectory
        BlockDrop newDrop = new BlockDrop(world, x, y, z, blockTypeId, quantity, velocity);
        drops.add(newDrop);
    }
    
    /**
     * Updates all drops - physics, aging, and pickup checks.
     */
    public void update(float deltaTime) {
        Iterator<BlockDrop> iterator = drops.iterator();
        Player player = Game.getPlayer();
        
        while (iterator.hasNext()) {
            BlockDrop drop = iterator.next();
            
            // Update drop physics and animation
            drop.update(deltaTime);
            
            // Check for despawn
            if (drop.shouldDespawn()) {
                iterator.remove();
                continue;
            }
            
            // Check for pickup
            if (player != null && drop.canPickup(player)) {
                // Try to add to player inventory
                Inventory inventory = player.getInventory();
                if (inventory != null) {
                    int addedAmount = inventory.addItemsAndReturnCount(drop.getBlockTypeId(), drop.getQuantity());
                    
                    if (addedAmount > 0) {
                        // Play pickup sound if any items were picked up
                        SoundSystem soundSystem = SoundSystem.getInstance();
                        if (soundSystem != null) {
                            soundSystem.playSoundWithVolume("blockpickup", 0.3f); // Block pickup sound
                        }
                        
                        // Remove the drop or reduce its quantity
                        if (addedAmount >= drop.getQuantity()) {
                            iterator.remove();
                        } else {
                            drop.setQuantity(drop.getQuantity() - addedAmount);
                        }
                    }
                }
            }
        }
        
        // Merge nearby drops of the same type
        mergeNearbyDrops();
    }
    
    /**
     * Merges drops of the same type that are close to each other.
     */
    private void mergeNearbyDrops() {
        for (int i = 0; i < drops.size(); i++) {
            BlockDrop drop1 = drops.get(i);
            
            for (int j = i + 1; j < drops.size(); j++) {
                BlockDrop drop2 = drops.get(j);
                
                if (drop1.mergeWith(drop2)) {
                    drops.remove(j);
                    j--; // Adjust index after removal
                }
            }
        }
    }
    
    /**
     * Gets all drops for rendering.
     */
    public List<BlockDrop> getDrops() {
        return new ArrayList<>(drops);
    }
    
    /**
     * Gets the number of active drops.
     */
    public int getDropCount() {
        return drops.size();
    }
    
    /**
     * Clears all drops (useful for world reset).
     */
    public void clearAllDrops() {
        drops.clear();
    }
    
    /**
     * Removes drops in a specific area (useful for chunk unloading).
     */
    public void removeDropsInArea(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        drops.removeIf(drop -> {
            float x = drop.getPosition().x;
            float y = drop.getPosition().y;
            float z = drop.getPosition().z;
            
            return x >= minX && x <= maxX && 
                   y >= minY && y <= maxY && 
                   z >= minZ && z <= maxZ;
        });
    }
}