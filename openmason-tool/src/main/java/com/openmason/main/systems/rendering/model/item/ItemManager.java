package com.openmason.main.systems.rendering.model.item;

import com.stonebreak.items.ItemType;
import com.stonebreak.rendering.player.items.voxelization.ColorPalette;
import com.stonebreak.rendering.player.items.voxelization.SpriteVoxelizer;
import com.stonebreak.rendering.player.items.voxelization.VoxelMesh;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Item management system for Open Mason following the same pattern as BlockManager.
 * Provides a simple interface for accessing voxelized item meshes.
 */
public class ItemManager {

    // Singleton instance
    private static volatile ItemManager instance;
    private static final Object LOCK = new Object();

    // State management
    private static final AtomicBoolean initialized = new AtomicBoolean(false);

    // Cache for voxelized meshes
    private final Map<ItemType, VoxelMesh> meshCache = new HashMap<>();
    private final Map<ItemType, ColorPalette> paletteCache = new HashMap<>();

    // Voxel rendering configuration
    private final float voxelSize = SpriteVoxelizer.getDefaultVoxelSize();

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
     * Thread-safe - only initializes once. Multiple threads can safely call this method.
     */
    public static void initialize() {
        // Fast path - already initialized
        if (initialized.get()) {
            return;
        }

        // Synchronize on the class to ensure only one thread initializes
        synchronized (ItemManager.class) {
            // Double-check after acquiring lock
            if (initialized.get()) {
                return;
            }

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

        @Override
        public String toString() {
            return String.format("ItemStatistics{cached=%d, total=%d, voxelSize=%.4f}",
                cachedMeshes, totalVoxelizableItems, voxelSize);
        }
    }
}
