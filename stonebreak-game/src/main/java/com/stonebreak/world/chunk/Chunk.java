package com.stonebreak.world.chunk;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.api.commonChunkOperations.CcoFactory;
import com.stonebreak.world.chunk.api.commonChunkOperations.CcoFactory.ComponentBundle;
import com.stonebreak.world.chunk.api.commonChunkOperations.coordinates.CcoCoordinates;
import com.stonebreak.world.chunk.api.commonChunkOperations.data.*;
import com.stonebreak.world.chunk.api.commonChunkOperations.operations.CcoBlockReader;
import com.stonebreak.world.chunk.api.commonChunkOperations.operations.CcoBlockWriter;
import com.stonebreak.world.chunk.api.commonChunkOperations.serialization.CcoSnapshotBuilder;
import com.stonebreak.world.chunk.api.commonChunkOperations.state.CcoAtomicStateManager;
import com.stonebreak.world.chunk.mesh.geometry.ChunkMeshOperations;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Represents a chunk of the world using the CCO (Common Chunk Operations) API.
 * This is a complete rewrite using CCO components for:
 * - Unified block operations with automatic dirty tracking
 * - Lock-free state management
 * - Optimized GPU buffer operations
 * - Built-in serialization support
 */
public class Chunk {

    private static final Logger logger = Logger.getLogger(Chunk.class.getName());

    // Position (immutable)
    private final int x;
    private final int z;

    // CCO Components
    private CcoChunkMetadata metadata;
    private final CcoBlockArray blocks;
    private final CcoBlockReader reader;
    private final CcoBlockWriter writer;
    private final CcoAtomicStateManager stateManager;
    private final CcoDirtyTracker dirtyTracker;

    // Mesh data and buffers
    private CcoMeshData pendingMeshData;
    private int vaoId = 0;
    private int vboId = 0;
    private int eboId = 0;
    private int indexCount = 0;
    private boolean meshGenerated = false;

    // Mesh generation helper
    private final ChunkMeshOperations chunkMeshOperations = new ChunkMeshOperations();

    /**
     * Creates a new chunk at the specified position using CCO API.
     */
    public Chunk(int x, int z) {
        this.x = x;
        this.z = z;

        // Initialize block array (16x256x16)
        BlockType[][][] blockArray = new BlockType[16][256][16];
        for (int ix = 0; ix < 16; ix++) {
            for (int iy = 0; iy < 256; iy++) {
                for (int iz = 0; iz < 16; iz++) {
                    blockArray[ix][iy][iz] = BlockType.AIR;
                }
            }
        }

        // Build CCO components using factory
        ComponentBundle bundle = CcoFactory.builder()
            .withPosition(x, z)
            .withBlocks(blockArray)
            .withSeed(0)
            .withInitialState(CcoChunkState.BLOCKS_POPULATED)
            .build();

        // Extract components
        this.metadata = bundle.metadata;
        this.blocks = bundle.blocks;
        this.reader = bundle.reader;
        this.writer = bundle.writer;
        this.stateManager = bundle.stateManager;
        this.dirtyTracker = bundle.dirtyTracker;
    }

    // ===== Block Operations (CCO-based) =====

    /**
     * Gets the block type at the specified local position.
     */
    public BlockType getBlock(int x, int y, int z) {
        return reader.get(x, y, z);
    }

    /**
     * Sets the block type at the specified local position.
     * Automatically marks chunk as dirty for mesh regeneration and saving.
     */
    public void setBlock(int x, int y, int z, BlockType blockType) {
        boolean changed = writer.set(x, y, z, blockType);
        if (changed) {
            metadata = metadata.withUpdatedTimestamp();
        }
    }

    // ===== Mesh Operations (CCO-based) =====

