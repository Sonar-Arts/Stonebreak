package com.stonebreak.textures;

import java.util.*;

/**
 * Manages ID namespace allocation for texture atlas system.
 * Ensures no conflicts between block and item IDs, and provides migration support.
 */
public class AtlasIdManager {
    
    // ID range constants
    public static final int BLOCK_ID_MIN = 0;
    public static final int BLOCK_ID_MAX = 999;
    public static final int ITEM_ID_MIN = 1000;
    public static final int ITEM_ID_MAX = Integer.MAX_VALUE;
    
    // Reserved ranges for special purposes
    public static final int ERROR_TEXTURE_ID = 999; // Reserved for Errockson.gif
    
    // Tracking sets for allocated IDs
    private final Set<Integer> allocatedBlockIds = new HashSet<>();
    private final Set<Integer> allocatedItemIds = new HashSet<>();
    
    // Mappings for legacy ID migration
    private final Map<Integer, Integer> legacyBlockIdMappings = new HashMap<>();
    private final Map<Integer, Integer> legacyItemIdMappings = new HashMap<>();
    
    // Name to ID mappings for reverse lookups
    private final Map<String, Integer> blockNameToId = new HashMap<>();
    private final Map<String, Integer> itemNameToId = new HashMap<>();
    private final Map<Integer, String> blockIdToName = new HashMap<>();
    private final Map<Integer, String> itemIdToName = new HashMap<>();
    
    /**
     * Result of ID allocation or validation operations.
     */
    public static class IdResult {
        public final boolean success;
        public final int assignedId;
        public final String message;
        public final IdType type;
        
        public IdResult(boolean success, int assignedId, String message, IdType type) {
            this.success = success;
            this.assignedId = assignedId;
            this.message = message;
            this.type = type;
        }
        
        public static IdResult success(int id, String message, IdType type) {
            return new IdResult(true, id, message, type);
        }
        
        public static IdResult failure(String message, IdType type) {
            return new IdResult(false, -1, message, type);
        }
    }
    
    public enum IdType {
        BLOCK, ITEM, ERROR_TEXTURE
    }
    
    /**
     * Creates a new AtlasIdManager with default configuration.
     */
    public AtlasIdManager() {
        // Reserve the error texture ID
        allocatedBlockIds.add(ERROR_TEXTURE_ID);
        blockNameToId.put("stonebreak:error", ERROR_TEXTURE_ID);
        blockIdToName.put(ERROR_TEXTURE_ID, "stonebreak:error");
    }
    
    /**
     * Allocates a new block ID in the valid range.
     * @param blockName The namespaced block name (e.g., "stonebreak:grass")
     * @param preferredId Optional preferred ID, or -1 for auto-assignment
     * @return IdResult indicating success or failure with assigned ID
     */
    public IdResult allocateBlockId(String blockName, int preferredId) {
        if (blockNameToId.containsKey(blockName)) {
            int existingId = blockNameToId.get(blockName);
            return IdResult.success(existingId, "Block already has ID: " + existingId, IdType.BLOCK);
        }
        
        int targetId = preferredId;
        
        // Auto-assign if no preference specified
        if (targetId == -1) {
            targetId = findNextAvailableBlockId();
        }
        
        // Validate ID is in valid range
        if (targetId < BLOCK_ID_MIN || targetId > BLOCK_ID_MAX) {
            return IdResult.failure("Block ID " + targetId + " is outside valid range [" + 
                                  BLOCK_ID_MIN + "-" + BLOCK_ID_MAX + "]", IdType.BLOCK);
        }
        
        // Check if ID is already allocated
        if (allocatedBlockIds.contains(targetId)) {
            if (preferredId != -1) {
                return IdResult.failure("Block ID " + targetId + " is already allocated to: " + 
                                      blockIdToName.get(targetId), IdType.BLOCK);
            } else {
                // Try to find another ID
                targetId = findNextAvailableBlockId();
                if (targetId == -1) {
                    return IdResult.failure("No available block IDs in range", IdType.BLOCK);
                }
            }
        }
        
        // Allocate the ID
        allocatedBlockIds.add(targetId);
        blockNameToId.put(blockName, targetId);
        blockIdToName.put(targetId, blockName);
        
        return IdResult.success(targetId, "Allocated block ID: " + targetId, IdType.BLOCK);
    }
    
