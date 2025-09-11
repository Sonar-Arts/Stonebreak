package com.stonebreak.world.chunk.mesh.geometry;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.rendering.WaterEffects;
import com.stonebreak.world.World;

/**
 * Service responsible for generating vertex positions and normals for block faces.
 * Follows Single Responsibility Principle by handling only geometry generation.
 */
public class GeometryGenerator {
    
    /**
     * Generates vertices for a specific face of a block.
     * Returns the number of vertices added (always 12 floats = 4 vertices * 3 components each).
     */
    public int generateFaceVertices(int face, float worldX, float worldY, float worldZ, float blockHeight, 
                                  float[] vertexArray, int vertexIndex) {
        switch (face) {
            case 0 -> { // Top face (y+1)
                float topY = worldY + blockHeight;
                vertexArray[vertexIndex++] = worldX;        vertexArray[vertexIndex++] = topY; vertexArray[vertexIndex++] = worldZ;
                vertexArray[vertexIndex++] = worldX + 1;    vertexArray[vertexIndex++] = topY; vertexArray[vertexIndex++] = worldZ;
                vertexArray[vertexIndex++] = worldX + 1;    vertexArray[vertexIndex++] = topY; vertexArray[vertexIndex++] = worldZ + 1;
                vertexArray[vertexIndex++] = worldX;        vertexArray[vertexIndex++] = topY; vertexArray[vertexIndex++] = worldZ + 1;
            }
            case 1 -> { // Bottom face (y-1)
                vertexArray[vertexIndex++] = worldX;        vertexArray[vertexIndex++] = worldY; vertexArray[vertexIndex++] = worldZ;
                vertexArray[vertexIndex++] = worldX;        vertexArray[vertexIndex++] = worldY; vertexArray[vertexIndex++] = worldZ + 1;
                vertexArray[vertexIndex++] = worldX + 1;    vertexArray[vertexIndex++] = worldY; vertexArray[vertexIndex++] = worldZ + 1;
                vertexArray[vertexIndex++] = worldX + 1;    vertexArray[vertexIndex++] = worldY; vertexArray[vertexIndex++] = worldZ;
            }
            case 2 -> { // Front face (z+1)
                float topY = worldY + blockHeight;
                vertexArray[vertexIndex++] = worldX;        vertexArray[vertexIndex++] = worldY;     vertexArray[vertexIndex++] = worldZ + 1;
                vertexArray[vertexIndex++] = worldX;        vertexArray[vertexIndex++] = topY; vertexArray[vertexIndex++] = worldZ + 1;
                vertexArray[vertexIndex++] = worldX + 1;    vertexArray[vertexIndex++] = topY; vertexArray[vertexIndex++] = worldZ + 1;
                vertexArray[vertexIndex++] = worldX + 1;    vertexArray[vertexIndex++] = worldY;     vertexArray[vertexIndex++] = worldZ + 1;
            }
            case 3 -> { // Back face (z-1)
                float topY = worldY + blockHeight;
                vertexArray[vertexIndex++] = worldX;        vertexArray[vertexIndex++] = worldY;     vertexArray[vertexIndex++] = worldZ;
                vertexArray[vertexIndex++] = worldX + 1;    vertexArray[vertexIndex++] = worldY;     vertexArray[vertexIndex++] = worldZ;
                vertexArray[vertexIndex++] = worldX + 1;    vertexArray[vertexIndex++] = topY; vertexArray[vertexIndex++] = worldZ;
                vertexArray[vertexIndex++] = worldX;        vertexArray[vertexIndex++] = topY; vertexArray[vertexIndex++] = worldZ;
            }
            case 4 -> { // Right face (x+1)
                float topY = worldY + blockHeight;
                vertexArray[vertexIndex++] = worldX + 1;    vertexArray[vertexIndex++] = worldY;     vertexArray[vertexIndex++] = worldZ;
                vertexArray[vertexIndex++] = worldX + 1;    vertexArray[vertexIndex++] = worldY;     vertexArray[vertexIndex++] = worldZ + 1;
                vertexArray[vertexIndex++] = worldX + 1;    vertexArray[vertexIndex++] = topY; vertexArray[vertexIndex++] = worldZ + 1;
                vertexArray[vertexIndex++] = worldX + 1;    vertexArray[vertexIndex++] = topY; vertexArray[vertexIndex++] = worldZ;
            }
            case 5 -> { // Left face (x-1)
                float topY = worldY + blockHeight;
                vertexArray[vertexIndex++] = worldX;    vertexArray[vertexIndex++] = worldY;     vertexArray[vertexIndex++] = worldZ;
                vertexArray[vertexIndex++] = worldX;    vertexArray[vertexIndex++] = topY; vertexArray[vertexIndex++] = worldZ;
                vertexArray[vertexIndex++] = worldX;    vertexArray[vertexIndex++] = topY; vertexArray[vertexIndex++] = worldZ + 1;
                vertexArray[vertexIndex++] = worldX;    vertexArray[vertexIndex++] = worldY;     vertexArray[vertexIndex++] = worldZ + 1;
            }
        }
        return 12; // Always 12 floats added (4 vertices * 3 components each)
    }
    
