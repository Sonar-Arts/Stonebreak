package com.stonebreak.world.chunk.mesh;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.rendering.WaterEffects;
import com.stonebreak.rendering.core.API.commonBlockResources.models.BlockDefinition;
import com.stonebreak.rendering.core.API.commonBlockResources.resources.CBRResourceManager;
import com.stonebreak.rendering.core.API.commonBlockResources.texturing.TextureResourceManager;
import com.stonebreak.world.World;

/**
 * Handles mesh generation operations for chunks following SOLID principles.
 * This class is responsible for generating mesh data without being coupled to chunk state management.
 */
public class ChunkMeshOperations {
    
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
        public final int vertexCount;
        
        public MeshData(float[] vertexData, float[] textureData, float[] normalData,
                       float[] isWaterData, float[] isAlphaTestedData, int[] indexData, int vertexCount) {
            this.vertexData = vertexData;
            this.textureData = textureData;
            this.normalData = normalData;
            this.isWaterData = isWaterData;
            this.isAlphaTestedData = isAlphaTestedData;
            this.indexData = indexData;
            this.vertexCount = vertexCount;
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
            for (int lx = 0; lx < World.CHUNK_SIZE; lx++) {
                for (int ly = 0; ly < World.WORLD_HEIGHT; ly++) {
                    for (int lz = 0; lz < World.CHUNK_SIZE; lz++) {
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
                            BlockType adjacentBlock = getAdjacentBlock(lx, ly, lz, face, blocks, chunkX, chunkZ, world);
                            
                            // Determine if the face should be rendered
                            boolean renderFace = shouldRenderFace(blockType, adjacentBlock, lx, ly, lz, face, chunkX, chunkZ);

                            if (renderFace) {
                                // Add face vertices, texture coordinates, normals, isWater flags, isAlphaTested flags, and indices
                                index = addFace(lx, ly, lz, face, blockType, index, chunkX, chunkZ, world);
                            }
                        }
                    }
                }
            }
            
