package com.stonebreak.world.chunk.api.mightyMesh.mmsIntegration;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.api.commonChunkOperations.core.CcoChunkData;
import com.stonebreak.world.chunk.api.commonChunkOperations.data.CcoChunkState;
import com.stonebreak.world.chunk.api.commonChunkOperations.state.CcoAtomicStateManager;
import com.stonebreak.world.chunk.api.commonChunkOperations.data.CcoDirtyTracker;
import com.stonebreak.world.chunk.api.mightyMesh.mmsCore.MmsMeshBuilder;
import com.stonebreak.world.chunk.api.mightyMesh.mmsCore.MmsMeshData;
import com.stonebreak.world.chunk.api.mightyMesh.mmsGeometry.MmsCuboidGenerator;
import com.stonebreak.world.chunk.api.mightyMesh.mmsGeometry.MmsCrossGenerator;
import com.stonebreak.world.chunk.api.mightyMesh.mmsGeometry.MmsGeometryService;
import com.stonebreak.world.chunk.api.mightyMesh.mmsTexturing.MmsAtlasTextureMapper;
import com.stonebreak.world.chunk.api.mightyMesh.mmsTexturing.MmsTextureMapper;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Mighty Mesh System - CCO Integration Adapter.
 *
 * Bridges the MMS mesh generation system with the CCO (Common Chunk Operations) API.
 * Handles state management, dirty tracking, and mesh generation coordination.
 *
 * Design Philosophy:
 * - Adapter Pattern: Connects two independent systems
 * - Single Responsibility: Only handles CCO integration
 * - KISS: Simple delegation and state management
 *
 * @since MMS 1.0
 */
public class MmsCcoAdapter {

    private final MmsGeometryService cuboidGenerator;
    private final MmsCrossGenerator crossGenerator;
    private final MmsTextureMapper textureMapper;
    private World world; // Not final - can be set after construction

    /**
     * Creates a CCO adapter with the specified services.
     *
     * @param textureMapper Texture coordinate mapper
     * @param world World instance for neighbor lookups (can be null initially, set later via setWorld)
     */
    public MmsCcoAdapter(MmsTextureMapper textureMapper, World world) {
        if (textureMapper == null) {
            throw new IllegalArgumentException("Texture mapper cannot be null");
        }
        // World can be null during initialization - it will be set later when world is created

        this.cuboidGenerator = new MmsCuboidGenerator();
        this.crossGenerator = new MmsCrossGenerator();
        this.textureMapper = textureMapper;
        this.world = world;
    }

    /**
     * Sets the world instance after construction.
     * Used when MMS is initialized before World is created.
     *
     * @param world World instance
     */
    public void setWorld(World world) {
        if (world == null) {
            throw new IllegalArgumentException("World cannot be null when setting");
        }
        this.world = world;
        System.out.println("[MmsCcoAdapter] World instance set successfully");
    }

    /**
     * Generates mesh data for a chunk using CCO data.
     *
     * @param chunkData CCO chunk data
     * @param stateManager CCO state manager
     * @param dirtyTracker CCO dirty tracker
     * @return Generated mesh data
     */
    public MmsMeshData generateChunkMesh(CcoChunkData chunkData,
                                         CcoAtomicStateManager stateManager,
                                         CcoDirtyTracker dirtyTracker) {

        // Mark as generating
        stateManager.addState(CcoChunkState.MESH_GENERATING);

        try {
            MmsMeshBuilder builder = MmsMeshBuilder.createWithCapacity(
                WorldConfiguration.CHUNK_SIZE * WorldConfiguration.CHUNK_SIZE * 64 // Estimate
            );

            int chunkX = chunkData.getChunkX();
            int chunkZ = chunkData.getChunkZ();

            // Iterate through all blocks in the chunk
            for (int lx = 0; lx < WorldConfiguration.CHUNK_SIZE; lx++) {
                for (int ly = 0; ly < WorldConfiguration.WORLD_HEIGHT; ly++) {
                    for (int lz = 0; lz < WorldConfiguration.CHUNK_SIZE; lz++) {
                        BlockType blockType = chunkData.getBlock(lx, ly, lz);

                        // Skip air blocks
                        if (blockType == BlockType.AIR) {
                            continue;
                        }

                        // Handle cross-section blocks (flowers)
                        if (isCrossBlock(blockType)) {
                            addCrossBlock(builder, blockType, lx, ly, lz, chunkX, chunkZ);
                            continue;
                        }

                        // Handle standard cube blocks with face culling
                        addCubeBlockWithCulling(builder, blockType, lx, ly, lz, chunkX, chunkZ, chunkData);
                    }
                }
            }

            // Build final mesh
            MmsMeshData meshData = builder.build();

            // Update CCO state
            stateManager.removeState(CcoChunkState.MESH_GENERATING);
            stateManager.addState(CcoChunkState.MESH_CPU_READY);

            return meshData;

        } catch (Exception e) {
            // Handle errors
            stateManager.removeState(CcoChunkState.MESH_GENERATING);
            dirtyTracker.markMeshDirtyOnly();
            throw new RuntimeException("Mesh generation failed for chunk (" +
                chunkData.getChunkX() + ", " + chunkData.getChunkZ() + ")", e);
        }
    }