    /**
     * Allocates a new item ID in the valid range (1000+).
     * @param itemName The namespaced item name (e.g., "stonebreak:stick")
     * @param preferredId Optional preferred ID, or -1 for auto-assignment
     * @return IdResult indicating success or failure with assigned ID
     */
    public IdResult allocateItemId(String itemName, int preferredId) {
        if (itemNameToId.containsKey(itemName)) {
            int existingId = itemNameToId.get(itemName);
            return IdResult.success(existingId, "Item already has ID: " + existingId, IdType.ITEM);
        }
        
        int targetId = preferredId;
        
        // Auto-assign if no preference specified
        if (targetId == -1) {
            targetId = findNextAvailableItemId();
        }
        
        // Validate ID is in valid range
        if (targetId < ITEM_ID_MIN) {
            return IdResult.failure("Item ID " + targetId + " is below minimum: " + ITEM_ID_MIN, IdType.ITEM);
        }
        
        // Check if ID is already allocated
        if (allocatedItemIds.contains(targetId)) {
            if (preferredId != -1) {
                return IdResult.failure("Item ID " + targetId + " is already allocated to: " + 
                                      itemIdToName.get(targetId), IdType.ITEM);
            } else {
                // Try to find another ID
                targetId = findNextAvailableItemId();
                if (targetId == -1) {
                    return IdResult.failure("Unable to find available item ID", IdType.ITEM);
                }
            }
        }
        
        // Allocate the ID
        allocatedItemIds.add(targetId);
        itemNameToId.put(itemName, targetId);
        itemIdToName.put(targetId, itemName);
        
        return IdResult.success(targetId, "Allocated item ID: " + targetId, IdType.ITEM);
    }
    
    /**
     * Migrates a legacy item ID to the new namespace (adds 1000).
     * @param itemName The item name
     * @param legacyId The old ID (0-2 in current system)
     * @return IdResult with the new ID
     */
    public IdResult migrateItemId(String itemName, int legacyId) {
        int newId = legacyId + ITEM_ID_MIN; // Convert 0->1000, 1->1001, 2->1002
        
        // Record the migration mapping
        legacyItemIdMappings.put(legacyId, newId);
        
        // Allocate with the calculated new ID
        IdResult result = allocateItemId(itemName, newId);
        if (result.success) {
            System.out.println("Migrated item '" + itemName + "' from ID " + legacyId + " to " + newId);
        }
        
        return result;
    }
    
    /**
     * Finds the next available block ID.
     * @return Next available ID, or -1 if none available
     */
    private int findNextAvailableBlockId() {
        for (int id = BLOCK_ID_MIN; id <= BLOCK_ID_MAX; id++) {
            if (!allocatedBlockIds.contains(id)) {
                return id;
            }
        }
        return -1;
    }
    
    /**
     * Finds the next available item ID.
     * @return Next available ID, or -1 if unable to find one in reasonable range
     */
    private int findNextAvailableItemId() {
        // Search in reasonable range to avoid infinite loops
        for (int id = ITEM_ID_MIN; id < ITEM_ID_MIN + 10000; id++) {
            if (!allocatedItemIds.contains(id)) {
                return id;
            }
        }
        return -1;
    }
    
    /**
     * Gets the ID for a block name.
     * @param blockName The namespaced block name
     * @return Block ID, or -1 if not found
     */
    public int getBlockId(String blockName) {
        return blockNameToId.getOrDefault(blockName, -1);
    }
    
    /**
     * Gets the ID for an item name.
     * @param itemName The namespaced item name
     * @return Item ID, or -1 if not found
     */
    public int getItemId(String itemName) {
        return itemNameToId.getOrDefault(itemName, -1);
    }
    
    /**
     * Gets the name for a block ID.
     * @param blockId The block ID
     * @return Block name, or null if not found
     */
    public String getBlockName(int blockId) {
        return blockIdToName.get(blockId);
    }
    