    /**
     * Generates normals for a specific face of a block.
     * Returns the number of normals added (always 12 floats = 4 normals * 3 components each).
     */
    public int generateFaceNormals(int face, float[] normalArray, int normalIndex) {
        switch (face) {
            case 0 -> { // Top face
                normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 1.0f; normalArray[normalIndex++] = 0.0f;
                normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 1.0f; normalArray[normalIndex++] = 0.0f;
                normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 1.0f; normalArray[normalIndex++] = 0.0f;
                normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 1.0f; normalArray[normalIndex++] = 0.0f;
            }
            case 1 -> { // Bottom face
                normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = -1.0f; normalArray[normalIndex++] = 0.0f;
                normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = -1.0f; normalArray[normalIndex++] = 0.0f;
                normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = -1.0f; normalArray[normalIndex++] = 0.0f;
                normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = -1.0f; normalArray[normalIndex++] = 0.0f;
            }
            case 2 -> { // Front face
                normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 1.0f;
                normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 1.0f;
                normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 1.0f;
                normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 1.0f;
            }
            case 3 -> { // Back face
                normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = -1.0f;
                normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = -1.0f;
                normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = -1.0f;
                normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = -1.0f;
            }
            case 4 -> { // Right face
                normalArray[normalIndex++] = 1.0f; normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 0.0f;
                normalArray[normalIndex++] = 1.0f; normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 0.0f;
                normalArray[normalIndex++] = 1.0f; normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 0.0f;
                normalArray[normalIndex++] = 1.0f; normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 0.0f;
            }
            case 5 -> { // Left face
                normalArray[normalIndex++] = -1.0f; normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 0.0f;
                normalArray[normalIndex++] = -1.0f; normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 0.0f;
                normalArray[normalIndex++] = -1.0f; normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 0.0f;
                normalArray[normalIndex++] = -1.0f; normalArray[normalIndex++] = 0.0f; normalArray[normalIndex++] = 0.0f;
            }
        }
        return 12; // Always 12 floats added (4 normals * 3 components each)
    }
    
    /**
     * Gets the visual height for blocks that can have variable heights.
     */
    public float getBlockHeight(BlockType blockType, float worldX, float worldY, float worldZ, World world) {
        if (blockType == BlockType.WATER) {
            WaterEffects waterEffects = Game.getWaterEffects();
            if (waterEffects != null) {
                return waterEffects.getWaterVisualHeight((int)worldX, (int)worldY, (int)worldZ);
            }
        } else if (blockType == BlockType.SNOW) {
            // Get snow layer height from world
            if (world != null) {
                return world.getSnowLayerManager().getSnowHeight((int)worldX, (int)worldY, (int)worldZ);
            }
        }
        return 1.0f; // Default full height
    }
}