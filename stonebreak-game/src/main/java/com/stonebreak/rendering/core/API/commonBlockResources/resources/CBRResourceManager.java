package com.stonebreak.rendering.core.API.commonBlockResources.resources;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemType;
import com.stonebreak.rendering.core.API.commonBlockResources.meshing.MeshManager;
import com.stonebreak.rendering.core.API.commonBlockResources.texturing.TextureResourceManager;
import com.stonebreak.rendering.core.API.commonBlockResources.models.BlockDefinition;
import com.stonebreak.rendering.core.API.commonBlockResources.models.BlockDefinitionRegistry;
import com.stonebreak.rendering.textures.TextureAtlas;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CBR Resource Manager - Central coordinator for all Common Block Resources.
 * 
 * This singleton class manages both texture and mesh resources, providing a unified
 * interface for all GPU resource interactions. It acts as the facade for the entire
 * CBR system, following the Facade pattern to simplify complex subsystem interactions.
 * 
 * Follows SOLID principles:
 * - Single Responsibility: Central coordination of all CBR resources
 * - Open/Closed: Extensible through manager composition
 * - Liskov Substitution: Can replace direct manager access
 * - Interface Segregation: Provides focused resource operations
 * - Dependency Inversion: Depends on manager abstractions
 * 
 * Implements RAII for automatic resource lifecycle management.
 */
public class CBRResourceManager implements AutoCloseable {
    
    // Singleton instance
    private static volatile CBRResourceManager instance;
    private static final Object LOCK = new Object();
    
    // Resource managers
    private final MeshManager meshManager;
    private final TextureResourceManager textureManager;
    private final BlockDefinitionRegistry blockRegistry;
    
    // State management
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean disposed = new AtomicBoolean(false);
    
    /**
     * Private constructor for singleton pattern.
     */
    private CBRResourceManager(TextureAtlas textureAtlas, BlockDefinitionRegistry registry) {
        this.blockRegistry = registry;
        this.meshManager = new MeshManager();
        this.textureManager = new TextureResourceManager(textureAtlas, registry);
        
        initialized.set(true);
        System.out.println("[CBRResourceManager] Initialized with mesh and texture managers");
    }
    