    /**
     * Builds the mesh data for this chunk. This is CPU-intensive and can be run on a worker thread.
     */
    public void buildAndPrepareMeshData(World world) {
        try {
            // Update loading progress
            Game game = Game.getInstance();
            if (game != null && game.getLoadingScreen() != null && game.getLoadingScreen().isVisible()) {
                game.getLoadingScreen().updateProgress("Meshing Chunk");
            }

            // Generate mesh data
            ChunkMeshOperations.MeshData oldMeshData = chunkMeshOperations.generateMeshData(
                blocks.getUnderlyingArray(), x, z, world);

            // Convert to CCO mesh data format
            pendingMeshData = new CcoMeshData(
                oldMeshData.vertexData,
                oldMeshData.textureData,
                oldMeshData.normalData,
                oldMeshData.isWaterData,
                oldMeshData.isAlphaTestedData,
                oldMeshData.indexData,
                oldMeshData.indexCount
            );

            // Mark mesh as ready for GPU upload
            stateManager.removeState(CcoChunkState.MESH_GENERATING);
            stateManager.addState(CcoChunkState.MESH_CPU_READY);

        } catch (Exception e) {
            logger.log(Level.SEVERE, "CRITICAL: Exception during mesh generation for chunk (" + x + ", " + z + "): "
                + e.getMessage(), e);
            stateManager.removeState(CcoChunkState.MESH_GENERATING);
            dirtyTracker.markMeshDirtyOnly();
        }
    }

    /**
     * Applies the prepared mesh data to OpenGL. This must be called on the main GL thread.
     */
    public void applyPreparedDataToGL() {
        if (!stateManager.hasState(CcoChunkState.MESH_CPU_READY)) {
            return; // Data not ready
        }

        try {
            if (pendingMeshData == null || pendingMeshData.isEmpty()) {
                // Empty mesh - clean up existing buffers
                if (meshGenerated) {
                    deleteGLBuffers();
                    meshGenerated = false;
                }
                indexCount = 0;
                stateManager.removeState(CcoChunkState.MESH_CPU_READY);
                stateManager.addState(CcoChunkState.BLOCKS_POPULATED);
                return;
            }

            // Create or update GL buffers
            if (meshGenerated) {
                // Update existing buffers
                updateGLBuffers(pendingMeshData);
            } else {
                // Create new buffers
                createGLBuffers(pendingMeshData);
                meshGenerated = true;
            }

            indexCount = pendingMeshData.getIndexCount();
            stateManager.removeState(CcoChunkState.MESH_CPU_READY);
            stateManager.addState(CcoChunkState.MESH_GPU_UPLOADED);

            // Clear dirty flags after successful upload
            dirtyTracker.clearMeshDirty();

        } catch (Exception e) {
            logger.log(Level.SEVERE, "CRITICAL: Error during GL buffer upload for chunk (" + x + ", " + z + ")", e);
            stateManager.removeState(CcoChunkState.MESH_CPU_READY);
            dirtyTracker.markMeshDirtyOnly();
        } finally {
            pendingMeshData = null;
        }
    }

    /**
     * Creates new GL buffers and uploads mesh data.
     */
    private void createGLBuffers(CcoMeshData meshData) {
        // Create VAO
        vaoId = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vaoId);

