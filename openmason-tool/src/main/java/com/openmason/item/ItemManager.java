package com.openmason.item;

import com.stonebreak.items.ItemType;
import com.stonebreak.rendering.player.items.voxelization.ColorPalette;
import com.stonebreak.rendering.player.items.voxelization.SpriteVoxelizer;
import com.stonebreak.rendering.player.items.voxelization.VoxelData;
import com.stonebreak.rendering.player.items.voxelization.VoxelMesh;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Item management system for Open Mason following the same pattern as BlockManager.
 * Provides a simple interface for accessing voxelized item meshes.
 *
 * Design Principles:
 * - KISS: Simple wrapper around SpriteVoxelizer
 * - YAGNI: Only implements what's needed (view all voxelizable items)
 * - SOLID: Single responsibility (manage item voxel meshes)
 * - DRY: Reuses voxelization API, no duplicate code
 */
public class ItemManager {

    // Singleton instance
    private static ItemManager instance;
    private static final Object LOCK = new Object();

    // State management
    private static final AtomicBoolean initialized = new AtomicBoolean(false);
    private static final AtomicBoolean initializationInProgress = new AtomicBoolean(false);

    // Cache for voxelized meshes
    private final Map<ItemType, VoxelMesh> meshCache = new HashMap<>();
    private final Map<ItemType, ColorPalette> paletteCache = new HashMap<>();

    // Voxel rendering configuration
    private float voxelSize = SpriteVoxelizer.getDefaultVoxelSize();

    /**
     * Private constructor for singleton pattern.
     */
    private ItemManager() {
        // Initialization happens in initialize()
    }

