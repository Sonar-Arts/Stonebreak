package com.stonebreak.util;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.mobs.entities.BlockDrop;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.ItemDrop;
import com.stonebreak.player.Player;
import com.stonebreak.player.Camera;
import com.stonebreak.world.World;
import org.joml.Vector3f;

/**
 * Utility class for creating and managing item and block drops in the world.
 * Handles the creation of drop entities when blocks are broken or items are dropped.
 */
public class DropUtil {
    
    // Constants for drop physics
    private static final float DROP_SPREAD_RADIUS = 0.5f;
    private static final float DROP_VELOCITY_MIN = 1.0f;
    private static final float DROP_VELOCITY_MAX = 3.0f;
    private static final float DROP_HEIGHT_OFFSET = 0.5f;
    
    /**
     * Creates a block drop at the specified position.
     * Used when blocks are broken by mining.
     */
    public static void createBlockDrop(World world, Vector3f position, BlockType blockType) {
        if (blockType == null || blockType == BlockType.AIR || world == null) {
            return;
        }
        
        // Add some randomness to the drop position
        Vector3f dropPosition = new Vector3f(
            position.x + (float)(Math.random() - 0.5) * DROP_SPREAD_RADIUS,
            position.y + DROP_HEIGHT_OFFSET,
            position.z + (float)(Math.random() - 0.5) * DROP_SPREAD_RADIUS
        );
        
        // Create random initial velocity
        Vector3f initialVelocity = new Vector3f(
            (float)(Math.random() - 0.5) * DROP_VELOCITY_MAX,
            DROP_VELOCITY_MIN + (float)Math.random() * (DROP_VELOCITY_MAX - DROP_VELOCITY_MIN),
            (float)(Math.random() - 0.5) * DROP_VELOCITY_MAX
        );
        
        BlockDrop drop = BlockDrop.createDropWithVelocity(world, dropPosition, blockType, initialVelocity);
        
        // Add to entity manager
        EntityManager entityManager = Game.getEntityManager();
        if (entityManager != null) {
            entityManager.addEntity(drop);
        }
    }
    
    /**
     * Creates multiple block drops for blocks that drop multiple items (like snow layers).
     */
    public static void createBlockDrops(World world, Vector3f position, BlockType blockType, int count) {
        for (int i = 0; i < count; i++) {
            createBlockDrop(world, position, blockType);
        }
    }
    
    /**
     * Creates an item drop at the specified position.
     * Used when items are dropped from inventory or other sources.
     */
    public static void createItemDrop(World world, Vector3f position, ItemStack itemStack) {
        if (itemStack == null || itemStack.isEmpty() || world == null) {
            return;
        }
        
        // Add some randomness to the drop position
        Vector3f dropPosition = new Vector3f(
            position.x + (float)(Math.random() - 0.5) * DROP_SPREAD_RADIUS,
            position.y + DROP_HEIGHT_OFFSET,
            position.z + (float)(Math.random() - 0.5) * DROP_SPREAD_RADIUS
        );
        
        // Create random initial velocity
        Vector3f initialVelocity = new Vector3f(
            (float)(Math.random() - 0.5) * DROP_VELOCITY_MAX,
            DROP_VELOCITY_MIN + (float)Math.random() * (DROP_VELOCITY_MAX - DROP_VELOCITY_MIN),
            (float)(Math.random() - 0.5) * DROP_VELOCITY_MAX
        );
        
        ItemDrop drop = ItemDrop.createDropWithVelocity(world, dropPosition, itemStack, initialVelocity);
        
        // Add to entity manager
        EntityManager entityManager = Game.getEntityManager();
        if (entityManager != null) {
            entityManager.addEntity(drop);
        }
    }
    
    /**
     * Creates an item drop from item type and count.
     */
    public static void createItemDrop(World world, Vector3f position, ItemType itemType, int count) {
        if (itemType == null || count <= 0) {
            return;
        }
        
        ItemStack itemStack = new ItemStack(itemType, count);
        createItemDrop(world, position, itemStack);
    }
    