    /**
     * Gets or creates the singleton instance.
     * 
     * @param textureAtlas The texture atlas system
     * @param registry The block definition registry
     * @return The CBR resource manager instance
     */
    public static CBRResourceManager getInstance(TextureAtlas textureAtlas, BlockDefinitionRegistry registry) {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = new CBRResourceManager(textureAtlas, registry);
                }
            }
        }
        return instance;
    }
    
    /**
     * Gets the existing singleton instance.
     * 
     * @return The CBR resource manager instance
     * @throws IllegalStateException if not yet initialized
     */
    public static CBRResourceManager getInstance() {
        CBRResourceManager current = instance;
        if (current == null || !current.initialized.get()) {
            throw new IllegalStateException("CBRResourceManager not initialized. Call getInstance(TextureAtlas, BlockDefinitionRegistry) first.");
        }
        return current;
    }
    
    /**
     * Checks if the resource manager has been initialized.
     * 
     * @return true if initialized and ready for use
     */
    public static boolean isInitialized() {
        CBRResourceManager current = instance;
        return current != null && current.initialized.get() && !current.disposed.get();
    }
    
    // === Block Resource Operations ===
    
    /**
     * Gets complete rendering resources for a block definition.
     * This is the main method for rendering systems.
     * 
     * @param definition The block definition
     * @return Complete block resource with mesh and texture data
     */
    public BlockRenderResource getBlockRenderResource(BlockDefinition definition) {
        ensureInitialized();
        
        // Get texture coordinates from the atlas
        TextureResourceManager.TextureCoordinates texCoords = textureManager.resolveBlockTexture(definition);
        
        // Create block-specific mesh with correct texture coordinates
        String meshName = definition.getResourceId() + "_mesh";
        MeshManager.MeshResource mesh;
        
        switch (definition.getRenderType()) {
            case CUBE_ALL:
                mesh = meshManager.createCubeMeshWithTextureCoordinates(
                    meshName, 
                    texCoords.getU1(), texCoords.getV1(),
                    texCoords.getU2(), texCoords.getV2()
                );
                break;
            case CUBE_DIRECTIONAL:
                // Check if this block has unique textures for all 6 faces (like workbench)
                if (hasUniqueFaceTextures(definition)) {
                    // Get texture coordinates for each individual face
                    TextureResourceManager.TextureCoordinates frontTexCoords = textureManager.resolveBlockFaceTexture(definition, "north");
                    TextureResourceManager.TextureCoordinates backTexCoords = textureManager.resolveBlockFaceTexture(definition, "south");
                    TextureResourceManager.TextureCoordinates leftTexCoords = textureManager.resolveBlockFaceTexture(definition, "west");
                    TextureResourceManager.TextureCoordinates rightTexCoords = textureManager.resolveBlockFaceTexture(definition, "east");
                    TextureResourceManager.TextureCoordinates topTexCoords = textureManager.resolveBlockFaceTexture(definition, "top");
                    TextureResourceManager.TextureCoordinates bottomTexCoords = textureManager.resolveBlockFaceTexture(definition, "bottom");
                    
                    mesh = meshManager.createSixFaceDirectionalCubeMesh(
                        meshName,
                        // Front face (north)
                        frontTexCoords.getU1(), frontTexCoords.getV1(), frontTexCoords.getU2(), frontTexCoords.getV2(),
                        // Back face (south)
                        backTexCoords.getU1(), backTexCoords.getV1(), backTexCoords.getU2(), backTexCoords.getV2(),
                        // Left face (west)
                        leftTexCoords.getU1(), leftTexCoords.getV1(), leftTexCoords.getU2(), leftTexCoords.getV2(),
                        // Right face (east)
                        rightTexCoords.getU1(), rightTexCoords.getV1(), rightTexCoords.getU2(), rightTexCoords.getV2(),
                        // Top face
                        topTexCoords.getU1(), topTexCoords.getV1(), topTexCoords.getU2(), topTexCoords.getV2(),
                        // Bottom face
                        bottomTexCoords.getU1(), bottomTexCoords.getV1(), bottomTexCoords.getU2(), bottomTexCoords.getV2()
                    );
                } else {
                    // Use the simpler 3-texture directional approach (top/sides/bottom) 
                    // For grass blocks: top=grass, sides=dirt, bottom=dirt
                    TextureResourceManager.TextureCoordinates topTexCoords = textureManager.resolveBlockFaceTexture(definition, "top");
                    
                    // For sides, try to get a side-specific texture, otherwise fall back to dirt
                    TextureResourceManager.TextureCoordinates sideTexCoords;
                    if (definition.getResourceId().contains("grass")) {
                        // For grass blocks, sides should be dirt-like, try to get grass side or fall back to dirt
                        sideTexCoords = textureManager.resolveBlockFaceTexture(definition, "north");
                        // If that gives us the same as top (all grass), try to get dirt texture instead
                        if (topTexCoords.equals(sideTexCoords)) {
                            // Fall back to dirt texture for grass sides
                            try {
                                var dirtDef = blockRegistry.getDefinition("stonebreak:dirt");
                                if (dirtDef.isPresent()) {
                                    sideTexCoords = textureManager.resolveBlockTexture(dirtDef.get());
                                }
                            } catch (Exception e) {
                                // Keep the original if dirt lookup fails
                            }
                        }
                    } else {
                        sideTexCoords = textureManager.resolveBlockFaceTexture(definition, "north");
                    }
                    
                    TextureResourceManager.TextureCoordinates bottomTexCoords = textureManager.resolveBlockFaceTexture(definition, "bottom");
                    
                    mesh = meshManager.createDirectionalCubeMesh(
                        meshName,
                        // Top face (grass texture)
                        topTexCoords.getU1(), topTexCoords.getV1(), topTexCoords.getU2(), topTexCoords.getV2(),
                        // Side faces (dirt/grass-side texture) 
                        sideTexCoords.getU1(), sideTexCoords.getV1(), sideTexCoords.getU2(), sideTexCoords.getV2(),
                        // Bottom face (dirt texture)
                        bottomTexCoords.getU1(), bottomTexCoords.getV1(), bottomTexCoords.getU2(), bottomTexCoords.getV2()
                    );
                }
                break;
            case CROSS:
                // Determine if this should use cube cross format or full texture format
                if (shouldUseCubeCrossFormat(definition)) {
                    // Use cube cross format (only middle strip of texture)
                    mesh = meshManager.createCrossMeshWithTextureCoordinates(
                        meshName,
                        texCoords.getU1(), texCoords.getV1(),
                        texCoords.getU2(), texCoords.getV2()
                    );
                } else {
                    // Use full texture format (entire texture height)
                    mesh = meshManager.createFullTextureCrossMesh(
                        meshName,
                        texCoords.getU1(), texCoords.getV1(),
                        texCoords.getU2(), texCoords.getV2()
                    );
                }
                break;
            default:
                // Fallback to generic mesh
                mesh = meshManager.getMeshForDefinition(definition);
                break;
        }
        
        return new BlockRenderResource(definition, mesh, texCoords);
    }
    
    /**
     * Gets complete rendering resources for a block face.
     * Used for directional blocks with different face textures.
     * 
     * @param definition The block definition
     * @param face The face to render (north, south, east, west, up, down)
     * @return Block face resource with mesh and face-specific texture
     */
    public BlockRenderResource getBlockFaceResource(BlockDefinition definition, String face) {
        ensureInitialized();
        
        MeshManager.MeshResource mesh = meshManager.getMeshForDefinition(definition);
        TextureResourceManager.TextureCoordinates texCoords = textureManager.resolveBlockFaceTexture(definition, face);
        
        return new BlockRenderResource(definition, mesh, texCoords, face);
    }
    
    /**
     * Gets rendering resource for legacy BlockType (backward compatibility).
     * 
     * @param blockType The legacy block type
     * @return Block render resource
     */
    public BlockRenderResource getBlockTypeResource(BlockType blockType) {
        ensureInitialized();
        
        // Try to find modern definition first
        String resourceId = "stonebreak:" + blockType.name().toLowerCase();
        Optional<BlockDefinition> definition = blockRegistry.getDefinition(resourceId);
        
        if (definition.isPresent()) {
            return getBlockRenderResource(definition.get());
        } else {
            // Fallback to legacy rendering
            MeshManager.MeshResource mesh = meshManager.getMesh(MeshManager.MeshType.CUBE);
            TextureResourceManager.TextureCoordinates texCoords = textureManager.resolveBlockType(blockType);
            
            // Create temporary definition for legacy block
            BlockDefinition tempDefinition = new BlockDefinition.Builder()
                .resourceId(resourceId)
                .numericId(blockType.ordinal())
                .renderType(BlockDefinition.RenderType.CUBE_ALL)
                .build();
            
            return new BlockRenderResource(tempDefinition, mesh, texCoords);
        }
    }
    
    /**
     * Gets rendering resource for legacy ItemType (backward compatibility).
     * 
     * @param itemType The legacy item type
     * @return Block render resource configured for item rendering
     */
    public BlockRenderResource getItemTypeResource(ItemType itemType) {
        ensureInitialized();
        
        MeshManager.MeshResource mesh = meshManager.getMesh(MeshManager.MeshType.SPRITE);
        TextureResourceManager.TextureCoordinates texCoords = textureManager.resolveItemType(itemType);
        
        // Create temporary definition for legacy item
        BlockDefinition tempDefinition = new BlockDefinition.Builder()
            .resourceId("stonebreak:" + itemType.name().toLowerCase())
            .numericId(itemType.getId())
            .renderType(BlockDefinition.RenderType.SPRITE)
            .renderLayer(BlockDefinition.RenderLayer.CUTOUT)
            .build();
        
        return new BlockRenderResource(tempDefinition, mesh, texCoords);
    }
    
    // === Direct Manager Access ===
    
    /**
     * Gets the mesh manager for direct access.
     * Use sparingly - prefer the unified resource methods above.
     * 
     * @return The mesh manager
     */
    public MeshManager getMeshManager() {
        ensureInitialized();
        return meshManager;
    }
    
    /**
     * Gets the texture manager for direct access.
     * Use sparingly - prefer the unified resource methods above.
     * 
     * @return The texture manager
     */
    public TextureResourceManager getTextureManager() {
        ensureInitialized();
        return textureManager;
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
    
    // === Resource Management ===
    
    /**
     * Clears all cached resources to free memory.
     * Useful during world transitions or low memory conditions.
     */
    public void clearCaches() {
        ensureInitialized();
        textureManager.clearCache();
        System.out.println("[CBRResourceManager] Cleared all resource caches");
    }
    
    /**
     * Gets comprehensive statistics about managed resources.
     * 
     * @return Combined resource statistics
     */
    public ResourceStatistics getResourceStatistics() {
        ensureInitialized();
        
        MeshManager.MeshStatistics meshStats = meshManager.getStatistics();
        TextureResourceManager.CacheStatistics texStats = textureManager.getCacheStatistics();
        int blockDefCount = blockRegistry.getDefinitionCount();
        
        return new ResourceStatistics(meshStats, texStats, blockDefCount);
    }
    
    /**
     * Forces garbage collection and resource cleanup.
     * Use during loading screens or memory pressure.
     */
    public void optimizeMemory() {
        ensureInitialized();
        clearCaches();
        System.gc(); // Suggest garbage collection
        System.out.println("[CBRResourceManager] Optimized memory usage");
    }

    /**
     * Refreshes leaf block definitions when transparency setting changes.
     * This is much safer than full reinitialization as it doesn't dispose OpenGL resources.
     */
    public static void refreshLeafDefinitions() {
        CBRResourceManager current = instance;
        if (current != null && current.initialized.get()) {
            try {
                // Check if the registry is a GameBlockDefinitionRegistry
                if (current.blockRegistry instanceof com.stonebreak.rendering.core.GameBlockDefinitionRegistry) {
                    com.stonebreak.rendering.core.GameBlockDefinitionRegistry gameRegistry =
                        (com.stonebreak.rendering.core.GameBlockDefinitionRegistry) current.blockRegistry;
                    gameRegistry.refreshLeafDefinitions();

                    // Clear any cached resources for leaf blocks
                    current.clearCaches();

                    System.out.println("[CBRResourceManager] Leaf definitions refreshed successfully");
                } else {
                    System.out.println("[CBRResourceManager] Cannot refresh leaf definitions - unknown registry type");
                }
            } catch (Exception e) {
                System.err.println("[CBRResourceManager] Error refreshing leaf definitions: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            System.out.println("[CBRResourceManager] Cannot refresh - no initialized instance");
        }
    }

    /**
     * Forces reinitialization of the CBR system.
     * Use when block definitions need to be updated (e.g., settings changes).
     * Note: This will reinitialize the system with the same texture atlas and registry.
     * WARNING: This can cause OpenGL errors if called from wrong thread.
     */
    public static void forceReinitialize() {
        synchronized (LOCK) {
            CBRResourceManager current = instance;
            if (current != null && current.initialized.get()) {
                try {
                    // Store references before disposal
                    TextureAtlas atlas = current.textureManager.getTextureAtlas();
                    BlockDefinitionRegistry registry = current.blockRegistry;

                    // Dispose current instance
                    current.close();

                    // Ensure instance is nullified
                    instance = null;

                    // Create new instance with same parameters
                    CBRResourceManager newInstance = getInstance(atlas, registry);

                    System.out.println("[CBRResourceManager] Forced reinitialization completed");
                } catch (Exception e) {
                    System.err.println("[CBRResourceManager] Error during forced reinitialization: " + e.getMessage());
                    e.printStackTrace();
                }
            } else {
                System.out.println("[CBRResourceManager] Reinitialization skipped - no current instance or not initialized");
            }
        }
    }
    
    // === Lifecycle Management ===
    
    /**
     * Determines if a block definition has unique textures for all 6 faces.
     * Currently checks for known blocks that have 6 different face textures.
     * 
     * @param definition The block definition to check
     * @return true if the block has unique textures for all 6 faces
     */
    private boolean hasUniqueFaceTextures(BlockDefinition definition) {
        String resourceId = definition.getResourceId().toLowerCase();
        // Add blocks that are known to have unique textures for each face
        return resourceId.contains("workbench") || 
               resourceId.contains("crafting_table") ||
               resourceId.contains("furnace");
    }
    
    /**
     * Determines if a cross block should use the cube cross texture format (xoxx/oooo/xoxx).
     * Some flowers use the cube cross format where only the middle strip is textured,
     * while others use the full texture height.
     * 
     * @param definition The block definition to check
     * @return true if the block should use cube cross format, false for full texture format
     */
    private boolean shouldUseCubeCrossFormat(BlockDefinition definition) {
        // Both rose and dandelion are cross section flowers that use full texture format
        // The cube cross format is not used by any flowers in this implementation
        // All cross blocks should display their full texture height
        return false; // No cross blocks use cube cross format currently
    }
    
    /**
     * Ensures the manager is initialized and not disposed.
     */
    private void ensureInitialized() {
        if (disposed.get()) {
            throw new IllegalStateException("CBRResourceManager has been disposed");
        }
        if (!initialized.get()) {
            throw new IllegalStateException("CBRResourceManager not initialized");
        }
    }
    
    /**
     * Shuts down the resource manager and cleans up all resources.
     * This is automatically called when the JVM shuts down.
     */
    @Override
    public void close() {
        if (disposed.compareAndSet(false, true)) {
            try {
                meshManager.close();
                textureManager.close();
                blockRegistry.close();
                
                synchronized (LOCK) {
                    instance = null;
                }
                
                System.out.println("[CBRResourceManager] Disposed and cleaned up all resources");
            } catch (Exception e) {
                System.err.println("[CBRResourceManager] Error during disposal: " + e.getMessage());
            }
        }
    }
    
    /**
     * NOTE: Shutdown hooks removed because OpenGL cleanup MUST happen on the OpenGL thread.
     * Applications using CBRResourceManager should explicitly call close() from the main thread
     * during their shutdown sequence.
     *
     * IMPORTANT: Do NOT call OpenGL functions (glDelete*, etc.) from shutdown hooks or
     * background threads - they will cause "No context is current" fatal errors.
     */
    
    // === Data Classes ===
    
    /**
     * Complete rendering resource for a block or item.
     * Contains all data needed for efficient rendering.
     */
    public static class BlockRenderResource {
        private final BlockDefinition definition;
        private final MeshManager.MeshResource mesh;
        private final TextureResourceManager.TextureCoordinates textureCoords;
        private final String face; // null for non-face-specific resources
        
        public BlockRenderResource(BlockDefinition definition, 
                                 MeshManager.MeshResource mesh,
                                 TextureResourceManager.TextureCoordinates textureCoords) {
            this(definition, mesh, textureCoords, null);
        }
        
        public BlockRenderResource(BlockDefinition definition, 
                                 MeshManager.MeshResource mesh,
                                 TextureResourceManager.TextureCoordinates textureCoords,
                                 String face) {
            this.definition = definition;
            this.mesh = mesh;
            this.textureCoords = textureCoords;
            this.face = face;
        }
        
        // Getters
        public BlockDefinition getDefinition() { return definition; }
        public MeshManager.MeshResource getMesh() { return mesh; }
        public TextureResourceManager.TextureCoordinates getTextureCoords() { return textureCoords; }
        public Optional<String> getFace() { return Optional.ofNullable(face); }
        
        /**
         * Convenience method for rendering - binds mesh and returns texture coordinates.
         * 
         * @return Texture coordinates array [u1, v1, u2, v2]
         */
        public float[] bindAndGetTexCoords() {
            mesh.bind();
            return textureCoords.toArray();
        }
        
        /**
         * Complete rendering operation - bind and draw with texture coordinates ready.
         */
        public void render() {
            mesh.bindAndDraw();
        }
        
        @Override
        public String toString() {
            return String.format("BlockRenderResource[%s, %s, %s%s]",
                               definition.getResourceId(),
                               mesh.getName(),
                               textureCoords,
                               face != null ? ", face=" + face : "");
        }
    }
    
    /**
     * Combined statistics from all resource managers.
     */
    public static class ResourceStatistics {
        private final MeshManager.MeshStatistics meshStats;
        private final TextureResourceManager.CacheStatistics textureStats;
        private final int blockDefinitionCount;
        
        public ResourceStatistics(MeshManager.MeshStatistics meshStats,
                                TextureResourceManager.CacheStatistics textureStats,
                                int blockDefinitionCount) {
            this.meshStats = meshStats;
            this.textureStats = textureStats;
            this.blockDefinitionCount = blockDefinitionCount;
        }
        
        public MeshManager.MeshStatistics getMeshStats() { return meshStats; }
        public TextureResourceManager.CacheStatistics getTextureStats() { return textureStats; }
        public int getBlockDefinitionCount() { return blockDefinitionCount; }
        
        @Override
        public String toString() {
            return String.format("CBR Stats[defs=%d, %s, %s]",
                               blockDefinitionCount, meshStats, textureStats);
        }
    }
}