        // Create and bind VBO
        vboId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboId);

        // Interleave all vertex data: position(3) + texture(2) + normal(3) + water(1) + alpha(1) = 10 floats/vertex
        int vertexCount = meshData.getVertexCount();
        float[] interleavedData = new float[vertexCount * 10];

        float[] vertices = meshData.getVertexData();
        float[] texCoords = meshData.getTextureData();
        float[] normals = meshData.getNormalData();
        float[] water = meshData.getIsWaterData();
        float[] alpha = meshData.getIsAlphaTestedData();

        for (int i = 0; i < vertexCount; i++) {
            int offset = i * 10;
            // Position
            interleavedData[offset] = vertices[i * 3];
            interleavedData[offset + 1] = vertices[i * 3 + 1];
            interleavedData[offset + 2] = vertices[i * 3 + 2];
            // Texture
            interleavedData[offset + 3] = texCoords[i * 2];
            interleavedData[offset + 4] = texCoords[i * 2 + 1];
            // Normal
            interleavedData[offset + 5] = normals[i * 3];
            interleavedData[offset + 6] = normals[i * 3 + 1];
            interleavedData[offset + 7] = normals[i * 3 + 2];
            // Water and alpha flags
            interleavedData[offset + 8] = water[i];
            interleavedData[offset + 9] = alpha[i];
        }

        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, interleavedData, GL15.GL_STATIC_DRAW);

        // Setup vertex attributes
        int stride = 10 * Float.BYTES;
        GL30.glEnableVertexAttribArray(0); // position
        GL30.glVertexAttribPointer(0, 3, GL15.GL_FLOAT, false, stride, 0);
        GL30.glEnableVertexAttribArray(1); // texCoord
        GL30.glVertexAttribPointer(1, 2, GL15.GL_FLOAT, false, stride, 3 * Float.BYTES);
        GL30.glEnableVertexAttribArray(2); // normal
        GL30.glVertexAttribPointer(2, 3, GL15.GL_FLOAT, false, stride, 5 * Float.BYTES);
        GL30.glEnableVertexAttribArray(3); // isWater
        GL30.glVertexAttribPointer(3, 1, GL15.GL_FLOAT, false, stride, 8 * Float.BYTES);
        GL30.glEnableVertexAttribArray(4); // isAlphaTested
        GL30.glVertexAttribPointer(4, 1, GL15.GL_FLOAT, false, stride, 9 * Float.BYTES);

        // Create and bind EBO
        eboId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, eboId);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, meshData.getIndexData(), GL15.GL_STATIC_DRAW);

        // Unbind
        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
    }

    /**
     * Updates existing GL buffers with new mesh data.
     */
    private void updateGLBuffers(CcoMeshData meshData) {
        GL30.glBindVertexArray(vaoId);

        // Re-upload VBO data
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboId);

        int vertexCount = meshData.getVertexCount();
        float[] interleavedData = new float[vertexCount * 10];

        float[] vertices = meshData.getVertexData();
        float[] texCoords = meshData.getTextureData();
        float[] normals = meshData.getNormalData();
        float[] water = meshData.getIsWaterData();
        float[] alpha = meshData.getIsAlphaTestedData();

        for (int i = 0; i < vertexCount; i++) {
            int offset = i * 10;
            interleavedData[offset] = vertices[i * 3];
            interleavedData[offset + 1] = vertices[i * 3 + 1];
            interleavedData[offset + 2] = vertices[i * 3 + 2];
            interleavedData[offset + 3] = texCoords[i * 2];
            interleavedData[offset + 4] = texCoords[i * 2 + 1];
            interleavedData[offset + 5] = normals[i * 3];
            interleavedData[offset + 6] = normals[i * 3 + 1];
            interleavedData[offset + 7] = normals[i * 3 + 2];
            interleavedData[offset + 8] = water[i];
            interleavedData[offset + 9] = alpha[i];
        }

        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, interleavedData, GL15.GL_STATIC_DRAW);

        // Re-upload EBO data
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, eboId);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, meshData.getIndexData(), GL15.GL_STATIC_DRAW);

        // Unbind
        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
    }

    /**
     * Deletes GL buffers.
     */
    private void deleteGLBuffers() {
        if (vaoId != 0) {
            GL30.glDeleteVertexArrays(vaoId);
            vaoId = 0;
        }
        if (vboId != 0) {
            GL15.glDeleteBuffers(vboId);
            vboId = 0;
        }
        if (eboId != 0) {
            GL15.glDeleteBuffers(eboId);
            eboId = 0;
        }
    }

    /**
     * Renders the chunk.
     */
    public void render() {
        if (!stateManager.isRenderable() || indexCount == 0 || !meshGenerated) {
            return;
        }

        GL30.glBindVertexArray(vaoId);
        GL15.glDrawElements(GL15.GL_TRIANGLES, indexCount, GL15.GL_UNSIGNED_INT, 0);
        GL30.glBindVertexArray(0);
    }

    // ===== Coordinate Operations =====

    /**
     * Converts a local X coordinate to a world X coordinate.
     */
    public int getWorldX(int localX) {
        return CcoCoordinates.localToWorldX(x, localX);
    }

    /**
     * Converts a local Z coordinate to a world Z coordinate.
     */
    public int getWorldZ(int localZ) {
        return CcoCoordinates.localToWorldZ(z, localZ);
    }

    public int getChunkX() {
        return this.x;
    }

    public int getChunkZ() {
        return this.z;
    }

    public int getX() {
        return x;
    }

    public int getZ() {
        return z;
    }

    // ===== State Management (CCO-based) =====

    public boolean areFeaturesPopulated() {
        return stateManager.hasState(CcoChunkState.FEATURES_POPULATED) ||
               metadata.hasStructures();
    }

    public void setFeaturesPopulated(boolean featuresPopulated) {
        if (featuresPopulated) {
            stateManager.addState(CcoChunkState.FEATURES_POPULATED);
            metadata = metadata.withFeaturesPopulated();
        }
    }

    public boolean isMeshGenerated() {
        return meshGenerated;
    }

    public boolean isDataReadyForGL() {
        return stateManager.hasState(CcoChunkState.MESH_CPU_READY);
    }

    public boolean isMeshDataGenerationScheduledOrInProgress() {
        return stateManager.hasState(CcoChunkState.MESH_GENERATING);
    }

    // ===== Dirty Tracking (CCO-based) =====

    /**
     * Checks if the chunk has been modified since last save.
     */
    public boolean isDirty() {
        return dirtyTracker.isDataDirty();
    }

    /**
     * Marks the chunk as dirty (needing to be saved).
     */
    public void markDirty() {
        dirtyTracker.markDataDirtyOnly();
        metadata = metadata.withUpdatedTimestamp();
    }

    /**
     * Marks the chunk as clean (saved to disk).
     */
    public void markClean() {
        dirtyTracker.clearDataDirty();
    }

    // ===== Serialization (CCO-based) =====

    /**
     * Creates a serializable snapshot of this chunk using CCO API.
     * This is the preferred method for serialization.
     */
    public CcoSerializableSnapshot createSnapshot() {
        return CcoSnapshotBuilder.create(metadata, blocks);
    }

    /**
     * Loads chunk data from a CCO snapshot.
     * This is the preferred method for deserialization.
     */
    public void loadFromSnapshot(CcoSerializableSnapshot snapshot) {
        // Update metadata from snapshot
        this.metadata = new CcoChunkMetadata(
            snapshot.getChunkX(),
            snapshot.getChunkZ(),
            metadata.getCreatedTime(),
            snapshot.getLastModified().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
            metadata.getGenerationSeed(),
            metadata.hasStructures(),
            snapshot.isFeaturesPopulated(),
            metadata.hasEntities()
        );

        // Copy block data
        BlockType[][][] snapshotBlocks = snapshot.getBlocks();
        BlockType[][][] currentBlocks = blocks.getUnderlyingArray();
        for (int ix = 0; ix < 16; ix++) {
            for (int iy = 0; iy < 256; iy++) {
                System.arraycopy(snapshotBlocks[ix][iy], 0, currentBlocks[ix][iy], 0, 16);
            }
        }

        dirtyTracker.markBlockChanged();
        stateManager.removeState(CcoChunkState.MESH_GPU_UPLOADED);
        stateManager.removeState(CcoChunkState.MESH_CPU_READY);
    }


    /**
     * Gets the last modification timestamp.
     */
    public java.time.LocalDateTime getLastModified() {
        return metadata.getLastModified();
    }

    /**
     * Sets the last modification timestamp. Used by save system.
     */
    public void setLastModified(java.time.LocalDateTime lastModified) {
        // Convert LocalDateTime to millis and update metadata
        long millis = lastModified.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        this.metadata = new CcoChunkMetadata(
            metadata.getChunkX(),
            metadata.getChunkZ(),
            metadata.getCreatedTime(),
            millis,
            metadata.getGenerationSeed(),
            metadata.hasStructures(),
            metadata.needsDecoration(),
            metadata.hasEntities()
        );
    }

    // ===== Resource Cleanup =====

    /**
     * Cleans up CPU-side resources. Safe to call from any thread.
     */
    public void cleanupCpuResources() {
        pendingMeshData = null;
        indexCount = 0;
    }

    /**
     * Cleans up GPU resources. MUST be called from the main OpenGL thread.
     */
    public void cleanupGpuResources() {
        deleteGLBuffers();
        meshGenerated = false;
    }

    // ===== CCO Component Access =====

    /**
     * Gets the CCO state manager for this chunk.
     */
    public CcoAtomicStateManager getCcoStateManager() {
        return stateManager;
    }

    /**
     * Gets the CCO block reader for efficient block access.
     * Prefer this over getBlocks() for performance-critical read operations.
     */
    public CcoBlockReader getBlockReader() {
        return reader;
    }

    /**
     * Gets the CCO dirty tracker for this chunk.
     */
    public CcoDirtyTracker getCcoDirtyTracker() {
        return dirtyTracker;
    }
}