    /**
     * Drops an item from the player's current position.
     * Used when player presses Q to drop selected item.
     */
    public static void dropItemFromPlayer(Player player, ItemStack itemStack) {
        if (player == null || itemStack == null || itemStack.isEmpty()) {
            return;
        }
        
        // Access world through Game since Player doesn't have getWorld method
        World world = Game.getWorld();
        if (world == null) {
            return;
        }
        
        // Calculate drop position safely in front of player
        Vector3f playerPosition = player.getPosition();
        // Get player's forward direction from camera
        Vector3f playerForward = player.getCamera().getFront();
        
        Vector3f dropPosition = new Vector3f(playerPosition)
            .add(new Vector3f(playerForward).mul(2.0f)) // Drop 2 blocks in front to avoid player collision
            .add(0, 1.0f, 0); // 1 block above ground for better visibility
        
        // Give the drop some forward velocity
        Vector3f dropVelocity = new Vector3f(playerForward)
            .mul(2.0f) // Reduced forward speed since drop starts further away
            .add(0, 1.0f, 0); // Upward component
        
        // Create appropriate drop type based on item stack content
        if (itemStack.isPlaceable()) {
            BlockType blockType = itemStack.asBlockType();
            if (blockType != null) {
                BlockDrop drop = BlockDrop.createDropWithVelocity(world, dropPosition, blockType, dropVelocity);
                EntityManager entityManager = Game.getEntityManager();
                if (entityManager != null) {
                    entityManager.addEntity(drop);
                }
            }
        } else {
            ItemDrop drop = ItemDrop.createDropWithVelocity(world, dropPosition, itemStack.copy(), dropVelocity);
            EntityManager entityManager = Game.getEntityManager();
            if (entityManager != null) {
                entityManager.addEntity(drop);
            }
        }
    }
    
    /**
     * Drops a single item from the player's selected hotbar slot.
     * Decrements the stack by 1 and drops it.
     */
    public static void dropSingleItemFromPlayer(Player player) {
        if (player == null) {
            return;
        }
        
        com.stonebreak.items.Inventory inventory = player.getInventory();
        if (inventory == null) {
            return;
        }
        
        ItemStack selectedSlot = inventory.getSelectedHotbarSlot();
        if (selectedSlot == null || selectedSlot.isEmpty()) {
            return;
        }
        
        // Create a copy with count 1 for dropping
        ItemStack dropStack;
        if (selectedSlot.isPlaceable()) {
            BlockType blockType = selectedSlot.asBlockType();
            dropStack = new ItemStack(blockType, 1);
        } else {
            ItemType itemType = selectedSlot.asItemType();
            if (itemType != null) {
                dropStack = new ItemStack(itemType, 1);
            } else {
                return; // Can't drop unknown item type
            }
        }
        
        // Drop the item
        dropItemFromPlayer(player, dropStack);
        
        // Decrement the inventory slot
        selectedSlot.decrementCount(1);
        
        // Clear slot if empty
        if (selectedSlot.getCount() <= 0) {
            selectedSlot.clear();
        }
    }
    
    /**
     * Drops the entire stack from the player's selected hotbar slot.
     */
    public static void dropEntireStackFromPlayer(Player player) {
        if (player == null) {
            return;
        }
        
        com.stonebreak.items.Inventory inventory = player.getInventory();
        if (inventory == null) {
            return;
        }
        
        ItemStack selectedSlot = inventory.getSelectedHotbarSlot();
        if (selectedSlot == null || selectedSlot.isEmpty()) {
            return;
        }
        
        // Drop the entire stack
        dropItemFromPlayer(player, selectedSlot.copy());
        
        // Clear the slot
        selectedSlot.clear();
    }
    
    /**
     * Gets the appropriate drop for a broken block.
     * Some blocks may drop different items than themselves (e.g., stone drops cobblestone).
     */
    public static BlockType getBlockDrop(BlockType brokenBlock) {
        if (brokenBlock == null) {
            return null;
        }
        
        // For now, most blocks drop themselves
        // This can be expanded later for blocks that drop different items
        switch (brokenBlock) {
            case STONE:
                // Stone drops cobblestone when mined
                return BlockType.COBBLESTONE;
            case RED_SANDSTONE:
                // Red sandstone drops red sand cobblestone when mined
                return BlockType.RED_SAND_COBBLESTONE;
            case SANDSTONE:
                // Sandstone drops sand cobblestone when mined
                return BlockType.SAND_COBBLESTONE;
            case GRASS:
                return BlockType.DIRT;
            case IRON_ORE:
                // In future, this should drop iron items instead of the block
                return BlockType.IRON_ORE;
            case COAL_ORE:
                // In future, this should drop coal items instead of the block
                return BlockType.COAL_ORE;
            default:
                return brokenBlock; // Most blocks drop themselves
        }
    }
    
    /**
     * Handles block breaking and creates appropriate drops.
     */
    public static void handleBlockBroken(World world, Vector3f position, BlockType brokenBlock) {
        if (world == null || brokenBlock == null || brokenBlock == BlockType.AIR) {
            return;
        }
        
        // Special handling for snow blocks (multiple layers)
        if (brokenBlock == BlockType.SNOW) {
            // Get snow layer count from world
            int snowLayers = world.getSnowLayers((int)position.x, (int)position.y, (int)position.z);
            if (snowLayers > 0) {
                // Create multiple snow ball drops based on layer count
                createBlockDrops(world, position, BlockType.SNOW, snowLayers);
            }
            return;
        }
        
        // Get the appropriate drop for this block
        BlockType dropType = getBlockDrop(brokenBlock);
        if (dropType != null) {
            createBlockDrop(world, position, dropType);
        }
    }
}