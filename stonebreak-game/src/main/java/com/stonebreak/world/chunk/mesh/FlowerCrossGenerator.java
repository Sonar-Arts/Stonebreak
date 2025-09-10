package com.stonebreak.world.chunk.mesh;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.rendering.core.API.commonBlockResources.models.BlockDefinition;
import com.stonebreak.rendering.core.API.commonBlockResources.resources.CBRResourceManager;
import com.stonebreak.rendering.core.API.commonBlockResources.texturing.TextureResourceManager;
import com.stonebreak.world.World;

/**
 * Service responsible for generating cross-shaped geometry for flower blocks.
 * Follows Single Responsibility Principle by handling only flower cross generation.
 */
public class FlowerCrossGenerator {
    
    /**
     * Generates cross-shaped geometry for flower blocks.
     * Returns the number of vertices added to the index counter.
     */
    public int generateFlowerCross(int x, int y, int z, BlockType blockType, int index, int chunkX, int chunkZ,
                                 float[] vertexArray, int vertexIndex, float[] textureArray, int textureIndex,
                                 float[] normalArray, int normalIndex, float[] isWaterFlags, float[] isAlphaTestedFlags,
                                 int flagIndex, int[] indexArray, int indexIndex) {
        
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
        float[] uv = resolveFlowerTextureCoordinates(blockType);
        u_left = uv[0];
        v_top = uv[1];
        u_right = uv[2];
        v_bottom = uv[3];
        
        // Generate first cross plane (diagonal from NW to SE)
        generateCrossPlane(centerX, worldY, centerZ, crossSize, 
                         u_left, v_top, u_right, v_bottom,
                         0.707f, -0.707f, // normals
                         vertexArray, vertexIndex, textureArray, textureIndex,
                         normalArray, normalIndex, isWaterFlags, isAlphaTestedFlags,
                         flagIndex, indexArray, indexIndex, index);
        
        index += 4;
        vertexIndex += 12;
        textureIndex += 8;
        normalIndex += 12;
        flagIndex += 4;
        indexIndex += 12;
        
        // Generate second cross plane (diagonal from NE to SW)
        generateCrossPlane(centerX, worldY, centerZ, crossSize,
                         u_left, v_top, u_right, v_bottom,
                         -0.707f, 0.707f, // normals
                         vertexArray, vertexIndex, textureArray, textureIndex,
                         normalArray, normalIndex, isWaterFlags, isAlphaTestedFlags,
                         flagIndex, indexArray, indexIndex, index, true);
        
        return 8; // Total vertices added (2 planes * 4 vertices each)
    }
    
    /**
     * Resolves texture coordinates for flower blocks using CBR system with fallbacks.
     */
    private float[] resolveFlowerTextureCoordinates(BlockType blockType) {
        float[] uv = null;
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
        return uv;
    }
    
    /**
     * Generates a single cross plane with given parameters.
     */
    private void generateCrossPlane(float centerX, float worldY, float centerZ, float crossSize,
                                  float u_left, float v_top, float u_right, float v_bottom,
                                  float normX, float normZ,
                                  float[] vertexArray, int vertexIndex, float[] textureArray, int textureIndex,
                                  float[] normalArray, int normalIndex, float[] isWaterFlags, float[] isAlphaTestedFlags,
                                  int flagIndex, int[] indexArray, int indexIndex, int index) {
        generateCrossPlane(centerX, worldY, centerZ, crossSize, u_left, v_top, u_right, v_bottom,
                         normX, normZ, vertexArray, vertexIndex, textureArray, textureIndex,
                         normalArray, normalIndex, isWaterFlags, isAlphaTestedFlags,
                         flagIndex, indexArray, indexIndex, index, false);
    }
    
