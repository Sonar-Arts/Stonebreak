package com.stonebreak.world.chunk.api.mightyMesh.mmsIntegration;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.api.commonChunkOperations.core.CcoChunkData;
import com.stonebreak.world.chunk.api.commonChunkOperations.data.CcoChunkState;
import com.stonebreak.world.chunk.api.commonChunkOperations.state.CcoAtomicStateManager;
import com.stonebreak.world.chunk.api.commonChunkOperations.data.CcoDirtyTracker;
import com.stonebreak.world.chunk.api.mightyMesh.mmsCore.MmsBufferLayout;
import com.stonebreak.world.chunk.api.mightyMesh.mmsCore.MmsMeshBuilder;
import com.stonebreak.world.chunk.api.mightyMesh.mmsCore.MmsMeshData;
import com.stonebreak.world.chunk.api.mightyMesh.mmsGeometry.MmsCuboidGenerator;
import com.stonebreak.world.chunk.api.mightyMesh.mmsGeometry.MmsCrossGenerator;
import com.stonebreak.world.chunk.api.mightyMesh.mmsGeometry.MmsGeometryService;
import com.stonebreak.world.chunk.api.mightyMesh.mmsGeometry.MmsWaterGenerator;
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
    private MmsWaterGenerator waterGenerator; // Created when world is set
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

        if (world != null) {
            this.waterGenerator = new MmsWaterGenerator(world, textureMapper);
            System.out.println("[MmsCcoAdapter] Water generator initialized with provided world instance");
        }
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
        this.waterGenerator = new MmsWaterGenerator(world, textureMapper);
        System.out.println("[MmsCcoAdapter] World instance set successfully (water generator initialized)");
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

                        // Handle water blocks with special geometry
                        if (blockType == BlockType.WATER) {
                            addWaterBlockWithCulling(builder, lx, ly, lz, chunkX, chunkZ, chunkData);
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

        // Generate geometry (8 vertices = 2 planes × 4 vertices, double-sided via index winding)
        float[] vertices = crossGenerator.generateCrossVertices(worldX, worldY, worldZ);
        float[] normals = crossGenerator.generateCrossNormals();

        // Generate texture coordinates (8 vertices)
        float[] texCoords = textureMapper.generateCrossTextureCoordinates(blockType);

        // Generate alpha flags (cross blocks always use alpha testing)
        float[] alphaFlags = new float[MmsBufferLayout.VERTICES_PER_CROSS];
        for (int i = 0; i < MmsBufferLayout.VERTICES_PER_CROSS; i++) {
            alphaFlags[i] = 1.0f; // Cross blocks need alpha testing
        }

        // Add vertices to builder (8 vertices)
        for (int i = 0; i < MmsBufferLayout.VERTICES_PER_CROSS; i++) {
            int vIdx = i * 3;
            int tIdx = i * 2;

            builder.addVertex(
                vertices[vIdx], vertices[vIdx + 1], vertices[vIdx + 2],
                texCoords[tIdx], texCoords[tIdx + 1],
                normals[vIdx], normals[vIdx + 1], normals[vIdx + 2],
                0.0f, alphaFlags[i] // No water flags needed
            );
        }

        // Add indices for cross (2 planes with double-sided rendering via index winding = 24 indices)
        int baseVertex = builder.getVertexCount() - MmsBufferLayout.VERTICES_PER_CROSS;
        int[] crossIndices = crossGenerator.generateCrossIndices(baseVertex);

        // Add all 24 indices to the builder
        for (int index : crossIndices) {
            builder.addIndex(index);
        }
    }

    /**
     * Adds a water block with face culling and variable height geometry.
     */
    private void addWaterBlockWithCulling(MmsMeshBuilder builder,
                                         int lx, int ly, int lz, int chunkX, int chunkZ,
                                         CcoChunkData chunkData) {
        if (waterGenerator == null) {
            // Fallback to standard cube if water generator not initialized
            addCubeBlockWithCulling(builder, BlockType.WATER, lx, ly, lz, chunkX, chunkZ, chunkData);
            return;
        }

        float worldX = lx + chunkX * WorldConfiguration.CHUNK_SIZE;
        float worldY = ly;
        float worldZ = lz + chunkZ * WorldConfiguration.CHUNK_SIZE;
        int blockX = (int) Math.floor(worldX);
        int blockY = (int) Math.floor(worldY);
        int blockZ = (int) Math.floor(worldZ);

        // Check each face for culling (water has special culling rules)
        for (int face = 0; face < 6; face++) {
            if (!shouldRenderWaterFace(lx, ly, lz, face, chunkData)) {
                continue; // Face is culled
            }

            // Generate water-specific geometry with variable heights
            float[] vertices = waterGenerator.generateFaceVertices(face, worldX, worldY, worldZ);
            float[] normals = waterGenerator.generateFaceNormals(face);

            // Generate texture coordinates with water height adjustment
            float[] baseTexCoords = textureMapper.generateFaceTextureCoordinates(BlockType.WATER, face);
            float[] texCoords = waterGenerator.generateWaterTextureCoordinates(face, blockX, blockY, blockZ, baseTexCoords);

            // Generate alpha flags (water uses alpha blending, not testing)
            float[] alphaFlags = new float[]{0.0f, 0.0f, 0.0f, 0.0f};

            // Generate water flags with height encoding
            float blockHeight = waterGenerator.generateWaterFlags(face, blockX, blockY, blockZ, 0.875f)[0];
            float[] waterFlags = waterGenerator.generateWaterFlags(face, blockX, blockY, blockZ, blockHeight);

            // Add face to builder
            builder.beginFace();
            for (int i = 0; i < 4; i++) {
                int vIdx = i * 3;
                int tIdx = i * 2;

                builder.addVertex(
                    vertices[vIdx], vertices[vIdx + 1], vertices[vIdx + 2],
                    texCoords[tIdx], texCoords[tIdx + 1],
                    normals[vIdx], normals[vIdx + 1], normals[vIdx + 2],
                    waterFlags[i], alphaFlags[i] // Water flags encode height
                );
            }
            builder.endFace();
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

        // Check each face for culling
        for (int face = 0; face < 6; face++) {
            if (!shouldRenderFace(blockType, lx, ly, lz, face, chunkData)) {
                continue; // Face is culled
            }

            // Generate face geometry (standard cuboid)
            float[] vertices = cuboidGenerator.generateFaceVertices(face, worldX, worldY, worldZ);
            float[] normals = cuboidGenerator.generateFaceNormals(face);

            // Generate texture coordinates
            float[] texCoords = textureMapper.generateFaceTextureCoordinates(blockType, face);

            // Generate alpha flags
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
                    0.0f, alphaFlags[i] // No water flags needed
                );
            }
            builder.endFace();
        }
    }

    /**
     * Determines if a water face should be rendered based on adjacent blocks.
     * Water has special culling rules to prevent water-to-water culling.
     */
    private boolean shouldRenderWaterFace(int lx, int ly, int lz, int face, CcoChunkData chunkData) {
        // Get adjacent block coordinates
        int adjX = lx + getFaceOffsetX(face);
        int adjY = ly + getFaceOffsetY(face);
        int adjZ = lz + getFaceOffsetZ(face);

        // Get adjacent block (handles chunk boundaries via world)
        BlockType adjacentBlock = getAdjacentBlock(adjX, adjY, adjZ, chunkData);

        // Water face culling rules (from old FaceRenderingService):
        if (adjacentBlock == BlockType.WATER) {
            // Never render faces between water blocks - they blend seamlessly
            return false;
        } else {
            // Water vs non-water: render top face when adjacent to opaque blocks,
            // other faces when transparent (but not water)
            if (face == 0) { // Top face
                return !adjacentBlock.isTransparent() || adjacentBlock == BlockType.AIR;
            } else {
                return adjacentBlock.isTransparent() && adjacentBlock != BlockType.WATER;
            }
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
     * Gets adjacent block, handling chunk boundaries by querying world.
     *
     * @param adjX Adjacent block X coordinate (local chunk space)
     * @param adjY Adjacent block Y coordinate (world space)
     * @param adjZ Adjacent block Z coordinate (local chunk space)
     * @param chunkData Current chunk data
     * @return Block type at adjacent position
     */
    private BlockType getAdjacentBlock(int adjX, int adjY, int adjZ, CcoChunkData chunkData) {
        // Check if within current chunk bounds
        if (adjX >= 0 && adjX < WorldConfiguration.CHUNK_SIZE &&
            adjY >= 0 && adjY < WorldConfiguration.WORLD_HEIGHT &&
            adjZ >= 0 && adjZ < WorldConfiguration.CHUNK_SIZE) {
            return chunkData.getBlock(adjX, adjY, adjZ);
        }

        // Out of bounds - query neighboring chunk via world
        // CRITICAL FIX: Only query if neighbor chunk exists to prevent recursive chunk generation
        if (world != null && adjY >= 0 && adjY < WorldConfiguration.WORLD_HEIGHT) {
            // Convert to world coordinates
            int worldX = adjX + chunkData.getChunkX() * WorldConfiguration.CHUNK_SIZE;
            int worldZ = adjZ + chunkData.getChunkZ() * WorldConfiguration.CHUNK_SIZE;

            // Calculate neighbor chunk coordinates
            int neighborChunkX = Math.floorDiv(worldX, WorldConfiguration.CHUNK_SIZE);
            int neighborChunkZ = Math.floorDiv(worldZ, WorldConfiguration.CHUNK_SIZE);

            // Only query if neighbor chunk already exists (don't trigger generation)
            if (world.hasChunkAt(neighborChunkX, neighborChunkZ)) {
                BlockType adjacentBlock = world.getBlockAt(worldX, adjY, worldZ);
                return adjacentBlock != null ? adjacentBlock : BlockType.AIR;
            }
        }

        // Out of world bounds or no world reference - assume air
        return BlockType.AIR;
    }

    /**
     * Determines if a face should render against an adjacent block.
     *
     * Culling Rules:
     * 1. Always render against AIR
     * 2. Transparent blocks render against different block types (e.g., water doesn't cull against water)
     * 3. Opaque blocks cull against other opaque blocks
     * 4. Opaque blocks render against transparent blocks
     *
     * @param blockType The block being rendered
     * @param adjacentBlock The neighboring block
     * @return true if face should be rendered
     */
    private boolean shouldRenderAgainst(BlockType blockType, BlockType adjacentBlock) {
        // Always render if adjacent is air
        if (adjacentBlock == BlockType.AIR) {
            return true;
        }

        // Transparent blocks (water, leaves, flowers) render against different block types
        // This prevents water-to-water face culling, allowing water to blend correctly
        if (isTransparent(blockType)) {
            return blockType != adjacentBlock;
        }

        // Opaque blocks don't render against other opaque blocks (standard culling)
        // But DO render against transparent blocks (e.g., grass underwater should be visible)
        return isTransparent(adjacentBlock);
    }

    /**
     * Checks if a block type is transparent and requires special culling.
     *
     * Transparent blocks:
     * - Water: Semi-transparent liquid
     * - Leaves: Alpha-tested foliage (all tree types)
     * - Flowers: Cross-section blocks with alpha testing
     *
     * @param blockType Block type to check
     * @return true if block is transparent
     */
    private boolean isTransparent(BlockType blockType) {
        return blockType == BlockType.WATER ||
               blockType == BlockType.LEAVES ||
               blockType == BlockType.PINE_LEAVES ||
               blockType == BlockType.ELM_LEAVES ||
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
