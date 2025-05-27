package com.stonebreak;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12; // Added for GL_TEXTURE_BASE_LEVEL and GL_TEXTURE_MAX_LEVEL
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;

/**
 * Handles texture loading and management.
 */
public class TextureAtlas {
    
    private final int textureId;
    private final int textureSize;
    private final int texturePixelSize = 16; // Size of each tile in pixels
    private final ByteBuffer waterTileUpdateBuffer; // Buffer for updating water tile

    // Atlas coordinates for the water tile (assuming previous fix to BlockType.java)
    private static final int WATER_ATLAS_X = 9;
    private static final int WATER_ATLAS_Y = 0;

    // Atlas coordinates for the new RED_SAND block
    private static final int RED_SAND_ATLAS_X = 2;
    private static final int RED_SAND_ATLAS_Y = 1;

    // Atlas coordinates for SANDSTONE textures
    private static final int SANDSTONE_TOP_ATLAS_X = 5;
    private static final int SANDSTONE_TOP_ATLAS_Y = 1;
    private static final int SANDSTONE_SIDE_ATLAS_X = 7;
    private static final int SANDSTONE_SIDE_ATLAS_Y = 1;

    // Atlas coordinates for RED_SANDSTONE textures
    private static final int RED_SANDSTONE_TOP_ATLAS_X = 6;
    private static final int RED_SANDSTONE_TOP_ATLAS_Y = 1;
    private static final int RED_SANDSTONE_SIDE_ATLAS_X = 8;
    private static final int RED_SANDSTONE_SIDE_ATLAS_Y = 1;

    // Atlas coordinates for flowers - MOVED to (10,1) and (11,1)
    private static final int ROSE_ATLAS_X = 10; // Was 7
    private static final int ROSE_ATLAS_Y = 1;
    private static final int DANDELION_ATLAS_X = 11; // Was 8
    private static final int DANDELION_ATLAS_Y = 1;
    
    /**
     * Creates a texture atlas with the specified texture size.
     * The texture size is the number of tiles in the atlas in each dimension.
     */
    public TextureAtlas(int textureSize) {
        this.textureSize = textureSize;
        System.out.println("Creating texture atlas with size: " + textureSize);
        this.textureId = generateTextureAtlas();
        // Initialize the buffer for water tile updates
        this.waterTileUpdateBuffer = BufferUtils.createByteBuffer(texturePixelSize * texturePixelSize * 4);
        System.out.println("Texture atlas created with ID: " + textureId);
    }

    /**
     * Generates pixel data for a single animated water tile.
     * @param time Current animation time.
     * @param buffer The ByteBuffer to fill with RGBA data.
     */
    private void generateWaterTileData(float time, ByteBuffer buffer) {
        buffer.clear(); // Prepare buffer for writing
        for (int y = 0; y < texturePixelSize; y++) { // y within the tile
            for (int x = 0; x < texturePixelSize; x++) { // x within the tile
                // Animated water effect using time
                // Adjust time multipliers for different animation speeds/styles
                float waterDepth = (float) Math.sin((x * 0.2f + y * 0.3f) * 0.5f + time * 1.5f) * 0.3f + 0.7f;
                float waveFactor = (float) Math.sin(x * 0.3f + y * 0.15f + time * 1.0f) * 0.2f + 0.8f;

                byte r_val = (byte) (30 + waveFactor * 20);
                byte g_val = (byte) (100 + waterDepth * 50 + Math.sin(time * 2.0f + x * 0.1f) * 15); // Add some shimmer
                byte b_val = (byte) (200 + waterDepth * 25 + Math.cos(time * 1.8f + y * 0.1f) * 20);
                byte a_val = (byte) (140 + waveFactor * 30 + Math.sin(time + (x+y)*0.05f) * 20); // Vary alpha slightly

                // Clamp values to ensure they are within byte range if necessary, though current logic should be fine.
                buffer.put(r_val);
                buffer.put(g_val);
                buffer.put(b_val);
                buffer.put(a_val);
            }
        }
        buffer.flip(); // Prepare buffer for reading (by OpenGL)
    }

    /**
     * Updates the animated water texture tile on the GPU.
     * @param time Current animation time.
     */
    public void updateAnimatedWater(float time) {
        if (textureId == 0) return; // Atlas not initialized

        generateWaterTileData(time, waterTileUpdateBuffer);

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);

        int offsetX = WATER_ATLAS_X * texturePixelSize;
        int offsetY = WATER_ATLAS_Y * texturePixelSize;