    /**
     * Gets the name for an item ID.
     * @param itemId The item ID
     * @return Item name, or null if not found
     */
    public String getItemName(int itemId) {
        return itemIdToName.get(itemId);
    }
    
    /**
     * Checks if a block ID is allocated.
     * @param blockId The block ID to check
     * @return true if allocated, false otherwise
     */
    public boolean isBlockIdAllocated(int blockId) {
        return allocatedBlockIds.contains(blockId);
    }
    
    /**
     * Checks if an item ID is allocated.
     * @param itemId The item ID to check
     * @return true if allocated, false otherwise
     */
    public boolean isItemIdAllocated(int itemId) {
        return allocatedItemIds.contains(itemId);
    }
    
    /**
     * Gets the new ID for a legacy item ID after migration.
     * @param legacyId The old item ID
     * @return New item ID, or -1 if no mapping exists
     */
    public int getMigratedItemId(int legacyId) {
        return legacyItemIdMappings.getOrDefault(legacyId, -1);
    }
    
    /**
     * Validates that there are no ID conflicts between blocks and items.
     * @return List of conflict messages, empty if no conflicts
     */
    public List<String> validateNoConflicts() {
        List<String> conflicts = new ArrayList<>();
        
        // Check for overlapping ID ranges (shouldn't happen by design, but validate anyway)
        for (Integer blockId : allocatedBlockIds) {
            if (allocatedItemIds.contains(blockId)) {
                conflicts.add("ID conflict: " + blockId + " is allocated to both block '" + 
                            getBlockName(blockId) + "' and item '" + getItemName(blockId) + "'");
            }
        }
        
        // Check for duplicate names
        Set<String> allNames = new HashSet<>();
        for (String blockName : blockNameToId.keySet()) {
            if (!allNames.add(blockName)) {
                conflicts.add("Duplicate name: " + blockName);
            }
        }
        for (String itemName : itemNameToId.keySet()) {
            if (!allNames.add(itemName)) {
                conflicts.add("Duplicate name: " + itemName);
            }
        }
        
        return conflicts;
    }
    
    /**
     * Gets usage statistics for debugging and monitoring.
     * @return Map of usage statistics
     */
    public Map<String, Object> getUsageStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("allocatedBlockIds", allocatedBlockIds.size());
        stats.put("allocatedItemIds", allocatedItemIds.size());
        stats.put("blockIdCapacity", BLOCK_ID_MAX - BLOCK_ID_MIN + 1);
        stats.put("blockUtilization", (double) allocatedBlockIds.size() / (BLOCK_ID_MAX - BLOCK_ID_MIN + 1));
        stats.put("legacyMigrations", legacyItemIdMappings.size());
        return stats;
    }
    
    /**
     * Prints a summary of allocated IDs for debugging.
     */
    public void printSummary() {
        System.out.println("=== Atlas ID Manager Summary ===");
        System.out.println("Allocated Block IDs: " + allocatedBlockIds.size() + "/" + (BLOCK_ID_MAX - BLOCK_ID_MIN + 1));
        System.out.println("Allocated Item IDs: " + allocatedItemIds.size());
        
        System.out.println("\nBlock Allocations:");
        for (Map.Entry<String, Integer> entry : blockNameToId.entrySet()) {
            System.out.println("  " + entry.getKey() + " -> " + entry.getValue());
        }
        
        System.out.println("\nItem Allocations:");
        for (Map.Entry<String, Integer> entry : itemNameToId.entrySet()) {
            System.out.println("  " + entry.getKey() + " -> " + entry.getValue());
        }
        
        if (!legacyItemIdMappings.isEmpty()) {
            System.out.println("\nLegacy Item Migrations:");
            for (Map.Entry<Integer, Integer> entry : legacyItemIdMappings.entrySet()) {
                System.out.println("  " + entry.getKey() + " -> " + entry.getValue());
            }
        }
        
        List<String> conflicts = validateNoConflicts();
        if (!conflicts.isEmpty()) {
            System.out.println("\nCONFLICTS DETECTED:");
            for (String conflict : conflicts) {
                System.out.println("  " + conflict);
            }
        }
        
        System.out.println("===============================");
    }
}