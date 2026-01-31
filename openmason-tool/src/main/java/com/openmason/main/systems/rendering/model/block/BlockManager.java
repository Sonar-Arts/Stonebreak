package com.openmason.main.systems.rendering.model.block;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.rendering.core.API.commonBlockResources.models.BlockDefinitionRegistry;
import com.stonebreak.rendering.core.API.commonBlockResources.resources.CBRResourceManager;
import com.stonebreak.rendering.core.GameBlockDefinitionRegistry;
import com.stonebreak.rendering.textures.TextureAtlas;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Block management system for Open Mason following the same pattern as ModelManager.
 * Provides a simple interface for accessing block rendering resources through the CBR API.
 */
public class BlockManager {

    // Singleton instance
    private static volatile BlockManager instance;
    private static final Object LOCK = new Object();

    // State management
    private static final AtomicBoolean initialized = new AtomicBoolean(false);

    // CBR system components
    private TextureAtlas blockTextureAtlas;
    private BlockDefinitionRegistry blockRegistry;
    private CBRResourceManager cbrManager;

    /**
     * Private constructor for singleton pattern.
     */
    private BlockManager() {
        // Initialization happens in initialize()
    }

    /**
     * Gets the singleton instance.
     * Must call initialize() before using.
     *
     * @return The BlockManager instance
     * @throws IllegalStateException if not initialized
     */
    public static BlockManager getInstance() {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = new BlockManager();
                }
            }
        }

        if (!initialized.get()) {
            throw new IllegalStateException("BlockManager not initialized. Call initialize() first.");
        }

        return instance;
    }

    /**
     * Initialize the BlockManager system.
     * Creates texture atlas and initializes CBR system.
     * Thread-safe - only initializes once. Multiple threads can safely call this method.
     */
    public static void initialize() {
        // Fast path - already initialized
        if (initialized.get()) {
            return;
        }

        // Synchronize on the class to ensure only one thread initializes
        synchronized (BlockManager.class) {
            // Double-check after acquiring lock
            if (initialized.get()) {
                return;
            }

            try {
                System.out.println("[BlockManager] Initializing block management system...");

                // Create singleton instance if not exists
                if (instance == null) {
                    synchronized (LOCK) {
                        if (instance == null) {
                            instance = new BlockManager();
                        }
                    }
                }

                BlockManager manager = instance;

                // Create texture atlas for blocks (16x16 grid = 256 tiles)
                System.out.println("[BlockManager] Creating block texture atlas...");
                manager.blockTextureAtlas = new TextureAtlas(16);

                // Create block definition registry
                System.out.println("[BlockManager] Creating block definition registry...");
                manager.blockRegistry = new GameBlockDefinitionRegistry();

                // Initialize CBR resource manager
                System.out.println("[BlockManager] Initializing CBR resource manager...");
                manager.cbrManager = CBRResourceManager.getInstance(
                    manager.blockTextureAtlas,
                    manager.blockRegistry
                );

                initialized.set(true);
                System.out.println("[BlockManager] Initialization complete. Ready to render blocks.");

            } catch (Exception e) {
                System.err.println("[BlockManager] Initialization failed: " + e.getMessage());
                e.printStackTrace();
                throw new RuntimeException("Failed to initialize BlockManager", e);
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
     * Gets the block rendering resource for a specific BlockType.
     * This is the main method for rendering blocks.
     *
     * @param blockType The block type to get resources for
     * @return Block render resource with mesh and texture data
     */
    public CBRResourceManager.BlockRenderResource getBlockResource(BlockType blockType) {
        ensureInitialized();
        return cbrManager.getBlockTypeResource(blockType);
    }

    /**
     * Gets a list of all available block types.
     *
     * @return List of all BlockType enum values
     */
    public static List<BlockType> getAvailableBlocks() {
        return Arrays.asList(BlockType.values());
    }


    /**
     * Gets the texture atlas used for blocks.
     *
     * @return The block texture atlas
     */
    public TextureAtlas getTextureAtlas() {
        ensureInitialized();
        return blockTextureAtlas;
    }

    /**
     * Gets statistics about block resources.
     *
     * @return Resource statistics
     */
    public CBRResourceManager.ResourceStatistics getStatistics() {
        ensureInitialized();
        return cbrManager.getResourceStatistics();
    }

    /**
     * Gets a user-friendly display name for a block type.
     *
     * @param blockType The block type
     * @return Formatted display name
     */
    public static String getDisplayName(BlockType blockType) {
        if (blockType == null) return "Unknown";

        // Convert GRASS_BLOCK to "Grass Block"
        String name = blockType.name().replace('_', ' ');
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
     * Ensures the manager is initialized.
     *
     * @throws IllegalStateException if not initialized
     */
    private void ensureInitialized() {
        if (!initialized.get()) {
            throw new IllegalStateException("BlockManager not initialized. Call initialize() first.");
        }
    }

}
