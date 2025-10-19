package com.openmason.block;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.rendering.core.GameBlockDefinitionRegistry;
import com.stonebreak.rendering.core.API.commonBlockResources.models.BlockDefinition;
import com.stonebreak.rendering.core.API.commonBlockResources.models.BlockDefinitionRegistry;
import com.stonebreak.rendering.core.API.commonBlockResources.resources.CBRResourceManager;
import com.stonebreak.rendering.textures.TextureAtlas;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Block management system for Open Mason following the same pattern as ModelManager.
 * Provides a simple interface for accessing block rendering resources through the CBR API.
 *
 * Design Principles:
 * - KISS: Simple wrapper around CBRResourceManager
 * - YAGNI: Only implements what's needed (view all BlockTypes)
 * - SOLID: Single responsibility (manage block resources)
 * - DRY: Reuses CBR API, no duplicate code
 */
public class BlockManager {

    // Singleton instance
    private static BlockManager instance;
    private static final Object LOCK = new Object();

    // State management
    private static final AtomicBoolean initialized = new AtomicBoolean(false);
    private static final AtomicBoolean initializationInProgress = new AtomicBoolean(false);

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
     * Thread-safe - only initializes once.
     */
    public static synchronized void initialize() {
        if (initialized.get()) {
            System.out.println("[BlockManager] Already initialized");
            return;
        }

        if (initializationInProgress.compareAndSet(false, true)) {
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
     * Gets the block rendering resource for a BlockDefinition.
     *
     * @param definition The block definition
     * @return Block render resource with mesh and texture data
     */
    public CBRResourceManager.BlockRenderResource getBlockResourceFromDefinition(BlockDefinition definition) {
        ensureInitialized();
        return cbrManager.getBlockRenderResource(definition);
    }

    /**
     * Gets the block definition for a specific BlockType.
     *
     * @param blockType The block type
     * @return Optional containing the block definition if found
     */
    public Optional<BlockDefinition> getBlockDefinition(BlockType blockType) {
        ensureInitialized();
        String resourceId = "stonebreak:" + blockType.name().toLowerCase();
        return blockRegistry.getDefinition(resourceId);
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
     * Gets a list of all block types filtered by category.
     *
     * @param category The category to filter by (e.g., "ORE", "WOOD", "STONE")
     * @return Filtered list of block types
     */
    public static List<BlockType> getBlocksByCategory(String category) {
        String searchTerm = category.toUpperCase();
        return Arrays.stream(BlockType.values())
            .filter(block -> block.name().toUpperCase().contains(searchTerm))
            .collect(Collectors.toList());
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
     * Gets the CBR resource manager.
     *
     * @return The CBR resource manager
     */
    public CBRResourceManager getCBRManager() {
        ensureInitialized();
        return cbrManager;
    }

    /**
     * Gets the block definition registry.
     *
     * @return The block registry
     */
    public BlockDefinitionRegistry getBlockRegistry() {
        ensureInitialized();
        return blockRegistry;
    }

    /**
     * Clears all cached resources to free memory.
     */
    public void clearCaches() {
        if (isInitialized()) {
            cbrManager.clearCaches();
            System.out.println("[BlockManager] Cleared all block resource caches");
        }
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
     * Validates that a block type can be rendered.
     *
     * @param blockType The block type to validate
     * @return true if the block can be rendered
     */
    public boolean validateBlock(BlockType blockType) {
        if (!isInitialized()) {
            return false;
        }

        try {
            CBRResourceManager.BlockRenderResource resource = getBlockResource(blockType);
            return resource != null && resource.getMesh() != null;
        } catch (Exception e) {
            System.err.println("[BlockManager] Validation failed for " + blockType + ": " + e.getMessage());
            return false;
        }
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
     * Cleanup resources.
     * Call when shutting down the application.
     */
    public static void shutdown() {
        synchronized (LOCK) {
            if (instance != null && initialized.get()) {
                try {
                    System.out.println("[BlockManager] Shutting down...");

                    if (instance.cbrManager != null) {
                        instance.cbrManager.close();
                    }

                    if (instance.blockTextureAtlas != null) {
                        instance.blockTextureAtlas.cleanup();
                    }

                    initialized.set(false);
                    instance = null;

                    System.out.println("[BlockManager] Shutdown complete");
                } catch (Exception e) {
                    System.err.println("[BlockManager] Error during shutdown: " + e.getMessage());
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
            throw new IllegalStateException("BlockManager not initialized. Call initialize() first.");
        }
    }

    /**
     * Simple block information class.
     */
    public static class BlockInfo {
        private final BlockType blockType;
        private final String displayName;
        private final BlockDefinition definition;

        public BlockInfo(BlockType blockType, String displayName, BlockDefinition definition) {
            this.blockType = blockType;
            this.displayName = displayName;
            this.definition = definition;
        }

        public BlockType getBlockType() { return blockType; }
        public String getDisplayName() { return displayName; }
        public BlockDefinition getDefinition() { return definition; }

        @Override
        public String toString() {
            return String.format("BlockInfo{%s - %s}", blockType, displayName);
        }
    }
}