        // Update the specific region of the texture atlas corresponding to the water tile
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, offsetX, offsetY,
                             texturePixelSize, texturePixelSize,
                             GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, waterTileUpdateBuffer);

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0); // Unbind
    }
    
    /**
     * Gets the texture ID of the atlas.
     */
    public int getTextureId() {
        return textureId;
    }
    
    /**
     * Gets the UV coordinates for a block texture.
     * @param x The x coordinate in the atlas (0-based)
     * @param y The y coordinate in the atlas (0-based)
     * @param face The face of the block (for multi-textured blocks)
     * @return An array of UV coordinates [u1, v1, u2, v2] for the texture
     */
    public float[] getUVCoordinates(int x, int y) { // Reverted parameter names for consistency with original intent, will be tileX, tileY in usage
        float tileSize = 1.0f / textureSize;
        float u1 = x * tileSize;
        float v1 = y * tileSize;
        float u2 = u1 + tileSize;
        float v2 = v1 + tileSize;
        
        return new float[] { u1, v1, u2, v2 };
    }
      /**
     * Generates a texture atlas with different colors for block types.
     */
    private int generateTextureAtlas() {
        // Create a texture ID
        int generatedTextureId = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, generatedTextureId);
        
        // Set texture parameters - use clearer settings to ensure textures are visible
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST); 
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        
        // Ensure mipmaps are disabled as we're using GL_NEAREST
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
        
        System.out.println("Generating texture atlas with ID: " + generatedTextureId);
        
        // Create a simple placeholder texture
        // int texturePixelSize = 16; // Now a class field
        int totalSize = textureSize * this.texturePixelSize;
        ByteBuffer buffer = BufferUtils.createByteBuffer(totalSize * totalSize * 4);
        
        // Fill the texture with colors for each block type
        for (int globalY = 0; globalY < totalSize; globalY++) {
            for (int globalX = 0; globalX < totalSize; globalX++) {
                int tileX = globalX / texturePixelSize;
                int tileY = globalY / texturePixelSize;
                
                // Color based on tile position
                byte r, g, b, a;
                
                // Determine color based on tile position (as if it were a block type)
                
                // Special handling for RED_SAND texture
                if (tileX == RED_SAND_ATLAS_X && tileY == RED_SAND_ATLAS_Y) {
                    // Use sand pattern with orangish-red color for RED_SAND
                    int pixelXInTileRedSand = globalX % texturePixelSize;
                    int pixelYInTileRedSand = globalY % texturePixelSize;

                    double noise1_rs = Math.sin(pixelXInTileRedSand * 0.8 + pixelYInTileRedSand * 1.1);
                    double noise2_rs = Math.cos(pixelXInTileRedSand * 0.5 - pixelYInTileRedSand * 0.9);
                    float combinedNoise_rs = (float) ((noise1_rs + noise2_rs) / 4.0 + 0.5);

                    // Base color: Orangish-Red
                    int baseR_rs = 200;
                    int baseG_rs = 100;
                    int baseB_rs = 50;

                    float variation_rs = (combinedNoise_rs - 0.5f) * 30; // Variation from -15 to 15

                    r = (byte) Math.max(0, Math.min(255, (int)(baseR_rs + variation_rs)));
                    g = (byte) Math.max(0, Math.min(255, (int)(baseG_rs + variation_rs * 0.8f))); // Vary green a bit less
                    b = (byte) Math.max(0, Math.min(255, (int)(baseB_rs + variation_rs * 0.6f))); // Vary blue even less for redness

                    a = (byte) 255; // Fully opaque
                    buffer.put(r).put(g).put(b).put(a);
                    continue; // Skip the general switch statement for this tile
                }
                // Special handling for SANDSTONE_TOP texture
                else if (tileX == SANDSTONE_TOP_ATLAS_X && tileY == SANDSTONE_TOP_ATLAS_Y) {
                    int pX = globalX % texturePixelSize;
                    int pY = globalY % texturePixelSize;

                    // Base color: Light beige/yellowish
                    int baseR_st = 235; // 218;
                    int baseG_st = 225; // 210;
                    int baseB_st = 190; // 175;

                    // Noise for subtle speckling
                    double noise1_st = Math.sin(pX * 0.7 + pY * 1.2 + tileX * 0.3);
                    double noise2_st = Math.cos(pX * 0.4 - pY * 0.9 + tileY * 0.2);
                    float combinedNoise_st = (float) ((noise1_st + noise2_st) / 4.0 + 0.5); // 0.0 to 1.0

                    float variation_st = (combinedNoise_st - 0.5f) * 25; // Variation from -12.5 to 12.5

                    r = (byte) Math.max(0, Math.min(255, (int)(baseR_st + variation_st)));
                    g = (byte) Math.max(0, Math.min(255, (int)(baseG_st + variation_st)));
                    b = (byte) Math.max(0, Math.min(255, (int)(baseB_st + variation_st * 0.8f))); // Less variation in blue

                    a = (byte) 255;
                    buffer.put(r).put(g).put(b).put(a);
                    continue;
                }
                // Special handling for ROSE texture
                else if (tileX == ROSE_ATLAS_X && tileY == ROSE_ATLAS_Y) {
                    int pX_tile = globalX % texturePixelSize;
                    int pY_tile = globalY % texturePixelSize;
                    byte r_pixel = 0, g_pixel = 0, b_pixel = 0, a_pixel = 0; // Default transparent

                    int h_offset = 4; // Horizontal offset for 8-pixel wide image on 16-pixel tile

                    // Rose Petals (Red: 204,0,0)
                    if (pX_tile == h_offset + 3 && pY_tile == 0) { r_pixel=(byte)204; g_pixel=(byte)0; b_pixel=(byte)0; a_pixel=(byte)255; }
                    else if (pX_tile >= h_offset + 2 && pX_tile <= h_offset + 3 && pY_tile == 1) { r_pixel=(byte)204; g_pixel=(byte)0; b_pixel=(byte)0; a_pixel=(byte)255; }
                    else if ((pX_tile == h_offset + 0 || (pX_tile >= h_offset + 2 && pX_tile <= h_offset + 5)) && pY_tile == 2) { r_pixel=(byte)204; g_pixel=(byte)0; b_pixel=(byte)0; a_pixel=(byte)255; }
                    else if (pX_tile >= h_offset + 1 && pX_tile <= h_offset + 5 && pY_tile == 3) { r_pixel=(byte)204; g_pixel=(byte)0; b_pixel=(byte)0; a_pixel=(byte)255; }
                    else if ((pX_tile == h_offset + 2 || pX_tile == h_offset + 4) && pY_tile == 4) { r_pixel=(byte)204; g_pixel=(byte)0; b_pixel=(byte)0; a_pixel=(byte)255; }
                    // Stem (Green: 34,177,76)
                    else if (pX_tile >= h_offset + 3 && pX_tile <= h_offset + 4 && pY_tile == 5) { r_pixel=(byte)34; g_pixel=(byte)177; b_pixel=(byte)76; a_pixel=(byte)255; }
                    // Thorns (Brown: 136,68,0) & Stem
                    else if (pX_tile == h_offset + 2 && pY_tile == 6) { r_pixel=(byte)136; g_pixel=(byte)68; b_pixel=(byte)0; a_pixel=(byte)255; }
                    else if (pX_tile >= h_offset + 3 && pX_tile <= h_offset + 4 && pY_tile == 6) { r_pixel=(byte)34; g_pixel=(byte)177; b_pixel=(byte)76; a_pixel=(byte)255; }
                    else if (pX_tile >= h_offset + 3 && pX_tile <= h_offset + 4 && pY_tile == 7) { r_pixel=(byte)34; g_pixel=(byte)177; b_pixel=(byte)76; a_pixel=(byte)255; }
                    else if (pX_tile == h_offset + 5 && pY_tile == 7) { r_pixel=(byte)136; g_pixel=(byte)68; b_pixel=(byte)0; a_pixel=(byte)255; }
                    else if (pX_tile >= h_offset + 3 && pX_tile <= h_offset + 4 && pY_tile == 8) { r_pixel=(byte)34; g_pixel=(byte)177; b_pixel=(byte)76; a_pixel=(byte)255; }
                    else if (pX_tile == h_offset + 2 && pY_tile == 9) { r_pixel=(byte)136; g_pixel=(byte)68; b_pixel=(byte)0; a_pixel=(byte)255; }
                    else if (pX_tile >= h_offset + 3 && pX_tile <= h_offset + 4 && pY_tile == 9) { r_pixel=(byte)34; g_pixel=(byte)177; b_pixel=(byte)76; a_pixel=(byte)255; }
                    else if (pX_tile >= h_offset + 3 && pX_tile <= h_offset + 4 && pY_tile == 10) { r_pixel=(byte)34; g_pixel=(byte)177; b_pixel=(byte)76; a_pixel=(byte)255; }
                    else if (pX_tile == h_offset + 5 && pY_tile == 10) { r_pixel=(byte)136; g_pixel=(byte)68; b_pixel=(byte)0; a_pixel=(byte)255; }
                    else if (pX_tile >= h_offset + 3 && pX_tile <= h_offset + 4 && pY_tile == 11) { r_pixel=(byte)34; g_pixel=(byte)177; b_pixel=(byte)76; a_pixel=(byte)255; }
                    else if (pX_tile == h_offset + 2 && pY_tile == 12) { r_pixel=(byte)136; g_pixel=(byte)68; b_pixel=(byte)0; a_pixel=(byte)255; }
                    else if (pX_tile >= h_offset + 3 && pX_tile <= h_offset + 4 && pY_tile == 12) { r_pixel=(byte)34; g_pixel=(byte)177; b_pixel=(byte)76; a_pixel=(byte)255; }
                    else if (pX_tile >= h_offset + 3 && pX_tile <= h_offset + 4 && pY_tile == 13) { r_pixel=(byte)34; g_pixel=(byte)177; b_pixel=(byte)76; a_pixel=(byte)255; }
                    else if (pX_tile >= h_offset + 3 && pX_tile <= h_offset + 4 && pY_tile == 14) { r_pixel=(byte)34; g_pixel=(byte)177; b_pixel=(byte)76; a_pixel=(byte)255; }
                    else if (pX_tile == h_offset + 3 && pY_tile == 15) { r_pixel=(byte)34; g_pixel=(byte)177; b_pixel=(byte)76; a_pixel=(byte)255; }
                    
                    buffer.put(r_pixel).put(g_pixel).put(b_pixel).put(a_pixel);
                    continue;
                }
                // Special handling for SANDSTONE_SIDE texture
                else if (tileX == SANDSTONE_SIDE_ATLAS_X && tileY == SANDSTONE_SIDE_ATLAS_Y) {
                    int pX = globalX % texturePixelSize;
                    int pY = globalY % texturePixelSize;

                    // Base color: Similar to top, slightly more saturated or varied
                    int baseR_ss = 220;
                    int baseG_ss = 200;
                    int baseB_ss = 160;

                    // Horizontal banding
                    // Bands are roughly 3-5 pixels high. Let's try 4 pixels.
                    float bandShadeFactor;

                    // Introduce slight waviness to bands based on pX
                    int wavyPY = pY + (int)(Math.sin(pX * 0.3 + tileX * 0.5) * 1.5); // tileX adds variation per tile instance
                    int band = (wavyPY / 4) % 4; // Declare and assign here


                    bandShadeFactor = switch (band) {
                        case 0 -> 0.0f;  // Base
                        case 1 -> -0.08f; // Slightly darker
                        case 2 -> 0.05f; // Slightly lighter
                        case 3 -> -0.05f; // Medium dark
                        default -> 0.0f; // Should not happen with % 4
                    };
                    
                    // Add overall noise for roughness
                    double noise1_ss = Math.sin(pX * 0.6 + pY * 1.1 + tileY * 0.4);
                    double noise2_ss = Math.cos(pX * 0.3 - pY * 0.8 + tileX * 0.6);
                    float combinedNoise_ss = (float) ((noise1_ss + noise2_ss) / 4.0 + 0.5); // 0.0 to 1.0

                    float variation_ss = (combinedNoise_ss - 0.5f) * 35; // Variation from -17.5 to 17.5

                    r = (byte) Math.max(0, Math.min(255, (int)(baseR_ss * (1 + bandShadeFactor) + variation_ss)));
                    g = (byte) Math.max(0, Math.min(255, (int)(baseG_ss * (1 + bandShadeFactor * 0.9f) + variation_ss)));
                    b = (byte) Math.max(0, Math.min(255, (int)(baseB_ss * (1 + bandShadeFactor * 0.8f) + variation_ss * 0.7f)));
                    


// Add occasional darker, brownish inclusions
                    if (combinedNoise_ss < 0.12) { // 12% chance for an inclusion
                        // Define a specific color for inclusions - a darker, slightly desaturated orangey-brown
                        r = (byte)180; 
                        g = (byte)150; 
                        b = (byte)110; 
                    }
                    a = (byte) 255;
                    buffer.put(r).put(g).put(b).put(a);
                    continue;
                }
                // Special handling for RED_SANDSTONE_TOP texture
                else if (tileX == RED_SANDSTONE_TOP_ATLAS_X && tileY == RED_SANDSTONE_TOP_ATLAS_Y) {
                    int pX = globalX % texturePixelSize;
                    int pY = globalY % texturePixelSize;

                    // Base color: Red Sand palette
                    int baseR_rst = 200;
                    int baseG_rst = 100;
                    int baseB_rst = 50;

                    // Noise for subtle speckling (same logic as Sandstone Top)
                    double noise1_rst = Math.sin(pX * 0.7 + pY * 1.2 + tileX * 0.3);
                    double noise2_rst = Math.cos(pX * 0.4 - pY * 0.9 + tileY * 0.2);
                    float combinedNoise_rst = (float) ((noise1_rst + noise2_rst) / 4.0 + 0.5); // 0.0 to 1.0

                    float variation_rst = (combinedNoise_rst - 0.5f) * 25; // Variation from -12.5 to 12.5

                    r = (byte) Math.max(0, Math.min(255, (int)(baseR_rst + variation_rst)));
                    g = (byte) Math.max(0, Math.min(255, (int)(baseG_rst + variation_rst * 0.8f))); // Vary green a bit less
                    b = (byte) Math.max(0, Math.min(255, (int)(baseB_rst + variation_rst * 0.6f))); // Vary blue even less

                    a = (byte) 255;
                    buffer.put(r).put(g).put(b).put(a);
                    continue;
                }
                // Special handling for DANDELION texture
                else if (tileX == DANDELION_ATLAS_X && tileY == DANDELION_ATLAS_Y) {
                    int pX_tile = globalX % texturePixelSize;
                    int pY_tile = globalY % texturePixelSize;
                    // Changed default transparent to white (255,255,255,0) from black (0,0,0,0)
                    byte r_pixel = (byte)255, g_pixel = (byte)255, b_pixel = (byte)255, a_pixel = 0;

                    int h_offset = 4; // Horizontal offset for 7-pixel wide image on 16-pixel tile
                    int v_offset = 3; // Vertical offset for 13-pixel tall image (bottom aligned)

                    // Flower Petals (Yellow: 255,217,26 / Orange: 255,175,23)
                    // Y=0 of image (tile pY_tile = v_offset + 0 = 3)
                    if (pY_tile == v_offset + 0 && pX_tile >= h_offset + 2 && pX_tile <= h_offset + 4) { r_pixel=(byte)255; g_pixel=(byte)217; b_pixel=(byte)26; a_pixel=(byte)255; }
                    // Y=1 of image (tile pY_tile = v_offset + 1 = 4)
                    else if (pY_tile == v_offset + 1 && pX_tile >= h_offset + 1 && pX_tile <= h_offset + 5) { r_pixel=(byte)255; g_pixel=(byte)217; b_pixel=(byte)26; a_pixel=(byte)255; }
                    // Y=2 of image (tile pY_tile = v_offset + 2 = 5)
                    else if (pY_tile == v_offset + 2) {
                        if ((pX_tile >= h_offset + 0 && pX_tile <= h_offset + 2) || (pX_tile >= h_offset + 4 && pX_tile <= h_offset + 6)) { r_pixel=(byte)255; g_pixel=(byte)217; b_pixel=(byte)26; a_pixel=(byte)255; } // Yellow
                        else if (pX_tile == h_offset + 3) { r_pixel=(byte)255; g_pixel=(byte)175; b_pixel=(byte)23; a_pixel=(byte)255; } // Orange
                    }
                    // Y=3 of image (tile pY_tile = v_offset + 3 = 6)
                    else if (pY_tile == v_offset + 3) {
                        if ((pX_tile >= h_offset + 0 && pX_tile <= h_offset + 1) || (pX_tile >= h_offset + 5 && pX_tile <= h_offset + 6)) { r_pixel=(byte)255; g_pixel=(byte)217; b_pixel=(byte)26; a_pixel=(byte)255; } // Yellow
                        else if (pX_tile == h_offset + 2 || pX_tile == h_offset + 4) { r_pixel=(byte)255; g_pixel=(byte)175; b_pixel=(byte)23; a_pixel=(byte)255; } // Orange
                    }
                    // Y=4 of image (tile pY_tile = v_offset + 4 = 7)
                    else if (pY_tile == v_offset + 4) {
                        if (pX_tile == h_offset + 1 || pX_tile == h_offset + 5) { r_pixel=(byte)255; g_pixel=(byte)217; b_pixel=(byte)26; a_pixel=(byte)255; } // Yellow
                        else if (pX_tile >= h_offset + 2 && pX_tile <= h_offset + 4) { r_pixel=(byte)255; g_pixel=(byte)175; b_pixel=(byte)23; a_pixel=(byte)255; } // Orange
                    }
                    // Y=5 of image (tile pY_tile = v_offset + 5 = 8)
                    else if (pY_tile == v_offset + 5 && pX_tile >= h_offset + 2 && pX_tile <= h_offset + 4) { r_pixel=(byte)255; g_pixel=(byte)217; b_pixel=(byte)26; a_pixel=(byte)255; } // Yellow
                    // Stem (Green: 0,154,23) - Y=6 to Y=12 of image
                    else if (pX_tile == h_offset + 3 && pY_tile >= v_offset + 6 && pY_tile <= v_offset + 12) { r_pixel=(byte)0; g_pixel=(byte)154; b_pixel=(byte)23; a_pixel=(byte)255; }
                    
                    buffer.put(r_pixel).put(g_pixel).put(b_pixel).put(a_pixel);
                    continue;
                }
                // Special handling for RED_SANDSTONE_SIDE texture
                else if (tileX == RED_SANDSTONE_SIDE_ATLAS_X && tileY == RED_SANDSTONE_SIDE_ATLAS_Y) {
                    int pX = globalX % texturePixelSize;
                    int pY = globalY % texturePixelSize;

                    // Base color: Red Sand palette
                    int baseR_rss = 200;
                    int baseG_rss = 100;
                    int baseB_rss = 50;

                    // Horizontal banding (same logic as Sandstone Side)
                    int wavyPY_rss = pY + (int)(Math.sin(pX * 0.3 + tileX * 0.5) * 1.5);
                    int band_rss = (wavyPY_rss / 4) % 4;
                    float bandShadeFactor_rss;

                    bandShadeFactor_rss = switch (band_rss) {
                        case 0 -> 0.0f;
                        case 1 -> -0.08f;
                        case 2 -> 0.05f;
                        case 3 -> -0.05f;
                        default -> 0.0f; // Should not happen with % 4
                    };
                    
                    // Add overall noise for roughness (same logic as Sandstone Side)
                    double noise1_rss = Math.sin(pX * 0.6 + pY * 1.1 + tileY * 0.4);
                    double noise2_rss = Math.cos(pX * 0.3 - pY * 0.8 + tileX * 0.6);
                    float combinedNoise_rss = (float) ((noise1_rss + noise2_rss) / 4.0 + 0.5);

                    float variation_rss = (combinedNoise_rss - 0.5f) * 35;

                    r = (byte) Math.max(0, Math.min(255, (int)(baseR_rss * (1 + bandShadeFactor_rss) + variation_rss)));
                    g = (byte) Math.max(0, Math.min(255, (int)(baseG_rss * (1 + bandShadeFactor_rss * 0.9f) + variation_rss * 0.8f)));
                    b = (byte) Math.max(0, Math.min(255, (int)(baseB_rss * (1 + bandShadeFactor_rss * 0.8f) + variation_rss * 0.6f)));
                    
                    // Add occasional darker, reddish-brown inclusions
                    if (combinedNoise_rss < 0.12) {
                        r = (byte)160; // Darker red
                        g = (byte)70;  // Darker, less saturated green component
                        b = (byte)30;  // Darker, less saturated blue component
                    }

                    a = (byte) 255;
                    buffer.put(r).put(g).put(b).put(a);
                    continue;
                }
                // Special handling for new snow-themed blocks in row 2 (y=2)
                else if (tileY == 2) {
                    if (tileX == 0) { // Snow top texture (0,2)
                        int pixelX_snow = globalX % texturePixelSize;
                        int pixelY_snow = globalY % texturePixelSize;
                        
                        // White snow with subtle variations
                        int noiseVal_snow = (pixelX_snow * 13 + pixelY_snow * 29 + tileX * 11 + tileY * 17) % 21;
                        
                        if (noiseVal_snow < 7) { // Pure white
                            r = (byte) 255; g = (byte) 255; b = (byte) 255;
                        } else if (noiseVal_snow < 14) { // Slightly off-white
                            r = (byte) 250; g = (byte) 250; b = (byte) 252;
                        } else { // Very light blue tint
                            r = (byte) 248; g = (byte) 250; b = (byte) 255;
                        }
                        a = (byte) 255;
                        buffer.put(r).put(g).put(b).put(a);
                        continue;
                    } else if (tileX == 1) { // Snow side texture (1,2) / Snowy leaves texture
                        int pixelX_snowside = globalX % texturePixelSize;
                        int pixelY_snowside = globalY % texturePixelSize;
                        
                        // For snow side: white top portion, dirt bottom portion
                        int snowLayerHeight = 3; // Top 3 pixels are snow
                        
                        if (pixelY_snowside < snowLayerHeight) { // Snow part (top)
                            int noiseVal_snowside = (pixelX_snowside * 17 + pixelY_snowside * 23) % 21;
                            if (noiseVal_snowside < 7) {
                                r = (byte) 250; g = (byte) 250; b = (byte) 252;
                            } else if (noiseVal_snowside < 14) {
                                r = (byte) 245; g = (byte) 248; b = (byte) 250;
                            } else {
                                r = (byte) 240; g = (byte) 245; b = (byte) 248;
                            }
                        } else { // Dirt part (below snow) - EXACT COPY of case 2 (Dirt block) logic
                            // 'globalX' and 'globalY' are global atlas pixel coordinates.
                            float dirtX_snowside = (float) Math.sin(globalX * 0.7 + globalY * 0.3) * 0.5f + 0.5f;
                            float dirtY_snowside = (float) Math.cos(globalX * 0.4 + globalY * 0.8) * 0.5f + 0.5f;
                            float dirtNoise_snowside = (dirtX_snowside + dirtY_snowside) * 0.5f;
                            
                            r = (byte) (135 + dirtNoise_snowside * 40);
                            g = (byte) (95 + dirtNoise_snowside * 35);
                            b = (byte) (70 + dirtNoise_snowside * 25);
                            
                            // Add occasional small rocks or roots (exact from case 2)
                            if ((globalX % 5 == 0 && globalY % 5 == 0) ||
                                ((globalX+2) % 7 == 0 && (globalY+3) % 6 == 0)) {
                                r = (byte) (r - 30); // Let byte casting handle underflow, like in original dirt
                                g = (byte) (g - 25);
                                b = (byte) (b - 20);
                            }
                        }
                        a = (byte) 255;
                        buffer.put(r).put(g).put(b).put(a);
                        continue;
                    } else if (tileX == 2) { // Pine wood texture (2,2)
                        // Darker wood variant
                        float woodGrain = (float) Math.sin(globalY * 1.5) * 0.5f + 0.5f;
                        int ringX = (globalX % texturePixelSize);
                        float ringEffect = (float) Math.sin(ringX * 0.7) * 0.4f + 0.6f;
                        
                        // Darker base colors than regular wood
                        r = (byte) (80 + woodGrain * 40 * ringEffect);
                        g = (byte) (50 + woodGrain * 25 * ringEffect);
                        b = (byte) (20 + woodGrain * 15 * ringEffect);
                        
                        // Add occasional darker knots
                        int centerX = texturePixelSize / 2;
                        int centerY = texturePixelSize / 2;
                        float distToCenter = (float) Math.sqrt(Math.pow(globalX % texturePixelSize - centerX, 2) +
                                                              Math.pow(globalY % texturePixelSize - centerY, 2));
                        
                        if (distToCenter < 3 && (globalX / texturePixelSize + globalY / texturePixelSize) % 3 == 0) {
                            r = (byte) (r - 15);
                            g = (byte) (g - 10);
                            b = (byte) (b - 5);
                        }
                        a = (byte) 255;
                        buffer.put(r).put(g).put(b).put(a);
                        continue;
                    } else if (tileX == 3) { // Ice texture (3,2)
                        int pixelX_ice = globalX % texturePixelSize;
                        int pixelY_ice = globalY % texturePixelSize;
                        
                        // Light blue ice with crystalline patterns
                        float iceNoise = (float) (
                            Math.sin(pixelX_ice * 0.8 + pixelY_ice * 0.6) * 0.3 +
                            Math.cos(pixelX_ice * 0.4 + pixelY_ice * 0.9) * 0.3 +
                            Math.sin((pixelX_ice + pixelY_ice) * 0.7) * 0.4
                        ) * 0.5f + 0.5f;
                        
                        r = (byte) (200 + iceNoise * 30);
                        g = (byte) (230 + iceNoise * 20);
                        b = (byte) (255);
                        
                        // Add crystal-like reflections
                        if ((pixelX_ice % 4 == 0 && pixelY_ice % 4 == 0) || 
                            ((pixelX_ice + 2) % 4 == 0 && (pixelY_ice + 2) % 4 == 0)) {
                            r = (byte) 255;
                            g = (byte) 255;
                            b = (byte) 255;
                        }
                        
                        a = (byte) 200; // Semi-transparent
                        buffer.put(r).put(g).put(b).put(a);
                        continue;
                    } else if (tileX == 4) { // Snowy leaves texture (4,2)
                        // Leaves with snow patches
                        float leafNoise = (float) (
                            Math.sin(globalX * 0.9 + globalY * 0.6) * 0.25 +
                            Math.cos(globalX * 0.7 + globalY * 0.9) * 0.25 +
                            Math.sin((globalX + globalY) * 0.8) * 0.5
                        ) * 0.5f + 0.5f;
                        
                        // Base green color (slightly darker than normal leaves)
                        r = (byte) (20 + leafNoise * 40);
                        g = (byte) (80 + leafNoise * 60);
                        b = (byte) (15 + leafNoise * 30);
                        
                        // Add snow patches
                        int pixelX_snowyleaves = globalX % texturePixelSize;
                        int pixelY_snowyleaves = globalY % texturePixelSize;
                        
                        if ((pixelX_snowyleaves % 3 == 0 && pixelY_snowyleaves % 4 == 0) || 
                            ((pixelX_snowyleaves + 2) % 5 == 0 && (pixelY_snowyleaves + 1) % 3 == 0)) {
                            // Snow patches
                            r = (byte) 250;
                            g = (byte) 250;
                            b = (byte) 255;
                        }
                        
                        // Add transparency at edges
                        int edgeX = globalX % texturePixelSize;
                        int edgeY = globalY % texturePixelSize;
                        if (edgeX <= 1 || edgeX >= texturePixelSize-2 ||
                            edgeY <= 1 || edgeY >= texturePixelSize-2) {
                            a = (byte) (200 + (leafNoise * 55));
                        } else {
                            a = (byte) 255;
                        }
                        buffer.put(r).put(g).put(b).put(a);
                        continue;
                    } else if (tileX == 5) { // Pure snow texture (5,2) for layered snow
                        int pixelX_puresnow = globalX % texturePixelSize;
                        int pixelY_puresnow = globalY % texturePixelSize;
                        
                        // Pure white snow with very subtle variations
                        int noiseVal_puresnow = (pixelX_puresnow * 13 + pixelY_puresnow * 29 + tileX * 11 + tileY * 17) % 21;
                        
                        if (noiseVal_puresnow < 7) { // Pure white
                            r = (byte) 255; g = (byte) 255; b = (byte) 255;
                        } else if (noiseVal_puresnow < 14) { // Slightly off-white
                            r = (byte) 252; g = (byte) 252; b = (byte) 254;
                        } else { // Very light blue tint
                            r = (byte) 250; g = (byte) 252; b = (byte) 255;
                        }
                        a = (byte) 255;
                        buffer.put(r).put(g).put(b).put(a);
                        continue;
                    }
                }

                int tileIndex = tileY * textureSize + tileX;
                  switch (tileIndex % 12) { // Ensure this switch is only hit if not handled above
                    case 0 -> { // Grass top
                        // Minecraft-style Grass Top - V2 (based on reference image)
                        int pixelX_gt = globalX % this.texturePixelSize;
                        int pixelY_gt = globalY % this.texturePixelSize;

                        // Pseudo-random noise to select shade
                        int noiseVal_gt = (pixelX_gt * 13 + pixelY_gt * 29 + tileX * 11 + tileY * 17) % 21; // Increased range for more granular choice

                        if (noiseVal_gt < 7) { // Darkest green
                            r = (byte) 80; g = (byte) 120; b = (byte) 50;
                        } else if (noiseVal_gt < 14) { // Medium green
                            r = (byte) 95; g = (byte) 150; b = (byte) 60;
                        } else { // Lightest green
                            r = (byte) 120; g = (byte) 180; b = (byte) 80;
                        }
                    }
                    case 1 -> { // Grass side
                        // Minecraft-style Grass Side - V4 (grass at top, exact dirt match)
                        int pixelX_gs = globalX % this.texturePixelSize;
                        int pixelY_gs = globalY % this.texturePixelSize; // y-coordinate within the 16x16 tile
                        
                        int grassLayerHeight = 3; // Top 3 pixels are grass

                        if (pixelY_gs < grassLayerHeight) { // Grass part (top of the tile)
                            int noiseVal_gs_grass = (pixelX_gs * 17 + pixelY_gs * 23 + tileX * 7 + tileY * 13) % 21;
                            if (noiseVal_gs_grass < 7) { // Darkest green
                                r = (byte) 75; g = (byte) 115; b = (byte) 45;
                            } else if (noiseVal_gs_grass < 14) { // Medium green
                                r = (byte) 90; g = (byte) 145; b = (byte) 55;
                            } else { // Lightest green
                                r = (byte) 115; g = (byte) 175; b = (byte) 75;
                            }
                        } else { // Dirt part (below grass) - EXACT COPY of case 2 (Dirt block) logic
                            // 'globalX' and 'globalY' are global atlas pixel coordinates.
                            float dirtX_case1 = (float) Math.sin(globalX * 0.7 + globalY * 0.3) * 0.5f + 0.5f;
                            float dirtY_case1 = (float) Math.cos(globalX * 0.4 + globalY * 0.8) * 0.5f + 0.5f;
                            float dirtNoise_case1 = (dirtX_case1 + dirtY_case1) * 0.5f;
                            
                            r = (byte) (135 + dirtNoise_case1 * 40);
                            g = (byte) (95 + dirtNoise_case1 * 35);
                            b = (byte) (70 + dirtNoise_case1 * 25);
                            
                            // Add occasional small rocks or roots (exact from case 2)
                            if ((globalX % 5 == 0 && globalY % 5 == 0) ||
                                ((globalX+2) % 7 == 0 && (globalY+3) % 6 == 0)) {
                                r = (byte) (r - 30); // Let byte casting handle underflow, like in original dirt
                                g = (byte) (g - 25);
                                b = (byte) (b - 20);
                            }
                        }
                    }
                    case 2 -> { // Dirt
                        // Richer dirt texture with clumps and variations
                        float dirtX = (float) Math.sin(globalX * 0.7 + globalY * 0.3) * 0.5f + 0.5f;
                        float dirtY = (float) Math.cos(globalX * 0.4 + globalY * 0.8) * 0.5f + 0.5f;
                        float dirtNoise = (dirtX + dirtY) * 0.5f;
                        
                        r = (byte) (135 + dirtNoise * 40);
                        g = (byte) (95 + dirtNoise * 35);
                        b = (byte) (70 + dirtNoise * 25);
                        
                        // Add occasional small rocks or roots
                        if ((globalX % 5 == 0 && globalY % 5 == 0) ||
                            ((globalX+2) % 7 == 0 && (globalY+3) % 6 == 0)) {
                            r = (byte) (r - 30);
                            g = (byte) (g - 25);
                            b = (byte) (b - 20);
                        }
                    }
                    case 3 -> { // Stone
                        // Stone with cracks, variations and depth
                        float stoneNoise = (float) (
                            Math.sin(globalX * 0.8 + globalY * 0.2) * 0.3 +
                            Math.cos(globalX * 0.3 + globalY * 0.7) * 0.3 +
                            Math.sin((globalX+globalY) * 0.5) * 0.4
                        ) * 0.5f + 0.5f;
                          r = (byte) (120 + stoneNoise * 30);
                        g = (byte) (120 + stoneNoise * 30);
                        b = (byte) (125 + stoneNoise * 30);
                    }
                    case 4 -> { // Bedrock
                        r = (byte) 50;
                        g = (byte) 50;
                        b = (byte) 50;
                    }
                    case 5 -> { // Wood side
                        // Wood with grain patterns
                        float woodGrain = (float) Math.sin(globalY * 1.5) * 0.5f + 0.5f;
                        // Create rings effect
                        int ringX = (globalX % texturePixelSize);
                        float ringEffect = (float) Math.sin(ringX * 0.7) * 0.4f + 0.6f;
                        
                        r = (byte) (120 + woodGrain * 60 * ringEffect);
                        g = (byte) (80 + woodGrain * 40 * ringEffect);
                        b = (byte) (40 + woodGrain * 25 * ringEffect);
                        
                        // Add occasional knots
                        int centerX = texturePixelSize / 2;
                        int centerY = texturePixelSize / 2;
                        float distToCenter = (float) Math.sqrt(Math.pow(globalX % texturePixelSize - centerX, 2) +
                                                              Math.pow(globalY % texturePixelSize - centerY, 2));
                        
                        if (distToCenter < 3 && (globalX / texturePixelSize + globalY / texturePixelSize) % 3 == 0) {
                            r = (byte) (r - 20);
                            g = (byte) (g - 15);
                            b = (byte) (b - 10);
                        }
                    }
                    case 6 -> { // Wood top
                        // Concentric ring pattern for top of wood
                        int dx = (globalX % texturePixelSize) - texturePixelSize/2;
                        int dy = (globalY % texturePixelSize) - texturePixelSize/2;
                        float distFromCenter = (float) Math.sqrt(dx*dx + dy*dy);
                        float ringPattern = (float) Math.sin(distFromCenter * 1.2) * 0.5f + 0.5f;
                        
                        r = (byte) (150 + ringPattern * 50);
                        g = (byte) (95 + ringPattern * 35);
                        b = (byte) (45 + ringPattern * 20);
                    }
                    case 7 -> { // Leaves
                        // Varied leaf texture with depth and transparency
                        float leafNoise = (float) (
                            Math.sin(globalX * 0.9 + globalY * 0.6) * 0.25 +
                            Math.cos(globalX * 0.7 + globalY * 0.9) * 0.25 +
                            Math.sin((globalX+globalY) * 0.8) * 0.5
                        ) * 0.5f + 0.5f;
                        
                        r = (byte) (30 + leafNoise * 60);
                        g = (byte) (100 + leafNoise * 80);
                        b = (byte) (20 + leafNoise * 40);
                        
                        // Some variation for fruit or flowers
                        if ((globalX % 8 == 0 && globalY % 8 == 0) || ((globalX+4) % 8 == 0 && (globalY+4) % 8 == 0)) {
                            r = (byte) 220;
                            g = (byte) 30;
                            b = (byte) 30;
                        }
                        
                        // Add transparency at edges for a less blocky look
                        int edgeX = globalX % texturePixelSize;
                        int edgeY = globalY % texturePixelSize;
                        if (edgeX <= 1 || edgeX >= texturePixelSize-2 ||
                            edgeY <= 1 || edgeY >= texturePixelSize-2) {
                            // More transparent at edges
                            a = (byte) (200 + (leafNoise * 55));
                            buffer.put(r).put(g).put(b).put(a);
                            continue; // Skip the opaque setting below
                        }
                    }
                    case 8 -> { // Sand (Minecraft-like)
                        // Using per-tile coordinates for noise to ensure each sand tile is self-contained
                        int pixelXInTileSand = globalX % texturePixelSize; // Renamed to avoid conflict
                        int pixelYInTileSand = globalY % texturePixelSize; // Renamed to avoid conflict

                        // Simple noise for a speckled effect
                        // Combine two sine/cosine waves for a slightly more irregular pattern
                        // Scale noise to be roughly in 0-1 range
                        double noise1 = Math.sin(pixelXInTileSand * 0.8 + pixelYInTileSand * 1.1); // Output: -1 to 1
                        double noise2 = Math.cos(pixelXInTileSand * 0.5 - pixelYInTileSand * 0.9); // Output: -1 to 1
                        // (noise1 + noise2) is -2 to 2.
                        // ((noise1 + noise2) / 4.0) is -0.5 to 0.5.
                        // ((noise1 + noise2) / 4.0 + 0.5) is 0.0 to 1.0.
                        float combinedNoise = (float) ((noise1 + noise2) / 4.0 + 0.5);

                        // Base color: Light yellowish-tan (inspired by Minecraft sand)
                        int baseR = 220;
                        int baseG = 205;
                        int baseB = 160;

                        // Apply noise for speckling - subtle variations
                        // combinedNoise is 0 to 1. (combinedNoise - 0.5f) is -0.5 to 0.5.
                        // Let variation be +/- 15 for a subtle effect
                        float variation = (combinedNoise - 0.5f) * 30; // Results in variation from -15 to 15

                        r = (byte) Math.max(0, Math.min(255, (int)(baseR + variation)));
                        g = (byte) Math.max(0, Math.min(255, (int)(baseG + variation)));
                        b = (byte) Math.max(0, Math.min(255, (int)(baseB + variation * 0.8f))); // Blue channel varies a bit less
                    }
                    case 9 -> { // Water (Initial frame, will be updated by updateAnimatedWater)
                        // Generate the initial (time=0) frame for water
                        // The main atlas buffer is 'buffer'. We need to fill the portion for this tile.
                        // globalX and globalY here are global pixel coordinates in the main atlas buffer.
                        // We need to generate data for a single tile and put it into the correct place.

                        // Create a temporary buffer for one tile's data
                        ByteBuffer singleWaterTileData = BufferUtils.createByteBuffer(this.texturePixelSize * this.texturePixelSize * 4);
                        generateWaterTileData(0.0f, singleWaterTileData); // Generate with time = 0

                        // Now, copy from singleWaterTileData to the main 'buffer'
                        // This loop iterates over the pixels of *this specific tile* within the larger atlas buffer.
                        // tileX, tileY are the coordinates of the current tile being processed by the outer loops.
                        // We only want to do this if current tile (tileX, tileY) is the water tile.
                        // The switch case (tileIndex % 12) == 9 already ensures this.

                        // (globalX, globalY) are the current global pixel coordinates in the atlas buffer.
                        // (pixelXInTile, pixelYInTile) are coordinates *within* the current 16x16 tile.
                        int pixelXInTile = globalX % this.texturePixelSize;
                        int pixelYInTile = globalY % this.texturePixelSize;
                        
                        int tempBufferPosition = (pixelYInTile * this.texturePixelSize + pixelXInTile) * 4;

                        if (tempBufferPosition + 3 < singleWaterTileData.capacity()) {
                            buffer.put(singleWaterTileData.get(tempBufferPosition));
                            buffer.put(singleWaterTileData.get(tempBufferPosition + 1));
                            buffer.put(singleWaterTileData.get(tempBufferPosition + 2));
                            buffer.put(singleWaterTileData.get(tempBufferPosition + 3));
                        } else {
                            // Fallback: magenta for error
                            buffer.put((byte)255).put((byte)0).put((byte)255).put((byte)255);
                        }
                        continue; // Skip the generic opaque setting below
                    }
                    case 10 -> { // Coal Ore
                        // Stone base with coal seams
                        float coalStoneBase = (float) (
                            Math.sin(globalX * 0.8 + globalY * 0.2) * 0.3 +
                            Math.cos(globalX * 0.3 + globalY * 0.7) * 0.3 +
                            Math.sin((globalX+globalY) * 0.5) * 0.4
                        ) * 0.5f + 0.5f;
                        
                        // Stone base color same as stone block but slightly darker
                        r = (byte) (110 + coalStoneBase * 25);
                        g = (byte) (110 + coalStoneBase * 25);
                        b = (byte) (115 + coalStoneBase * 25);
                        
                        // Add coal veins - more organic looking
                        float veinFactor = (float) Math.sin(globalX * 1.5 + globalY * 1.7) * 0.5f + 0.5f;
                        if (veinFactor > 0.7) {
                            // Size and shape of coal deposits
                            float veinSize = (float) Math.sin((globalX + globalY) * 0.8) * 0.3f + 0.7f;
                            if (veinSize > 0.6) {
                                r = (byte) (40 + veinSize * 20);
                                g = (byte) (40 + veinSize * 20);
                                b = (byte) (40 + veinSize * 20);
                            }
                        }
                    }
                    case 11 -> { // Iron Ore
                        // Stone base with iron deposits
                        float ironStoneBase = (float) (
                            Math.sin(globalX * 0.8 + globalY * 0.2) * 0.3 +
                            Math.cos(globalX * 0.3 + globalY * 0.7) * 0.3 +
                            Math.sin((globalX+globalY) * 0.5) * 0.4
                        ) * 0.5f + 0.5f;
                        
                        // Stone base color
                        r = (byte) (110 + ironStoneBase * 25);
                        g = (byte) (110 + ironStoneBase * 25);
                        b = (byte) (115 + ironStoneBase * 25);
                        
                        // Add iron veins - reddish brown
                        float ironVeinFactor = (float) Math.sin(globalX * 1.2 + globalY * 1.3) * 0.5f + 0.5f;
                        if (ironVeinFactor > 0.7) {
                            // Iron deposits
                            float veinSize = (float) Math.sin((globalX + globalY) * 0.9) * 0.3f + 0.7f;
                            if (veinSize > 0.6) {
                                r = (byte) (160 + veinSize * 40);
                                g = (byte) (110 + veinSize * 20);
                                b = (byte) (70 + veinSize * 10);
                            }
                        }
                    }
                    default -> {
                        r = (byte) 255;
                        g = (byte) 0;
                        b = (byte) 255; // Magenta for unknown
                    }
                }
                
                a = (byte) 255; // Fully opaque for most blocks
                buffer.put(r).put(g).put(b).put(a);
            }
        }
        
        buffer.flip();        // Upload texture to GPU
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, totalSize, totalSize, 
                0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
        
        // Set appropriate texture filtering
        // Note: In OpenGL 3.0+, we would use GL30.glGenerateMipmap() here
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        
        return generatedTextureId;
    }    /**
     * Loads a texture from an image file.
     * Note: In a real implementation, you'd load from an actual file rather than generating.
     * This method is kept for reference.
     * @SuppressWarnings("unused")
     */
    @SuppressWarnings("unused")
    private int loadTextureFromFile(String filePath) {
        int loadedTextureId = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, loadedTextureId);
        
        // Set texture parameters
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        
        // Load texture data
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);
            IntBuffer channels = stack.mallocInt(1);
            
            ByteBuffer image = STBImage.stbi_load(filePath, width, height, channels, 4);
            if (image == null) {
                throw new RuntimeException("Failed to load texture file: " + filePath + 
                        ", reason: " + STBImage.stbi_failure_reason());
            }
            
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width.get(), height.get(), 
                    0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, image);
            
            STBImage.stbi_image_free(image);
        }
        
        return loadedTextureId;
    }
    
    /**
     * Destroys the texture atlas and frees GPU resources.
     */
    public void cleanup() {
        GL11.glDeleteTextures(textureId);
    }
}
