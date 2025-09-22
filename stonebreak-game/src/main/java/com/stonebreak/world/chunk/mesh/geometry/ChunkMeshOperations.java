package com.stonebreak.world.chunk.mesh.geometry;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;
import com.stonebreak.world.chunk.mesh.texture.FaceRenderingService;
import com.stonebreak.world.chunk.mesh.builder.MeshDataAssembler;
import com.stonebreak.world.chunk.mesh.texture.TextureCoordinateProcessor;

/**
 * Handles mesh generation operations for chunks following SOLID principles.
 * This class coordinates mesh generation using specialized service modules.
 * Follows the Dependency Inversion Principle by depending on service abstractions.
 */
public class ChunkMeshOperations {
    
    // Service modules following SOLID principles
    private final FaceRenderingService faceRenderingService;
    private final GeometryGenerator geometryGenerator;
    private final TextureCoordinateProcessor textureProcessor;
    private final FlowerCrossGenerator flowerGenerator;
    private final MeshDataAssembler meshAssembler;
    
    /**
     * Constructor with dependency injection for service modules.
     */
    public ChunkMeshOperations() {
        this.faceRenderingService = new FaceRenderingService();
        this.geometryGenerator = new GeometryGenerator();
        this.textureProcessor = new TextureCoordinateProcessor();
        this.flowerGenerator = new FlowerCrossGenerator();
        this.meshAssembler = new MeshDataAssembler();
    }
    
    // Static reusable arrays for mesh generation shared across all chunks
    // This dramatically reduces memory usage from ~5MB per chunk to shared arrays
    private static final Object tempArraysLock = new Object();
    private static final float[] tempVertices = new float[262144]; // Doubled size for complex terrain
    private static final float[] tempTextureCoords = new float[174768]; // 2/3 of vertices for texture coords
    private static final float[] tempNormals = new float[262144]; // Same as vertices for normals
    private static final float[] tempIsWaterFlags = new float[131072]; // Match vertex capacity / 2
    private static final float[] tempIsAlphaTestedFlags = new float[131072]; // Match vertex capacity / 2
    private static final int[] tempIndices = new int[393216]; // 1.5x vertices for indices
    
    private int vertexIndex = 0;
    private int textureIndex = 0;
    private int normalIndex = 0;
    private int flagIndex = 0;
    private int indexIndex = 0;

    /**
     * Data container for mesh generation results
     */
    public static class MeshData {
        public final float[] vertexData;
        public final float[] textureData;
        public final float[] normalData;
        public final float[] isWaterData;
        public final float[] isAlphaTestedData;
        public final int[] indexData;
        public final int indexCount; // number of indices to draw
        
        public MeshData(float[] vertexData, float[] textureData, float[] normalData,
                       float[] isWaterData, float[] isAlphaTestedData, int[] indexData, int indexCount) {
            this.vertexData = vertexData;
            this.textureData = textureData;
            this.normalData = normalData;
            this.isWaterData = isWaterData;
            this.isAlphaTestedData = isAlphaTestedData;
            this.indexData = indexData;
            this.indexCount = indexCount;
        }
    }