    /**
     * Adds a cross-section block to the mesh builder.
     */
    private void addCrossBlock(MmsMeshBuilder builder, BlockType blockType,
                              int lx, int ly, int lz, int chunkX, int chunkZ) {

        float worldX = lx + chunkX * WorldConfiguration.CHUNK_SIZE;
        float worldY = ly;
        float worldZ = lz + chunkZ * WorldConfiguration.CHUNK_SIZE;

        // Generate geometry (16 vertices = 2 planes × 2 faces × 4 vertices)
        float[] vertices = crossGenerator.generateCrossVertices(worldX, worldY, worldZ);
        float[] normals = crossGenerator.generateCrossNormals();

        // Generate texture coordinates (16 vertices)
        float[] texCoords = textureMapper.generateCrossTextureCoordinates(blockType);

        // Generate flags (cross blocks always use alpha testing, never water)
        float[] waterFlags = new float[16]; // All zeros
        float[] alphaFlags = new float[16];
        for (int i = 0; i < 16; i++) {
            alphaFlags[i] = 1.0f; // Cross blocks need alpha testing
        }

        // Add vertices to builder (16 vertices)
        for (int i = 0; i < 16; i++) {
            int vIdx = i * 3;
            int tIdx = i * 2;

            builder.addVertex(
                vertices[vIdx], vertices[vIdx + 1], vertices[vIdx + 2],
                texCoords[tIdx], texCoords[tIdx + 1],
                normals[vIdx], normals[vIdx + 1], normals[vIdx + 2],
                waterFlags[i], alphaFlags[i]
            );
        }

        // Add indices for cross (4 quads = 24 indices)
        int baseVertex = builder.getVertexCount() - 16;
        int[] crossIndices = crossGenerator.generateCrossIndices(baseVertex);

        // Add all 24 indices to the builder
        for (int index : crossIndices) {
            builder.addIndex(index);
        }
    }