    /**
     * Gets the singleton instance.
     * Must call initialize() before using.
     *
     * @return The ItemManager instance
     * @throws IllegalStateException if not initialized
     */
    public static ItemManager getInstance() {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = new ItemManager();
                }
            }
        }

        if (!initialized.get()) {
            throw new IllegalStateException("ItemManager not initialized. Call initialize() first.");
        }

        return instance;
    }

    /**
     * Initialize the ItemManager system.
     * Thread-safe - only initializes once.
     */
    public static synchronized void initialize() {
        if (initialized.get()) {
            System.out.println("[ItemManager] Already initialized");
            return;
        }

        if (initializationInProgress.compareAndSet(false, true)) {
            try {
                System.out.println("[ItemManager] Initializing item voxelization system...");

                // Create singleton instance if not exists
                if (instance == null) {
                    synchronized (LOCK) {
                        if (instance == null) {
                            instance = new ItemManager();
                        }
                    }
                }

                // Pre-cache all voxelizable items
                System.out.println("[ItemManager] Pre-caching voxelized items...");
                int cached = 0;
                for (ItemType itemType : ItemType.values()) {
                    if (SpriteVoxelizer.isVoxelizable(itemType)) {
                        try {
                            instance.getOrCreateMesh(itemType);
                            cached++;
                        } catch (Exception e) {
                            System.err.println("[ItemManager] Failed to cache " + itemType.getName() + ": " + e.getMessage());
                        }
                    }
                }

                initialized.set(true);
                System.out.println("[ItemManager] Initialization complete. Cached " + cached + " items.");

            } catch (Exception e) {
                System.err.println("[ItemManager] Initialization failed: " + e.getMessage());
                e.printStackTrace();
                throw new RuntimeException("Failed to initialize ItemManager", e);
            } finally {
                initializationInProgress.set(false);
            }
        } else {
            // Wait for initialization to complete
            while (initializationInProgress.get()) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Initialization interrupted", e);
                }
            }
        }
    }

    /**
     * Checks if the manager is initialized.
     *
     * @return true if initialized and ready for use
     */
    public static boolean isInitialized() {
        return initialized.get() && instance != null;
    }

    /**
     * Gets the voxelized mesh for a specific ItemType.
     * This is the main method for rendering items.
     *
     * @param itemType The item type to get mesh for
     * @return VoxelMesh for rendering
     * @throws IllegalArgumentException if item is not voxelizable
     */
    public VoxelMesh getItemMesh(ItemType itemType) {
        ensureInitialized();

        if (!SpriteVoxelizer.isVoxelizable(itemType)) {
            throw new IllegalArgumentException("Item type " + itemType.getName() + " is not voxelizable");
        }

        return getOrCreateMesh(itemType);
    }

    /**
     * Gets the color palette for a specific ItemType.
     *
     * @param itemType The item type to get palette for
     * @return ColorPalette for the item
     */
    public ColorPalette getItemPalette(ItemType itemType) {
        ensureInitialized();

        if (!SpriteVoxelizer.isVoxelizable(itemType)) {
            throw new IllegalArgumentException("Item type " + itemType.getName() + " is not voxelizable");
        }

        return paletteCache.get(itemType);
    }

    /**
     * Gets or creates a voxelized mesh for an item type.
     * Uses caching to avoid regenerating meshes.
     *
     * @param itemType The item type
     * @return The voxelized mesh
     */
    private VoxelMesh getOrCreateMesh(ItemType itemType) {
        // Check cache first
        if (meshCache.containsKey(itemType)) {
            return meshCache.get(itemType);
        }

        // Voxelize the sprite
        SpriteVoxelizer.VoxelizationResult result = SpriteVoxelizer.voxelizeSpriteWithPalette(itemType);

        if (!result.isValid()) {
            throw new RuntimeException("Failed to voxelize item: " + itemType.getName());
        }

        // Create mesh from voxel data
        VoxelMesh mesh = new VoxelMesh();
        mesh.createMesh(result.getVoxels(), voxelSize);

        // Cache the mesh and palette
        meshCache.put(itemType, mesh);
        paletteCache.put(itemType, result.getColorPalette());

        System.out.println("[ItemManager] Created voxel mesh for " + itemType.getName() +
                         " (" + result.getVoxels().size() + " voxels)");

        return mesh;
    }

    /**
     * Gets a list of all available item types.
     *
     * @return List of all ItemType enum values
     */
    public static List<ItemType> getAvailableItems() {
        return Arrays.asList(ItemType.values());
    }

    /**
     * Gets a list of all voxelizable item types.
     *
     * @return List of voxelizable items
     */
    public static List<ItemType> getVoxelizableItems() {
        List<ItemType> voxelizable = new ArrayList<>();
        for (ItemType itemType : ItemType.values()) {
            if (SpriteVoxelizer.isVoxelizable(itemType)) {
                voxelizable.add(itemType);
            }
        }
        return voxelizable;
    }

    /**
     * Gets a user-friendly display name for an item type.
     *
     * @param itemType The item type
     * @return Formatted display name
     */
    public static String getDisplayName(ItemType itemType) {
        if (itemType == null) return "Unknown";

        // Convert WOODEN_PICKAXE to "Wooden Pickaxe"
        String name = itemType.name().replace('_', ' ');
        String[] words = name.toLowerCase().split(" ");
        StringBuilder result = new StringBuilder();

        for (String word : words) {
            if (!word.isEmpty()) {
                result.append(Character.toUpperCase(word.charAt(0)))
                      .append(word.substring(1))
                      .append(" ");
            }
        }

        return result.toString().trim();
    }

    /**
     * Validates that an item type can be rendered.
     *
     * @param itemType The item type to validate
     * @return true if the item can be rendered
     */
    public boolean validateItem(ItemType itemType) {
        if (!isInitialized()) {
            return false;
        }

        if (!SpriteVoxelizer.isVoxelizable(itemType)) {
            return false;
        }

        try {
            VoxelMesh mesh = getItemMesh(itemType);
            return mesh != null && mesh.isCreated();
        } catch (Exception e) {
            System.err.println("[ItemManager] Validation failed for " + itemType + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Gets the voxel size used for rendering.
     *
     * @return Voxel size in world units
     */
    public float getVoxelSize() {
        return voxelSize;
    }

    /**
     * Sets the voxel size for rendering.
     * Note: This will invalidate the cache and require mesh regeneration.
     *
     * @param voxelSize New voxel size
     */
    public void setVoxelSize(float voxelSize) {
        if (voxelSize <= 0) {
            throw new IllegalArgumentException("Voxel size must be positive");
        }

        if (this.voxelSize != voxelSize) {
            this.voxelSize = voxelSize;
            clearCaches(); // Invalidate cache since size changed
            System.out.println("[ItemManager] Voxel size changed to " + voxelSize);
        }
    }

    /**
     * Clears all cached meshes and palettes.
     * Call this to free memory or when changing voxel size.
     */
    public void clearCaches() {
        if (isInitialized()) {
            // Clean up OpenGL resources
            for (VoxelMesh mesh : meshCache.values()) {
                mesh.cleanup();
            }

            meshCache.clear();
            paletteCache.clear();
            System.out.println("[ItemManager] Cleared all item mesh caches");
        }
    }

    /**
     * Gets statistics about item resources.
     *
     * @return Resource statistics
     */
    public ItemStatistics getStatistics() {
        ensureInitialized();
        return new ItemStatistics(
            meshCache.size(),
            getVoxelizableItems().size(),
            voxelSize
        );
    }

    /**
     * Cleanup resources.
     * Call when shutting down the application.
     */
    public static void shutdown() {
        synchronized (LOCK) {
            if (instance != null && initialized.get()) {
                try {
                    System.out.println("[ItemManager] Shutting down...");

                    instance.clearCaches();

                    // Clear sprite cache in voxelizer
                    SpriteVoxelizer.clearCache();

                    initialized.set(false);
                    instance = null;

                    System.out.println("[ItemManager] Shutdown complete");
                } catch (Exception e) {
                    System.err.println("[ItemManager] Error during shutdown: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * Ensures the manager is initialized.
     *
     * @throws IllegalStateException if not initialized
     */
    private void ensureInitialized() {
        if (!initialized.get()) {
            throw new IllegalStateException("ItemManager not initialized. Call initialize() first.");
        }
    }

    /**
     * Statistics about item resources.
     */
    public static class ItemStatistics {
        private final int cachedMeshes;
        private final int totalVoxelizableItems;
        private final float voxelSize;

        public ItemStatistics(int cachedMeshes, int totalVoxelizableItems, float voxelSize) {
            this.cachedMeshes = cachedMeshes;
            this.totalVoxelizableItems = totalVoxelizableItems;
            this.voxelSize = voxelSize;
        }

        public int getCachedMeshes() { return cachedMeshes; }
        public int getTotalVoxelizableItems() { return totalVoxelizableItems; }
        public float getVoxelSize() { return voxelSize; }

        @Override
        public String toString() {
            return String.format("ItemStatistics{cached=%d, total=%d, voxelSize=%.4f}",
                cachedMeshes, totalVoxelizableItems, voxelSize);
        }
    }

    /**
     * Simple item information class.
     */
    public static class ItemInfo {
        private final ItemType itemType;
        private final String displayName;
        private final boolean voxelizable;

        public ItemInfo(ItemType itemType, String displayName, boolean voxelizable) {
            this.itemType = itemType;
            this.displayName = displayName;
            this.voxelizable = voxelizable;
        }

        public ItemType getItemType() { return itemType; }
        public String getDisplayName() { return displayName; }
        public boolean isVoxelizable() { return voxelizable; }

        @Override
        public String toString() {
            return String.format("ItemInfo{%s - %s (voxelizable=%s)}",
                itemType, displayName, voxelizable);
        }
    }
}