    /**
     * Generates mesh data for the given chunk blocks.
     * @param blocks The 3D array of blocks in the chunk
     * @param chunkX The chunk's X coordinate
     * @param chunkZ The chunk's Z coordinate
     * @param world The world instance for adjacent block lookups
     * @return MeshData containing all generated mesh information
     */
    public MeshData generateMeshData(BlockType[][][] blocks, int chunkX, int chunkZ, World world) {
        // Synchronize access to shared temporary arrays
        synchronized (tempArraysLock) {
            // Reset counters for reusable arrays
            vertexIndex = 0;
            textureIndex = 0;
            normalIndex = 0;
            flagIndex = 0;
            indexIndex = 0;
            
            int index = 0;
            
            // Iterate through all blocks in the chunk
            for (int lx = 0; lx < WorldConfiguration.CHUNK_SIZE; lx++) {
                for (int ly = 0; ly < WorldConfiguration.WORLD_HEIGHT; ly++) {
                    for (int lz = 0; lz < WorldConfiguration.CHUNK_SIZE; lz++) {
                        BlockType blockType = blocks[lx][ly][lz];
                        
                        // Skip air blocks
                        if (blockType == BlockType.AIR) {
                            continue;
                        }
                        
                        // Handle flowers with cross-shaped geometry
                        if (blockType == BlockType.ROSE || blockType == BlockType.DANDELION) {
                            index = addFlowerCross(lx, ly, lz, blockType, index, chunkX, chunkZ);
                            continue;
                        }
                        
                        // Check each face of the block
                        for (int face = 0; face < 6; face++) {
                            // Check if the adjacent block is solid
                            BlockType adjacentBlock = faceRenderingService.getAdjacentBlock(lx, ly, lz, face, blocks, chunkX, chunkZ, world);
                            
                            // Determine if the face should be rendered
                            boolean renderFace = faceRenderingService.shouldRenderFace(blockType, adjacentBlock, lx, ly, lz, face, chunkX, chunkZ);

                            if (renderFace) {
                                // Add face vertices, texture coordinates, normals, isWater flags, isAlphaTested flags, and indices
                                index = addFace(lx, ly, lz, face, blockType, index, chunkX, chunkZ, world);
                            }
                        }
                    }
                }
            }
            
            // Copy from pre-allocated arrays to final arrays (only the used portions)
            return meshAssembler.createMeshDataResult(tempVertices, tempTextureCoords, tempNormals, 
                                                    tempIsWaterFlags, tempIsAlphaTestedFlags, tempIndices,
                                                    vertexIndex, textureIndex, normalIndex, flagIndex, indexIndex);
        } // End synchronized block
    }

    
    /**
     * Adds a face to the mesh data using modular services.
     */
    private int addFace(int x, int y, int z, int face, BlockType blockType, int index, int chunkX, int chunkZ, World world) {
        // Check if we have enough space in our pre-allocated arrays
        if (meshAssembler.isArraySpaceInsufficient(vertexIndex, textureIndex, normalIndex, flagIndex, indexIndex,
                                            tempVertices, tempTextureCoords, tempNormals, tempIsWaterFlags, 
                                            tempIsAlphaTestedFlags, tempIndices, 12, 8, 12, 4, 6)) {
            // Arrays are full, skip this face to avoid overflow
            meshAssembler.logArrayOverflow("face", vertexIndex, textureIndex, normalIndex, flagIndex, indexIndex);
            return index;
        }
        
        // Convert to world coordinates
        float worldX = x + chunkX * WorldConfiguration.CHUNK_SIZE;
        float worldY = y;
        float worldZ = z + chunkZ * WorldConfiguration.CHUNK_SIZE;
        
        // Get visual height for blocks that can have variable heights
        float blockHeight = geometryGenerator.getBlockHeight(blockType, worldX, worldY, worldZ, world);
        
        // Generate vertices using appropriate method based on block type
        if (blockType == BlockType.WATER) {
            // Use specialized water vertex generation for smooth slopes
            vertexIndex += geometryGenerator.generateWaterFaceVertices(face, worldX, worldY, worldZ, tempVertices, vertexIndex);
        } else {
            // Use standard vertex generation for other blocks
            vertexIndex += geometryGenerator.generateFaceVertices(face, worldX, worldY, worldZ, blockHeight, tempVertices, vertexIndex);
        }
        normalIndex += geometryGenerator.generateFaceNormals(face, tempNormals, normalIndex);
        
        // Add texture coordinates and flags using the texture service
        textureProcessor.addTextureCoordinatesAndFlags(blockType, face, worldX, worldY, worldZ, blockHeight,
                                                     tempTextureCoords, textureIndex, tempIsWaterFlags, 
                                                     tempIsAlphaTestedFlags, flagIndex);
        textureIndex += 8; // 4 vertices * 2 texture coordinates each
        flagIndex += 4; // 4 flags for each vertex
        
        // Add indices
        tempIndices[indexIndex++] = index;
        tempIndices[indexIndex++] = index + 1;
        tempIndices[indexIndex++] = index + 2;
        
        tempIndices[indexIndex++] = index;
        tempIndices[indexIndex++] = index + 2;
        tempIndices[indexIndex++] = index + 3;
        
        return index + 4;
    }

    
    /**
     * Adds cross-shaped geometry for flower blocks using the FlowerCrossGenerator service.
     */
    private int addFlowerCross(int x, int y, int z, BlockType blockType, int index, int chunkX, int chunkZ) {
        // Check if we have enough space in our pre-allocated arrays for flower geometry (8 vertices)
        if (meshAssembler.isArraySpaceInsufficient(vertexIndex, textureIndex, normalIndex, flagIndex, indexIndex,
                                            tempVertices, tempTextureCoords, tempNormals, tempIsWaterFlags, 
                                            tempIsAlphaTestedFlags, tempIndices, 24, 16, 24, 8, 24)) {
            // Arrays are full, skip this flower to avoid overflow
            meshAssembler.logArrayOverflow("flower", vertexIndex, textureIndex, normalIndex, flagIndex, indexIndex);
            return index;
        }
        // Use the FlowerCrossGenerator service to generate the cross geometry
        int verticesAdded = flowerGenerator.generateFlowerCross(x, y, z, blockType, index, chunkX, chunkZ,
                                                               tempVertices, vertexIndex, tempTextureCoords, textureIndex,
                                                               tempNormals, normalIndex, tempIsWaterFlags, 
                                                               tempIsAlphaTestedFlags, flagIndex, tempIndices, indexIndex);
        
        // Update indices based on what was generated
        vertexIndex += 24; // 8 vertices * 3 components each
        textureIndex += 16; // 8 vertices * 2 texture coordinates each
        normalIndex += 24; // 8 vertices * 3 normal components each
        flagIndex += 8; // 8 vertices with flags
        indexIndex += 24; // 4 triangles * 6 indices per cross * 2 planes * 2 sides
        
        return index + verticesAdded; // Return the next available index
    }

}
