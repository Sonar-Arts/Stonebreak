package com.stonebreak.world.chunk.mesh.geometry;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.rendering.WaterEffects;
import com.stonebreak.world.World;
import com.stonebreak.world.WaterLevelManager;

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
     * Generates vertices for water blocks with individual corner heights for smooth slopes.
     * This creates the Minecraft-like effect where water visually decreases over distance.
     *
     * @param face Face index (0-5)
     * @param worldX World X coordinate
     * @param worldY World Y coordinate
     * @param worldZ World Z coordinate
     * @param vertexArray Array to store vertices
     * @param vertexIndex Starting index in vertex array
     * @return Number of floats added to vertex array
     */
    public int generateWaterFaceVertices(int face, float worldX, float worldY, float worldZ,
                                       float[] vertexArray, int vertexIndex) {
        switch (face) {
            case 0 -> { // Top face (y+1) - This is where smooth water slopes are most visible
                // Get individual corner heights for smooth transitions
                float h00 = worldY + WaterLevelManager.getWaterCornerHeight((int)worldX, (int)worldY, (int)worldZ, 0.0f, 0.0f);
                float h10 = worldY + WaterLevelManager.getWaterCornerHeight((int)worldX, (int)worldY, (int)worldZ, 1.0f, 0.0f);
                float h11 = worldY + WaterLevelManager.getWaterCornerHeight((int)worldX, (int)worldY, (int)worldZ, 1.0f, 1.0f);
                float h01 = worldY + WaterLevelManager.getWaterCornerHeight((int)worldX, (int)worldY, (int)worldZ, 0.0f, 1.0f);

                // Generate vertices with individual heights (creates smooth slopes)
                vertexArray[vertexIndex++] = worldX;        vertexArray[vertexIndex++] = h00; vertexArray[vertexIndex++] = worldZ;
                vertexArray[vertexIndex++] = worldX + 1;    vertexArray[vertexIndex++] = h10; vertexArray[vertexIndex++] = worldZ;
                vertexArray[vertexIndex++] = worldX + 1;    vertexArray[vertexIndex++] = h11; vertexArray[vertexIndex++] = worldZ + 1;
                vertexArray[vertexIndex++] = worldX;        vertexArray[vertexIndex++] = h01; vertexArray[vertexIndex++] = worldZ + 1;
            }
            case 1 -> { // Bottom face (y-1) - Use standard generation
                vertexArray[vertexIndex++] = worldX;        vertexArray[vertexIndex++] = worldY; vertexArray[vertexIndex++] = worldZ;
                vertexArray[vertexIndex++] = worldX;        vertexArray[vertexIndex++] = worldY; vertexArray[vertexIndex++] = worldZ + 1;
                vertexArray[vertexIndex++] = worldX + 1;    vertexArray[vertexIndex++] = worldY; vertexArray[vertexIndex++] = worldZ + 1;
                vertexArray[vertexIndex++] = worldX + 1;    vertexArray[vertexIndex++] = worldY; vertexArray[vertexIndex++] = worldZ;
            }
            case 2 -> { // Front face (z+1) - Connect to adjacent water heights
                float leftHeight = worldY + WaterLevelManager.getWaterVisualHeight((int)worldX, (int)worldY, (int)worldZ);
                float rightHeight = worldY + WaterLevelManager.getWaterVisualHeight((int)worldX + 1, (int)worldY, (int)worldZ);

                vertexArray[vertexIndex++] = worldX;        vertexArray[vertexIndex++] = worldY;      vertexArray[vertexIndex++] = worldZ + 1;
                vertexArray[vertexIndex++] = worldX;        vertexArray[vertexIndex++] = leftHeight;  vertexArray[vertexIndex++] = worldZ + 1;
                vertexArray[vertexIndex++] = worldX + 1;    vertexArray[vertexIndex++] = rightHeight; vertexArray[vertexIndex++] = worldZ + 1;
                vertexArray[vertexIndex++] = worldX + 1;    vertexArray[vertexIndex++] = worldY;      vertexArray[vertexIndex++] = worldZ + 1;
            }
            case 3 -> { // Back face (z-1) - Connect to adjacent water heights
                float leftHeight = worldY + WaterLevelManager.getWaterVisualHeight((int)worldX, (int)worldY, (int)worldZ);
                float rightHeight = worldY + WaterLevelManager.getWaterVisualHeight((int)worldX + 1, (int)worldY, (int)worldZ);

                vertexArray[vertexIndex++] = worldX;        vertexArray[vertexIndex++] = worldY;      vertexArray[vertexIndex++] = worldZ;
                vertexArray[vertexIndex++] = worldX + 1;    vertexArray[vertexIndex++] = worldY;      vertexArray[vertexIndex++] = worldZ;
                vertexArray[vertexIndex++] = worldX + 1;    vertexArray[vertexIndex++] = rightHeight; vertexArray[vertexIndex++] = worldZ;
                vertexArray[vertexIndex++] = worldX;        vertexArray[vertexIndex++] = leftHeight;  vertexArray[vertexIndex++] = worldZ;
            }
            case 4 -> { // Right face (x+1) - Connect to adjacent water heights
                float frontHeight = worldY + WaterLevelManager.getWaterVisualHeight((int)worldX, (int)worldY, (int)worldZ);
                float backHeight = worldY + WaterLevelManager.getWaterVisualHeight((int)worldX, (int)worldY, (int)worldZ + 1);

                vertexArray[vertexIndex++] = worldX + 1;    vertexArray[vertexIndex++] = worldY;      vertexArray[vertexIndex++] = worldZ;
                vertexArray[vertexIndex++] = worldX + 1;    vertexArray[vertexIndex++] = worldY;      vertexArray[vertexIndex++] = worldZ + 1;
                vertexArray[vertexIndex++] = worldX + 1;    vertexArray[vertexIndex++] = backHeight;  vertexArray[vertexIndex++] = worldZ + 1;
                vertexArray[vertexIndex++] = worldX + 1;    vertexArray[vertexIndex++] = frontHeight; vertexArray[vertexIndex++] = worldZ;
            }
            case 5 -> { // Left face (x-1) - Connect to adjacent water heights
                float frontHeight = worldY + WaterLevelManager.getWaterVisualHeight((int)worldX, (int)worldY, (int)worldZ);
                float backHeight = worldY + WaterLevelManager.getWaterVisualHeight((int)worldX, (int)worldY, (int)worldZ + 1);

                vertexArray[vertexIndex++] = worldX;    vertexArray[vertexIndex++] = worldY;      vertexArray[vertexIndex++] = worldZ;
                vertexArray[vertexIndex++] = worldX;    vertexArray[vertexIndex++] = frontHeight; vertexArray[vertexIndex++] = worldZ;
                vertexArray[vertexIndex++] = worldX;    vertexArray[vertexIndex++] = backHeight;  vertexArray[vertexIndex++] = worldZ + 1;
                vertexArray[vertexIndex++] = worldX;    vertexArray[vertexIndex++] = worldY;      vertexArray[vertexIndex++] = worldZ + 1;
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
            // Use water level manager for distance-based height calculation
            return WaterLevelManager.getWaterVisualHeight((int)worldX, (int)worldY, (int)worldZ);
        } else if (blockType == BlockType.SNOW) {
            // Get snow layer height from world
            if (world != null) {
                return world.getSnowLayerManager().getSnowHeight((int)worldX, (int)worldY, (int)worldZ);
            }
        }
        return 1.0f; // Default full height
    }
}