            // Copy from pre-allocated arrays to final arrays (only the used portions)
            return createMeshDataResult();
        } // End synchronized block
    }

    /**
     * Determines if a face should be rendered based on block types and water logic
     */
    private boolean shouldRenderFace(BlockType blockType, BlockType adjacentBlock, int lx, int ly, int lz, int face, int chunkX, int chunkZ) {
        if (blockType == BlockType.WATER) {
            // For water blocks, always render faces except when adjacent to the same water level
            if (adjacentBlock == BlockType.WATER) {
                // Check if we should render between water blocks based on water levels
                WaterEffects waterEffects = Game.getWaterEffects();
                if (waterEffects != null) {
                    int worldX = lx + chunkX * World.CHUNK_SIZE;
                    int worldZ = lz + chunkZ * World.CHUNK_SIZE;
                    float currentWaterLevel = waterEffects.getWaterLevel(worldX, ly, worldZ);
                    
                    // Get adjacent block's world coordinates for level check
                    int adjWorldX = worldX;
                    int adjWorldY = ly;
                    int adjWorldZ = worldZ;
                    
                    switch (face) {
                        case 0 -> adjWorldY += 1; // Top
                        case 1 -> adjWorldY -= 1; // Bottom
                        case 2 -> adjWorldZ += 1; // Front
                        case 3 -> adjWorldZ -= 1; // Back
                        case 4 -> adjWorldX += 1; // Right
                        case 5 -> adjWorldX -= 1; // Left
                    }
                    
                    float adjacentWaterLevel = waterEffects.getWaterLevel(adjWorldX, adjWorldY, adjWorldZ);
                    
                    // Render face if water levels are different or if one is a source
                    return currentWaterLevel != adjacentWaterLevel || 
                           waterEffects.isWaterSource(worldX, ly, worldZ) ||
                           waterEffects.isWaterSource(adjWorldX, adjWorldY, adjWorldZ);
                } else {
                    // Fallback: render all water faces if water effects not available
                    return true;
                }
            } else {
                // Water vs non-water: render if adjacent is transparent or air
                return adjacentBlock.isTransparent();
            }
        } else {
            // For non-water blocks, use the original logic
            return (adjacentBlock.isTransparent() && (blockType != BlockType.WATER || adjacentBlock != BlockType.WATER)) ||
                   (blockType.isTransparent() && !adjacentBlock.isTransparent());
        }
    }

    /**
     * Gets the block adjacent to the specified position in the given direction.
     */
    private BlockType getAdjacentBlock(int x, int y, int z, int face, BlockType[][][] blocks, int chunkX, int chunkZ, World world) {
        // Determine adjacent block coordinates based on the face
        // 0: Top, 1: Bottom, 2: Front, 3: Back, 4: Right, 5: Left
        try {
            return switch (face) {
                case 0 -> // Top
                    y + 1 < World.WORLD_HEIGHT ? blocks[x][y + 1][z] : BlockType.AIR;
                case 1 -> // Bottom
                    y - 1 >= 0 ? blocks[x][y - 1][z] : BlockType.AIR;
                case 2 -> { // Front
                    if (z + 1 < World.CHUNK_SIZE) {
                        yield blocks[x][y][z + 1];
                    } else {
                        // Get block from neighboring chunk
                        int worldX = x + chunkX * World.CHUNK_SIZE;
                        int worldZ = z + chunkZ * World.CHUNK_SIZE + 1;
                        yield world.getBlockAt(worldX, y, worldZ);
                    }
                }
                case 3 -> { // Back
                    if (z - 1 >= 0) {
                        yield blocks[x][y][z - 1];
                    } else {
                        // Get block from neighboring chunk
                        int worldX = x + chunkX * World.CHUNK_SIZE;
                        int worldZ = z + chunkZ * World.CHUNK_SIZE - 1;
                        yield world.getBlockAt(worldX, y, worldZ);
                    }
                }
                case 4 -> { // Right
                    if (x + 1 < World.CHUNK_SIZE) {
                        yield blocks[x + 1][y][z];
                    } else {
                        // Get block from neighboring chunk
                        int worldX = x + chunkX * World.CHUNK_SIZE + 1;
                        int worldZ = z + chunkZ * World.CHUNK_SIZE;
                        yield world.getBlockAt(worldX, y, worldZ);
                    }
                }
                case 5 -> { // Left
                    if (x - 1 >= 0) {
                        yield blocks[x - 1][y][z];
                    } else {
                        // Get block from neighboring chunk
                        int worldX = x + chunkX * World.CHUNK_SIZE - 1;
                        int worldZ = z + chunkZ * World.CHUNK_SIZE;
                        yield world.getBlockAt(worldX, y, worldZ);
                    }
                }
                default -> BlockType.AIR;
            };
        } catch (Exception e) {
            // If there's any issue getting the adjacent block, assume it's air
            // This prevents crashes when dealing with chunk borders
            return BlockType.AIR;
        }
    }

    /**
     * Maps internal face index to CBR face string.
     * 0: up, 1: down, 2: south(+Z), 3: north(-Z), 4: east(+X), 5: west(-X)
     */
    private static String mapFaceIndexToName(int faceIndex) {
        return switch (faceIndex) {
            case 0 -> "top";
            case 1 -> "bottom";
            case 2 -> "south"; // +Z
            case 3 -> "north"; // -Z
            case 4 -> "east";  // +X
            case 5 -> "west";  // -X
            default -> "up";
        };
    }

    /**
     * Some atlas lookups may fail and return a full-atlas span (0..1,0..1) or
     * otherwise invalid values. Detect and allow a fallback to direct atlas lookup.
     */
    private static boolean isSuspiciousAtlasSpan(float[] uv) {
        if (uv == null || uv.length < 4) return true;
        // Outside [0,1] range
        if (uv[0] < 0f || uv[1] < 0f || uv[2] > 1f || uv[3] > 1f) return true;
        float du = Math.abs(uv[2] - uv[0]);
        float dv = Math.abs(uv[3] - uv[1]);
        // Extremely large region (likely fallback or error texture)
        return du >= 0.95f || dv >= 0.95f || du <= 0.0f || dv <= 0.0f;
    }
    
    /**
     * Adds a face to the mesh data.
     */
    private int addFace(int x, int y, int z, int face, BlockType blockType, int index, int chunkX, int chunkZ, World world) {
        // Check if we have enough space in our pre-allocated arrays
        if (vertexIndex + 12 > tempVertices.length || 
            textureIndex + 8 > tempTextureCoords.length ||
            normalIndex + 12 > tempNormals.length ||
            flagIndex + 4 > tempIsWaterFlags.length ||
            flagIndex + 4 > tempIsAlphaTestedFlags.length ||
            indexIndex + 6 > tempIndices.length) {
            // Arrays are full, skip this face to avoid overflow
            System.err.println("Warning: Chunk mesh arrays full, skipping face. Vertex: " + vertexIndex + ", Texture: " + textureIndex + ", Normal: " + normalIndex + ", Flag: " + flagIndex + ", Index: " + indexIndex);
            return index;
        }
        // Convert to world coordinates
        float worldX = x + chunkX * World.CHUNK_SIZE;
        float worldY = y;
        float worldZ = z + chunkZ * World.CHUNK_SIZE;
        
        // Get visual height for blocks that can have variable heights
        float blockHeight = 1.0f; // Default full height
        if (blockType == BlockType.WATER) {
            WaterEffects waterEffects = Game.getWaterEffects();
            if (waterEffects != null) {
                blockHeight = waterEffects.getWaterVisualHeight((int)worldX, (int)worldY, (int)worldZ);
            }
        } else if (blockType == BlockType.SNOW) {
            // Get snow layer height from world
            if (world != null) {
                blockHeight = world.getSnowLayerManager().getSnowHeight((int)worldX, (int)worldY, (int)worldZ);
            }
        }
        
        // Define vertices for each face
        switch (face) {
            case 0 -> { // Top face (y+1)
                float topY = worldY + blockHeight;
                // Add vertices
                tempVertices[vertexIndex++] = worldX;        tempVertices[vertexIndex++] = topY; tempVertices[vertexIndex++] = worldZ;
                tempVertices[vertexIndex++] = worldX + 1;    tempVertices[vertexIndex++] = topY; tempVertices[vertexIndex++] = worldZ;
                tempVertices[vertexIndex++] = worldX + 1;    tempVertices[vertexIndex++] = topY; tempVertices[vertexIndex++] = worldZ + 1;
                tempVertices[vertexIndex++] = worldX;        tempVertices[vertexIndex++] = topY; tempVertices[vertexIndex++] = worldZ + 1;
                
                // Add normals
                tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 1.0f; tempNormals[normalIndex++] = 0.0f;
                tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 1.0f; tempNormals[normalIndex++] = 0.0f;
                tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 1.0f; tempNormals[normalIndex++] = 0.0f;
                tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 1.0f; tempNormals[normalIndex++] = 0.0f;
            }
            case 1 -> { // Bottom face (y-1)
                // Add vertices
                tempVertices[vertexIndex++] = worldX;        tempVertices[vertexIndex++] = worldY; tempVertices[vertexIndex++] = worldZ;
                tempVertices[vertexIndex++] = worldX;        tempVertices[vertexIndex++] = worldY; tempVertices[vertexIndex++] = worldZ + 1;
                tempVertices[vertexIndex++] = worldX + 1;    tempVertices[vertexIndex++] = worldY; tempVertices[vertexIndex++] = worldZ + 1;
                tempVertices[vertexIndex++] = worldX + 1;    tempVertices[vertexIndex++] = worldY; tempVertices[vertexIndex++] = worldZ;
                
                // Add normals
                tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = -1.0f; tempNormals[normalIndex++] = 0.0f;
                tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = -1.0f; tempNormals[normalIndex++] = 0.0f;
                tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = -1.0f; tempNormals[normalIndex++] = 0.0f;
                tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = -1.0f; tempNormals[normalIndex++] = 0.0f;
            }
            case 2 -> { // Front face (z+1)
                float topY = worldY + blockHeight;
                // Add vertices
                tempVertices[vertexIndex++] = worldX;        tempVertices[vertexIndex++] = worldY;     tempVertices[vertexIndex++] = worldZ + 1;
                tempVertices[vertexIndex++] = worldX;        tempVertices[vertexIndex++] = topY; tempVertices[vertexIndex++] = worldZ + 1;
                tempVertices[vertexIndex++] = worldX + 1;    tempVertices[vertexIndex++] = topY; tempVertices[vertexIndex++] = worldZ + 1;
                tempVertices[vertexIndex++] = worldX + 1;    tempVertices[vertexIndex++] = worldY;     tempVertices[vertexIndex++] = worldZ + 1;
                
                // Add normals
                tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 1.0f;
                tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 1.0f;
                tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 1.0f;
                tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 1.0f;
            }
            case 3 -> { // Back face (z-1)
                float topY = worldY + blockHeight;
                // Add vertices
                tempVertices[vertexIndex++] = worldX;        tempVertices[vertexIndex++] = worldY;     tempVertices[vertexIndex++] = worldZ;
                tempVertices[vertexIndex++] = worldX + 1;    tempVertices[vertexIndex++] = worldY;     tempVertices[vertexIndex++] = worldZ;
                tempVertices[vertexIndex++] = worldX + 1;    tempVertices[vertexIndex++] = topY; tempVertices[vertexIndex++] = worldZ;
                tempVertices[vertexIndex++] = worldX;        tempVertices[vertexIndex++] = topY; tempVertices[vertexIndex++] = worldZ;
                
                // Add normals
                tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = -1.0f;
                tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = -1.0f;
                tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = -1.0f;
                tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = -1.0f;
            }
            case 4 -> { // Right face (x+1)
                float topY = worldY + blockHeight;
                // Add vertices
                tempVertices[vertexIndex++] = worldX + 1;    tempVertices[vertexIndex++] = worldY;     tempVertices[vertexIndex++] = worldZ;
                tempVertices[vertexIndex++] = worldX + 1;    tempVertices[vertexIndex++] = worldY;     tempVertices[vertexIndex++] = worldZ + 1;
                tempVertices[vertexIndex++] = worldX + 1;    tempVertices[vertexIndex++] = topY; tempVertices[vertexIndex++] = worldZ + 1;
                tempVertices[vertexIndex++] = worldX + 1;    tempVertices[vertexIndex++] = topY; tempVertices[vertexIndex++] = worldZ;
                
                // Add normals
                tempNormals[normalIndex++] = 1.0f; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 0.0f;
                tempNormals[normalIndex++] = 1.0f; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 0.0f;
                tempNormals[normalIndex++] = 1.0f; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 0.0f;
                tempNormals[normalIndex++] = 1.0f; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 0.0f;
            }
            case 5 -> { // Left face (x-1)
                float topY = worldY + blockHeight;
                // Add vertices
                tempVertices[vertexIndex++] = worldX;    tempVertices[vertexIndex++] = worldY;     tempVertices[vertexIndex++] = worldZ;
                tempVertices[vertexIndex++] = worldX;    tempVertices[vertexIndex++] = topY; tempVertices[vertexIndex++] = worldZ;
                tempVertices[vertexIndex++] = worldX;    tempVertices[vertexIndex++] = topY; tempVertices[vertexIndex++] = worldZ + 1;
                tempVertices[vertexIndex++] = worldX;    tempVertices[vertexIndex++] = worldY;     tempVertices[vertexIndex++] = worldZ + 1;
                
                // Add normals
                tempNormals[normalIndex++] = -1.0f; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 0.0f;
                tempNormals[normalIndex++] = -1.0f; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 0.0f;
                tempNormals[normalIndex++] = -1.0f; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 0.0f;
                tempNormals[normalIndex++] = -1.0f; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 0.0f;
            }
        }
        
        // Add texture coordinates and flags
        addTextureCoordinatesAndFlags(blockType, face, worldX, worldY, worldZ, blockHeight);
        
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
     * Adds texture coordinates and flags for a face
     */
    private void addTextureCoordinatesAndFlags(BlockType blockType, int face, float worldX, float worldY, float worldZ, float blockHeight) {
        // Resolve base face texture coordinates via CBR (fallback to legacy if not initialized)
        float texX, texY, texSize;
        float[] uvCoords = null;
        if (CBRResourceManager.isInitialized()) {
            try {
                CBRResourceManager cbr = CBRResourceManager.getInstance();
                TextureResourceManager textureMgr = cbr.getTextureManager();
                BlockDefinition def = cbr.getBlockRegistry().getDefinition(blockType.ordinal()).orElse(null);
                if (def != null) {
                    String faceName = mapFaceIndexToName(face);
                    TextureResourceManager.TextureCoordinates coords = textureMgr.resolveBlockFaceTexture(def, faceName);
                    if (coords != null) {
                        uvCoords = coords.toArray();
                    }
                } else {
                    // Fallback to legacy mapping through CBR texture manager
                    TextureResourceManager.TextureCoordinates coords = textureMgr.resolveBlockType(blockType);
                    if (coords != null) {
                        uvCoords = coords.toArray();
                    }
                }
            } catch (Exception e) {
                // CBR path failed, will fallback to legacy below
            }
        }
        if (uvCoords == null || isSuspiciousAtlasSpan(uvCoords)) {
            // Fallback to modern TextureAtlas directly if available
            Game game = Game.getInstance();
            if (game != null && game.getTextureAtlas() != null) {
                uvCoords = game.getTextureAtlas().getBlockFaceUVs(blockType, BlockType.Face.values()[face]);
            }
            // If still suspicious after atlas lookup, fallback to legacy grid
            if (uvCoords == null || isSuspiciousAtlasSpan(uvCoords)) {
                float[] texCoords = blockType.getTextureCoords(BlockType.Face.values()[face]);
                float legacyU = texCoords[0] / 16.0f;
                float legacyV = texCoords[1] / 16.0f;
                float legacySize = 1.0f / 16.0f;
                uvCoords = new float[] { legacyU, legacyV, legacyU + legacySize, legacyV + legacySize };
            }
        }
        // Derive base tile origin and span
        texX = uvCoords[0];
        texY = uvCoords[1];
        texSize = uvCoords[2] - uvCoords[0];
        
        float u_topLeft, v_topLeft, u_bottomLeft, v_bottomLeft;
        float u_bottomRight, v_bottomRight, u_topRight, v_topRight;
        
        if (blockType == BlockType.WATER) {
            // Special water texture handling
            addWaterTextureCoordinates(face, worldX, worldY, worldZ, blockHeight, texX, texY, texSize);
        } else {
            // For non-water blocks, use texture coordinates from atlas
            if (uvCoords != null) {
                // Use the modern atlas UV coordinates directly [u1, v1, u2, v2]
                u_topLeft = uvCoords[0];
                v_topLeft = uvCoords[1];
                u_bottomLeft = uvCoords[0];
                v_bottomLeft = uvCoords[3];
                u_bottomRight = uvCoords[2];
                v_bottomRight = uvCoords[3];
                u_topRight = uvCoords[2];
                v_topRight = uvCoords[1];
            } else {
                // Fallback to legacy calculated coordinates
                u_topLeft = texX;
                v_topLeft = texY;
                u_bottomLeft = texX;
                v_bottomLeft = texY + texSize;
                u_bottomRight = texX + texSize;
                v_bottomRight = texY + texSize;
                u_topRight = texX + texSize;
                v_topRight = texY;
            }
            
            // Apply UV mapping based on face orientation
            applyUVMapping(face, u_topLeft, v_topLeft, u_bottomLeft, v_bottomLeft, u_bottomRight, v_bottomRight, u_topRight, v_topRight);
        }

        // Add isWater flag for each of the 4 vertices of this face
        float isWaterValue = (blockType == BlockType.WATER) ? 1.0f : 0.0f;
        tempIsWaterFlags[flagIndex] = isWaterValue;
        tempIsWaterFlags[flagIndex + 1] = isWaterValue;
        tempIsWaterFlags[flagIndex + 2] = isWaterValue;
        tempIsWaterFlags[flagIndex + 3] = isWaterValue;
        
        // Add isAlphaTested flag for each of the 4 vertices of this face
        float isAlphaTestedValue;
        if (CBRResourceManager.isInitialized()) {
            try {
                CBRResourceManager cbr = CBRResourceManager.getInstance();
                BlockDefinition def = cbr.getBlockRegistry().getDefinition(blockType.ordinal()).orElse(null);
                if (def != null) {
                    isAlphaTestedValue = (def.getRenderLayer() == BlockDefinition.RenderLayer.CUTOUT) ? 1.0f : 0.0f;
                } else {
                    isAlphaTestedValue = (blockType.isTransparent() && blockType != BlockType.WATER && blockType != BlockType.AIR) ? 1.0f : 0.0f;
                }
            } catch (Exception e) {
                isAlphaTestedValue = (blockType.isTransparent() && blockType != BlockType.WATER && blockType != BlockType.AIR) ? 1.0f : 0.0f;
            }
        } else {
            isAlphaTestedValue = (blockType.isTransparent() && blockType != BlockType.WATER && blockType != BlockType.AIR) ? 1.0f : 0.0f;
        }
        tempIsAlphaTestedFlags[flagIndex] = isAlphaTestedValue;
        tempIsAlphaTestedFlags[flagIndex + 1] = isAlphaTestedValue;
        tempIsAlphaTestedFlags[flagIndex + 2] = isAlphaTestedValue;
        tempIsAlphaTestedFlags[flagIndex + 3] = isAlphaTestedValue;
        
        flagIndex += 4; // Move flag index forward by 4
    }

    /**
     * Adds special water texture coordinates with seamless tiling
     */
    private void addWaterTextureCoordinates(int face, float worldX, float worldY, float worldZ, float blockHeight, float texX, float texY, float texSize) {
        // For water blocks, use continuous world-space texture coordinates for completely seamless tiling
        // This makes the texture flow continuously across all water blocks without any visible boundaries
        float textureScale = 0.0625f; // Very small scale for large-area seamless appearance
        
        float u_topLeft, v_topLeft, u_bottomLeft, v_bottomLeft;
        float u_bottomRight, v_bottomRight, u_topRight, v_topRight;
        
        switch (face) {
            case 0 -> { // Top face - use X,Z world coordinates
                u_topLeft = worldX * textureScale;
                v_topLeft = worldZ * textureScale;
                u_bottomLeft = worldX * textureScale;
                v_bottomLeft = (worldZ + 1) * textureScale;
                u_bottomRight = (worldX + 1) * textureScale;
                v_bottomRight = (worldZ + 1) * textureScale;
                u_topRight = (worldX + 1) * textureScale;
                v_topRight = worldZ * textureScale;
            }
            case 1 -> { // Bottom face - use X,Z world coordinates
                u_topLeft = worldX * textureScale;
                v_topLeft = worldZ * textureScale;
                u_bottomLeft = worldX * textureScale;
                v_bottomLeft = (worldZ + 1) * textureScale;
                u_bottomRight = (worldX + 1) * textureScale;
                v_bottomRight = (worldZ + 1) * textureScale;
                u_topRight = (worldX + 1) * textureScale;
                v_topRight = worldZ * textureScale;
            }
            case 2, 3 -> { // Front/Back faces - use X,Y world coordinates
                u_topLeft = worldX * textureScale;
                v_topLeft = (worldY + blockHeight) * textureScale;
                u_bottomLeft = worldX * textureScale;
                v_bottomLeft = worldY * textureScale;
                u_bottomRight = (worldX + 1) * textureScale;
                v_bottomRight = worldY * textureScale;
                u_topRight = (worldX + 1) * textureScale;
                v_topRight = (worldY + blockHeight) * textureScale;
            }
            case 4, 5 -> { // Left/Right faces - use Z,Y world coordinates  
                u_topLeft = worldZ * textureScale;
                v_topLeft = (worldY + blockHeight) * textureScale;
                u_bottomLeft = worldZ * textureScale;
                v_bottomLeft = worldY * textureScale;
                u_bottomRight = (worldZ + 1) * textureScale;
                v_bottomRight = worldY * textureScale;
                u_topRight = (worldZ + 1) * textureScale;
                v_topRight = (worldY + blockHeight) * textureScale;
            }
            default -> {
                // Fallback to regular texture coordinates
                u_topLeft = texX;
                v_topLeft = texY;
                u_bottomLeft = texX;
                v_bottomLeft = texY + texSize;
                u_bottomRight = texX + texSize;
                v_bottomRight = texY + texSize;
                u_topRight = texX + texSize;
                v_topRight = texY;
            }
        }
        
        // Add subtle pseudo-random offset to break up any remaining grid patterns
        // This creates more natural-looking water texture variation
        float noiseScale = 0.03f;
        float noiseU = (float)(Math.sin(worldX * 0.1f + worldZ * 0.17f) * noiseScale);
        float noiseV = (float)(Math.cos(worldX * 0.13f + worldZ * 0.11f) * noiseScale);
        
        // Apply noise offset
        u_topLeft += noiseU;
        v_topLeft += noiseV;
        u_bottomLeft += noiseU;
        v_bottomLeft += noiseV;
        u_bottomRight += noiseU;
        v_bottomRight += noiseV;
        u_topRight += noiseU;
        v_topRight += noiseV;
        
        // Apply fractional part to keep texture coordinates within 0-1 range while maintaining continuity
        u_topLeft = u_topLeft % 1.0f;
        v_topLeft = v_topLeft % 1.0f;
        u_bottomLeft = u_bottomLeft % 1.0f;
        v_bottomLeft = v_bottomLeft % 1.0f;
        u_bottomRight = u_bottomRight % 1.0f;
        v_bottomRight = v_bottomRight % 1.0f;
        u_topRight = u_topRight % 1.0f;
        v_topRight = v_topRight % 1.0f;
        
        // Handle negative values properly for seamless wrapping
        if (u_topLeft < 0) u_topLeft += 1.0f;
        if (v_topLeft < 0) v_topLeft += 1.0f;
        if (u_bottomLeft < 0) u_bottomLeft += 1.0f;
        if (v_bottomLeft < 0) v_bottomLeft += 1.0f;
        if (u_bottomRight < 0) u_bottomRight += 1.0f;
        if (v_bottomRight < 0) v_bottomRight += 1.0f;
        if (u_topRight < 0) u_topRight += 1.0f;
        if (v_topRight < 0) v_topRight += 1.0f;
        
        // Scale to fit within the water texture's atlas coordinates
        u_topLeft = texX + u_topLeft * texSize;
        v_topLeft = texY + v_topLeft * texSize;
        u_bottomLeft = texX + u_bottomLeft * texSize;
        v_bottomLeft = texY + v_bottomLeft * texSize;
        u_bottomRight = texX + u_bottomRight * texSize;
        v_bottomRight = texY + v_bottomRight * texSize;
        u_topRight = texX + u_topRight * texSize;
        v_topRight = texY + v_topRight * texSize;
        
        // Apply UV mapping based on face orientation
        applyUVMapping(face, u_topLeft, v_topLeft, u_bottomLeft, v_bottomLeft, u_bottomRight, v_bottomRight, u_topRight, v_topRight);
    }

    /**
     * Applies UV mapping based on face orientation
     */
    private void applyUVMapping(int face, float u_topLeft, float v_topLeft, float u_bottomLeft, float v_bottomLeft,
                               float u_bottomRight, float v_bottomRight, float u_topRight, float v_topRight) {
        switch (face) {
            case 0, 1 -> { // Top or Bottom face
                tempTextureCoords[textureIndex++] = u_topLeft; tempTextureCoords[textureIndex++] = v_topLeft;         // V0
                tempTextureCoords[textureIndex++] = u_bottomLeft; tempTextureCoords[textureIndex++] = v_bottomLeft;   // V1
                tempTextureCoords[textureIndex++] = u_bottomRight; tempTextureCoords[textureIndex++] = v_bottomRight; // V2
                tempTextureCoords[textureIndex++] = u_topRight; tempTextureCoords[textureIndex++] = v_topRight;       // V3
            }
            case 2, 5 -> { // Front (+Z) or Left (-X)
                // Vertices for these faces are ordered: BL, TL, TR, BR
                // Desired UVs: BottomLeft, TopLeft, TopRight, BottomRight
                tempTextureCoords[textureIndex++] = u_bottomLeft; tempTextureCoords[textureIndex++] = v_bottomLeft;   // For V0 (BL of face)
                tempTextureCoords[textureIndex++] = u_topLeft; tempTextureCoords[textureIndex++] = v_topLeft;         // For V1 (TL of face)
                tempTextureCoords[textureIndex++] = u_topRight; tempTextureCoords[textureIndex++] = v_topRight;       // For V2 (TR of face)
                tempTextureCoords[textureIndex++] = u_bottomRight; tempTextureCoords[textureIndex++] = v_bottomRight; // For V3 (BR of face)
            }
            case 3 -> { // Back (-Z)
                // Vertices for this face are ordered: BR_face, BL_face, TL_face, TR_face
                // Desired UVs: BottomRight, BottomLeft, TopLeft, TopRight
                tempTextureCoords[textureIndex++] = u_bottomRight; tempTextureCoords[textureIndex++] = v_bottomRight; // For V0 (BR of face)
                tempTextureCoords[textureIndex++] = u_bottomLeft; tempTextureCoords[textureIndex++] = v_bottomLeft;   // For V1 (BL of face)
                tempTextureCoords[textureIndex++] = u_topLeft; tempTextureCoords[textureIndex++] = v_topLeft;         // For V2 (TL of face)
                tempTextureCoords[textureIndex++] = u_topRight; tempTextureCoords[textureIndex++] = v_topRight;       // For V3 (TR of face)
            }
            case 4 -> { // Right (+X)
                // Vertices for this face are ordered: BL_face, BR_face, TR_face, TL_face
                // Desired UVs: BottomLeft, BottomRight, TopRight, TopLeft
                tempTextureCoords[textureIndex++] = u_bottomLeft; tempTextureCoords[textureIndex++] = v_bottomLeft;   // For V0 (BL of face)
                tempTextureCoords[textureIndex++] = u_bottomRight; tempTextureCoords[textureIndex++] = v_bottomRight; // For V1 (BR of face)
                tempTextureCoords[textureIndex++] = u_topRight; tempTextureCoords[textureIndex++] = v_topRight;       // For V2 (TR of face)
                tempTextureCoords[textureIndex++] = u_topLeft; tempTextureCoords[textureIndex++] = v_topLeft;         // For V3 (TL of face)
            }
        }
    }
    
    /**
     * Adds cross-shaped geometry for flower blocks.
     */
    private int addFlowerCross(int x, int y, int z, BlockType blockType, int index, int chunkX, int chunkZ) {
        // Check if we have enough space in our pre-allocated arrays for flower geometry (8 vertices)
        if (vertexIndex + 24 > tempVertices.length || 
            textureIndex + 16 > tempTextureCoords.length ||
            normalIndex + 24 > tempNormals.length ||
            flagIndex + 8 > tempIsWaterFlags.length ||
            flagIndex + 8 > tempIsAlphaTestedFlags.length ||
            indexIndex + 24 > tempIndices.length) {
            // Arrays are full, skip this flower to avoid overflow
            System.err.println("Warning: Chunk mesh arrays full, skipping flower. Vertex: " + vertexIndex + ", Texture: " + textureIndex + ", Normal: " + normalIndex + ", Flag: " + flagIndex + ", Index: " + indexIndex);
            return index;
        }
        // Convert to world coordinates
        float worldX = x + chunkX * World.CHUNK_SIZE;
        float worldY = y;
        float worldZ = z + chunkZ * World.CHUNK_SIZE;
        
        // Offset for centering the cross in the block
        float centerX = worldX + 0.5f;
        float centerZ = worldZ + 0.5f;
        float crossSize = 0.45f; // Slightly smaller than full block
        
        // Get texture coordinates for the flower using CBR system (fallback to atlas)
        float u_left, v_top, u_right, v_bottom;
        float[] uv;
        uv = null;
        if (CBRResourceManager.isInitialized()) {
            try {
                CBRResourceManager cbr = CBRResourceManager.getInstance();
                TextureResourceManager.TextureCoordinates coords;
                BlockDefinition def = cbr.getBlockRegistry().getDefinition(blockType.ordinal()).orElse(null);
                if (def != null) {
                    // For CROSS blocks, still just need full texture coords
                    coords = cbr.getTextureManager().resolveBlockTexture(def);
                } else {
                    coords = cbr.getTextureManager().resolveBlockType(blockType);
                }
                if (coords != null) uv = coords.toArray();
            } catch (Exception e) {
                // Will fallback below
            }
        }
        if (uv == null) {
            Game game = Game.getInstance();
            if (game != null && game.getTextureAtlas() != null) {
                uv = game.getTextureAtlas().getBlockFaceUVs(blockType, BlockType.Face.TOP);
            } else {
                float[] texCoords = blockType.getTextureCoords(BlockType.Face.TOP);
                float tx = texCoords[0] / 16.0f;
                float ty = texCoords[1] / 16.0f;
                float ts = 1.0f / 16.0f;
                uv = new float[] { tx, ty, tx + ts, ty + ts };
            }
        }
        u_left = uv[0];
        v_top = uv[1];
        u_right = uv[2];
        v_bottom = uv[3];
        
        // Only create 2 cross planes (no duplicates for double-sided)
        // First cross plane (diagonal from NW to SE)
        tempVertices[vertexIndex++] = centerX - crossSize; tempVertices[vertexIndex++] = worldY;     tempVertices[vertexIndex++] = centerZ - crossSize; // Bottom NW
        tempVertices[vertexIndex++] = centerX - crossSize; tempVertices[vertexIndex++] = worldY + 1; tempVertices[vertexIndex++] = centerZ - crossSize; // Top NW
        tempVertices[vertexIndex++] = centerX + crossSize; tempVertices[vertexIndex++] = worldY + 1; tempVertices[vertexIndex++] = centerZ + crossSize; // Top SE
        tempVertices[vertexIndex++] = centerX + crossSize; tempVertices[vertexIndex++] = worldY;     tempVertices[vertexIndex++] = centerZ + crossSize; // Bottom SE
        
        // Normals for first plane
        float norm1X = 0.707f, norm1Z = -0.707f;
        tempNormals[normalIndex++] = norm1X; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = norm1Z;
        tempNormals[normalIndex++] = norm1X; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = norm1Z;
        tempNormals[normalIndex++] = norm1X; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = norm1Z;
        tempNormals[normalIndex++] = norm1X; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = norm1Z;
        
        // Texture coordinates for first plane
        tempTextureCoords[textureIndex++] = u_left; tempTextureCoords[textureIndex++] = v_bottom;
        tempTextureCoords[textureIndex++] = u_left; tempTextureCoords[textureIndex++] = v_top;
        tempTextureCoords[textureIndex++] = u_right; tempTextureCoords[textureIndex++] = v_top;
        tempTextureCoords[textureIndex++] = u_right; tempTextureCoords[textureIndex++] = v_bottom;
        
        // Flags for first plane
        tempIsWaterFlags[flagIndex] = 0.0f;
        tempIsWaterFlags[flagIndex + 1] = 0.0f;
        tempIsWaterFlags[flagIndex + 2] = 0.0f;
        tempIsWaterFlags[flagIndex + 3] = 0.0f;
        tempIsAlphaTestedFlags[flagIndex] = 1.0f;
        tempIsAlphaTestedFlags[flagIndex + 1] = 1.0f;
        tempIsAlphaTestedFlags[flagIndex + 2] = 1.0f;
        tempIsAlphaTestedFlags[flagIndex + 3] = 1.0f;
        flagIndex += 4;
        
        // Indices for first plane (front-facing)
        tempIndices[indexIndex++] = index;
        tempIndices[indexIndex++] = index + 1;
        tempIndices[indexIndex++] = index + 2;
        tempIndices[indexIndex++] = index;
        tempIndices[indexIndex++] = index + 2;
        tempIndices[indexIndex++] = index + 3;
        
        // Indices for first plane (back-facing with reversed winding)
        tempIndices[indexIndex++] = index;
        tempIndices[indexIndex++] = index + 3;
        tempIndices[indexIndex++] = index + 2;
        tempIndices[indexIndex++] = index;
        tempIndices[indexIndex++] = index + 2;
        tempIndices[indexIndex++] = index + 1;
        
        index += 4;
        
        // Second cross plane (diagonal from NE to SW)
        tempVertices[vertexIndex++] = centerX + crossSize; tempVertices[vertexIndex++] = worldY;     tempVertices[vertexIndex++] = centerZ - crossSize; // Bottom NE
        tempVertices[vertexIndex++] = centerX + crossSize; tempVertices[vertexIndex++] = worldY + 1; tempVertices[vertexIndex++] = centerZ - crossSize; // Top NE
        tempVertices[vertexIndex++] = centerX - crossSize; tempVertices[vertexIndex++] = worldY + 1; tempVertices[vertexIndex++] = centerZ + crossSize; // Top SW
        tempVertices[vertexIndex++] = centerX - crossSize; tempVertices[vertexIndex++] = worldY;     tempVertices[vertexIndex++] = centerZ + crossSize; // Bottom SW
        
        // Normals for second plane
        float norm2X = -0.707f, norm2Z = 0.707f;
        tempNormals[normalIndex++] = norm2X; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = norm2Z;
        tempNormals[normalIndex++] = norm2X; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = norm2Z;
        tempNormals[normalIndex++] = norm2X; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = norm2Z;
        tempNormals[normalIndex++] = norm2X; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = norm2Z;
        
        // Texture coordinates for second plane
        tempTextureCoords[textureIndex++] = u_left; tempTextureCoords[textureIndex++] = v_bottom;
        tempTextureCoords[textureIndex++] = u_left; tempTextureCoords[textureIndex++] = v_top;
        tempTextureCoords[textureIndex++] = u_right; tempTextureCoords[textureIndex++] = v_top;
        tempTextureCoords[textureIndex++] = u_right; tempTextureCoords[textureIndex++] = v_bottom;
        
        // Flags for second plane
        tempIsWaterFlags[flagIndex] = 0.0f;
        tempIsWaterFlags[flagIndex + 1] = 0.0f;
        tempIsWaterFlags[flagIndex + 2] = 0.0f;
        tempIsWaterFlags[flagIndex + 3] = 0.0f;
        tempIsAlphaTestedFlags[flagIndex] = 1.0f;
        tempIsAlphaTestedFlags[flagIndex + 1] = 1.0f;
        tempIsAlphaTestedFlags[flagIndex + 2] = 1.0f;
        tempIsAlphaTestedFlags[flagIndex + 3] = 1.0f;
        flagIndex += 4;
        
        // Indices for second plane (front-facing)
        tempIndices[indexIndex++] = index;
        tempIndices[indexIndex++] = index + 1;
        tempIndices[indexIndex++] = index + 2;
        tempIndices[indexIndex++] = index;
        tempIndices[indexIndex++] = index + 2;
        tempIndices[indexIndex++] = index + 3;
        
        // Indices for second plane (back-facing with reversed winding)
        tempIndices[indexIndex++] = index;
        tempIndices[indexIndex++] = index + 3;
        tempIndices[indexIndex++] = index + 2;
        tempIndices[indexIndex++] = index;
        tempIndices[indexIndex++] = index + 2;
        tempIndices[indexIndex++] = index + 1;
        
        return index + 4; // Return the next available index
    }

    /**
     * Creates the final mesh data result from temporary arrays
     */
    private MeshData createMeshDataResult() {
        if (vertexIndex > 0) {
            // Safety checks to prevent array overruns
            if (vertexIndex > tempVertices.length || textureIndex > tempTextureCoords.length ||
                normalIndex > tempNormals.length || flagIndex > tempIsWaterFlags.length ||
                flagIndex > tempIsAlphaTestedFlags.length || indexIndex > tempIndices.length) {
                System.err.println("CRITICAL: Array indices exceed bounds during mesh data copy");
                System.err.println("Vertex: " + vertexIndex + "/" + tempVertices.length +
                                 ", Texture: " + textureIndex + "/" + tempTextureCoords.length +
                                 ", Normal: " + normalIndex + "/" + tempNormals.length +
                                 ", Flag: " + flagIndex + "/" + tempIsWaterFlags.length +
                                 ", Index: " + indexIndex + "/" + tempIndices.length);
                // Return empty arrays to prevent crash
                return new MeshData(new float[0], new float[0], new float[0], new float[0], new float[0], new int[0], 0);
            }
            
            float[] vertexData = new float[vertexIndex];
            System.arraycopy(tempVertices, 0, vertexData, 0, vertexIndex);
            
            float[] textureData = new float[textureIndex];
            System.arraycopy(tempTextureCoords, 0, textureData, 0, textureIndex);
            
            float[] normalData = new float[normalIndex];
            System.arraycopy(tempNormals, 0, normalData, 0, normalIndex);
            
            float[] isWaterData = new float[flagIndex];
            System.arraycopy(tempIsWaterFlags, 0, isWaterData, 0, flagIndex);
            
            float[] isAlphaTestedData = new float[flagIndex];
            System.arraycopy(tempIsAlphaTestedFlags, 0, isAlphaTestedData, 0, flagIndex);
            
            int[] indexData = new int[indexIndex];
            System.arraycopy(tempIndices, 0, indexData, 0, indexIndex);
            
            return new MeshData(vertexData, textureData, normalData, isWaterData, isAlphaTestedData, indexData, indexIndex);
        } else {
            // No mesh data generated
            return new MeshData(new float[0], new float[0], new float[0], new float[0], new float[0], new int[0], 0);
        }
    }
}