    /**
     * Generates a single cross plane with given parameters.
     */
    private void generateCrossPlane(float centerX, float worldY, float centerZ, float crossSize,
                                  float u_left, float v_top, float u_right, float v_bottom,
                                  float normX, float normZ,
                                  float[] vertexArray, int vertexIndex, float[] textureArray, int textureIndex,
                                  float[] normalArray, int normalIndex, float[] isWaterFlags, float[] isAlphaTestedFlags,
                                  int flagIndex, int[] indexArray, int indexIndex, int index, boolean isSecondPlane) {
        
        if (isSecondPlane) {
            // Second cross plane (diagonal from NE to SW)
            vertexArray[vertexIndex++] = centerX + crossSize; vertexArray[vertexIndex++] = worldY;     vertexArray[vertexIndex++] = centerZ - crossSize; // Bottom NE
            vertexArray[vertexIndex++] = centerX + crossSize; vertexArray[vertexIndex++] = worldY + 1; vertexArray[vertexIndex++] = centerZ - crossSize; // Top NE
            vertexArray[vertexIndex++] = centerX - crossSize; vertexArray[vertexIndex++] = worldY + 1; vertexArray[vertexIndex++] = centerZ + crossSize; // Top SW
            vertexArray[vertexIndex++] = centerX - crossSize; vertexArray[vertexIndex++] = worldY;     vertexArray[vertexIndex++] = centerZ + crossSize; // Bottom SW
        } else {
            // First cross plane (diagonal from NW to SE)
            vertexArray[vertexIndex++] = centerX - crossSize; vertexArray[vertexIndex++] = worldY;     vertexArray[vertexIndex++] = centerZ - crossSize; // Bottom NW
            vertexArray[vertexIndex++] = centerX - crossSize; vertexArray[vertexIndex++] = worldY + 1; vertexArray[vertexIndex++] = centerZ - crossSize; // Top NW
            vertexArray[vertexIndex++] = centerX + crossSize; vertexArray[vertexIndex++] = worldY + 1; vertexArray[vertexIndex++] = centerZ + crossSize; // Top SE
            vertexArray[vertexIndex++] = centerX + crossSize; vertexArray[vertexIndex++] = worldY;     vertexArray[vertexIndex++] = centerZ + crossSize; // Bottom SE
        }
        
        // Normals for the plane
        normalArray[normalIndex++] = normX; normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = normZ;
        normalArray[normalIndex++] = normX; normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = normZ;
        normalArray[normalIndex++] = normX; normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = normZ;
        normalArray[normalIndex++] = normX; normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = normZ;
        
        // Texture coordinates for the plane
        textureArray[textureIndex++] = u_left; textureArray[textureIndex++] = v_bottom;
        textureArray[textureIndex++] = u_left; textureArray[textureIndex++] = v_top;
        textureArray[textureIndex++] = u_right; textureArray[textureIndex++] = v_top;
        textureArray[textureIndex++] = u_right; textureArray[textureIndex++] = v_bottom;
        
        // Flags for the plane
        isWaterFlags[flagIndex] = 0.0f;
        isWaterFlags[flagIndex + 1] = 0.0f;
        isWaterFlags[flagIndex + 2] = 0.0f;
        isWaterFlags[flagIndex + 3] = 0.0f;
        isAlphaTestedFlags[flagIndex] = 1.0f;
        isAlphaTestedFlags[flagIndex + 1] = 1.0f;
        isAlphaTestedFlags[flagIndex + 2] = 1.0f;
        isAlphaTestedFlags[flagIndex + 3] = 1.0f;
        
        // Indices for the plane (front-facing)
        indexArray[indexIndex++] = index;
        indexArray[indexIndex++] = index + 1;
        indexArray[indexIndex++] = index + 2;
        indexArray[indexIndex++] = index;
        indexArray[indexIndex++] = index + 2;
        indexArray[indexIndex++] = index + 3;
        
        // Indices for the plane (back-facing with reversed winding)
        indexArray[indexIndex++] = index;
        indexArray[indexIndex++] = index + 3;
        indexArray[indexIndex++] = index + 2;
        indexArray[indexIndex++] = index;
        indexArray[indexIndex++] = index + 2;
        indexArray[indexIndex++] = index + 1;
    }
}