    /**
     * Adds a cube block with face culling to the mesh builder.
     */
    private void addCubeBlockWithCulling(MmsMeshBuilder builder, BlockType blockType,
                                        int lx, int ly, int lz, int chunkX, int chunkZ,
                                        CcoChunkData chunkData) {

        float worldX = lx + chunkX * WorldConfiguration.CHUNK_SIZE;
        float worldY = ly;
        float worldZ = lz + chunkZ * WorldConfiguration.CHUNK_SIZE;

        float blockHeight = cuboidGenerator.calculateBlockHeight(blockType, worldX, worldY, worldZ);

        // Calculate water corner heights if needed
        float[] waterCornerHeights = null;
        if (blockType == BlockType.WATER) {
            waterCornerHeights = cuboidGenerator.calculateWaterCornerHeights(
                (int)worldX, (int)worldY, (int)worldZ, blockHeight
            );
        }

        // Check each face for culling
        for (int face = 0; face < 6; face++) {
            if (!shouldRenderFace(blockType, lx, ly, lz, face, chunkData)) {
                continue; // Face is culled
            }

            // Generate face geometry
            float[] vertices = cuboidGenerator.generateFaceVerticesWithWater(
                face, blockType, worldX, worldY, worldZ, blockHeight, waterCornerHeights, worldY
            );
            float[] normals = cuboidGenerator.generateFaceNormals(face);

            // Generate texture coordinates
            float[] texCoords = textureMapper.generateFaceTextureCoordinates(blockType, face);

            // Generate flags
            float[] waterFlags = textureMapper.generateWaterFlags(face, waterCornerHeights);
            float[] alphaFlags = textureMapper.generateAlphaFlags(blockType);

            // Add face to builder
            builder.beginFace();
            for (int i = 0; i < 4; i++) {
                int vIdx = i * 3;
                int tIdx = i * 2;

                builder.addVertex(
                    vertices[vIdx], vertices[vIdx + 1], vertices[vIdx + 2],
                    texCoords[tIdx], texCoords[tIdx + 1],
                    normals[vIdx], normals[vIdx + 1], normals[vIdx + 2],
                    waterFlags[i], alphaFlags[i]
                );
            }
            builder.endFace();
        }
    }

    /**
     * Determines if a face should be rendered based on adjacent blocks.
     */
    private boolean shouldRenderFace(BlockType blockType, int lx, int ly, int lz,
                                     int face, CcoChunkData chunkData) {

        // Get adjacent block coordinates
        int adjX = lx + getFaceOffsetX(face);
        int adjY = ly + getFaceOffsetY(face);
        int adjZ = lz + getFaceOffsetZ(face);

        // Get adjacent block (handles chunk boundaries via world)
        BlockType adjacentBlock = getAdjacentBlock(adjX, adjY, adjZ, chunkData);

        // Face culling logic
        return shouldRenderAgainst(blockType, adjacentBlock);
    }

    /**
     * Gets adjacent block, handling chunk boundaries.
     */
    private BlockType getAdjacentBlock(int adjX, int adjY, int adjZ, CcoChunkData chunkData) {
        // Check if within current chunk bounds
        if (adjX >= 0 && adjX < WorldConfiguration.CHUNK_SIZE &&
            adjY >= 0 && adjY < WorldConfiguration.WORLD_HEIGHT &&
            adjZ >= 0 && adjZ < WorldConfiguration.CHUNK_SIZE) {
            return chunkData.getBlock(adjX, adjY, adjZ);
        }

        // Out of bounds - would need to query world/neighboring chunks
        // For now, assume air (will render the face)
        return BlockType.AIR;
    }

    /**
     * Determines if a face should render against an adjacent block.
     */
    private boolean shouldRenderAgainst(BlockType blockType, BlockType adjacentBlock) {
        // Always render if adjacent is air
        if (adjacentBlock == BlockType.AIR) {
            return true;
        }

        // Transparent blocks render against different block types
        if (isTransparent(blockType)) {
            return blockType != adjacentBlock;
        }

        // Opaque blocks don't render against other opaque blocks
        return isTransparent(adjacentBlock);
    }

    /**
     * Checks if a block is transparent.
     */
    private boolean isTransparent(BlockType blockType) {
        return blockType == BlockType.WATER ||
               blockType == BlockType.LEAVES ||
               blockType == BlockType.ROSE ||
               blockType == BlockType.DANDELION;
    }

    /**
     * Checks if a block type is a cross-section block.
     */
    private boolean isCrossBlock(BlockType blockType) {
        return blockType == BlockType.ROSE || blockType == BlockType.DANDELION;
    }

    // Face offset helpers
    private int getFaceOffsetX(int face) {
        return switch (face) {
            case 4 -> 1;  // East
            case 5 -> -1; // West
            default -> 0;
        };
    }

    private int getFaceOffsetY(int face) {
        return switch (face) {
            case 0 -> 1;  // Top
            case 1 -> -1; // Bottom
            default -> 0;
        };
    }

    private int getFaceOffsetZ(int face) {
        return switch (face) {
            case 2 -> -1; // North
            case 3 -> 1;  // South
            default -> 0;
        };
    }
}
