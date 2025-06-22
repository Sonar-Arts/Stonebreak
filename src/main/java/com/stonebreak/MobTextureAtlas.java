package com.stonebreak;

import java.nio.ByteBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;

/**
 * Simple, reliable texture atlas for mob textures.
 * Uses solid colors for debugging and reliable rendering.
 */
public class MobTextureAtlas {
    
    private final int textureId;
    private final int textureSize;
    private final int texturePixelSize = 16; // Size of each tile in pixels
    private ByteBuffer atlasPixelBuffer;

    // Atlas coordinates for COW textures - must match CowModel UV coordinates
    public static final int COW_HEAD_ATLAS_X = 0;
    public static final int COW_HEAD_ATLAS_Y = 0;
    public static final int COW_BODY_ATLAS_X = 1;
    public static final int COW_BODY_ATLAS_Y = 0;
    public static final int COW_LEGS_ATLAS_X = 2;
    public static final int COW_LEGS_ATLAS_Y = 0;
    public static final int COW_HORNS_ATLAS_X = 3;
    public static final int COW_HORNS_ATLAS_Y = 0;
    public static final int COW_UDDER_ATLAS_X = 0;
    public static final int COW_UDDER_ATLAS_Y = 1;
    public static final int COW_TAIL_ATLAS_X = 1;
    public static final int COW_TAIL_ATLAS_Y = 1;
    
    /**
     * Creates a mob texture atlas with the specified texture size.
     */
    public MobTextureAtlas(int textureSize) {
        this.textureSize = textureSize;
        
        // Generate OpenGL texture
        this.textureId = GL11.glGenTextures();
        
        System.out.println("Creating MobTextureAtlas with size: " + textureSize + "x" + textureSize);
        
        // Generate texture data
        generateTextureData();
        
        // Upload to GPU
        uploadToGPU();
        
        System.out.println("MobTextureAtlas created successfully with texture ID: " + textureId);
    }
    
    /**
     * Generates simple, solid texture data for all mob textures.
     */
    private void generateTextureData() {
        int totalPixels = textureSize * texturePixelSize;
        int bufferSize = totalPixels * totalPixels * 4; // RGBA - 4 bytes per pixel
        atlasPixelBuffer = BufferUtils.createByteBuffer(bufferSize);
        
        System.out.println("Generating texture data: " + totalPixels + "x" + totalPixels + " pixels, " + bufferSize + " bytes");
        
        // Generate each tile
        for (int tileY = 0; tileY < textureSize; tileY++) {
            for (int tileX = 0; tileX < textureSize; tileX++) {
                generateTile(tileX, tileY);
            }
        }
        
        atlasPixelBuffer.flip();
        System.out.println("Texture data generated, buffer position: " + atlasPixelBuffer.position() + "/" + atlasPixelBuffer.limit());
    }
    
    /**
     * Generates a single tile in the texture atlas.
     */
    private void generateTile(int tileX, int tileY) {
        // Generate cow textures with simple, solid colors
        if (tileX == COW_HEAD_ATLAS_X && tileY == COW_HEAD_ATLAS_Y) {
            generateSolidTexture(160, 120, 80, 255); // Brown head
            System.out.println("Generated COW_HEAD at (" + tileX + ", " + tileY + ")");
        } else if (tileX == COW_BODY_ATLAS_X && tileY == COW_BODY_ATLAS_Y) {
            generateSolidTexture(140, 100, 60, 255); // Brown body
            System.out.println("Generated COW_BODY at (" + tileX + ", " + tileY + ")");
        } else if (tileX == COW_LEGS_ATLAS_X && tileY == COW_LEGS_ATLAS_Y) {
            generateSolidTexture(120, 80, 40, 255); // Dark brown legs
            System.out.println("Generated COW_LEGS at (" + tileX + ", " + tileY + ")");
        } else if (tileX == COW_HORNS_ATLAS_X && tileY == COW_HORNS_ATLAS_Y) {
            generateSolidTexture(240, 220, 200, 255); // Light horn color
            System.out.println("Generated COW_HORNS at (" + tileX + ", " + tileY + ")");
        } else if (tileX == COW_UDDER_ATLAS_X && tileY == COW_UDDER_ATLAS_Y) {
            generateSolidTexture(220, 180, 160, 255); // Pink udder
            System.out.println("Generated COW_UDDER at (" + tileX + ", " + tileY + ")");
        } else if (tileX == COW_TAIL_ATLAS_X && tileY == COW_TAIL_ATLAS_Y) {
            generateSolidTexture(100, 70, 30, 255); // Dark brown tail
            System.out.println("Generated COW_TAIL at (" + tileX + ", " + tileY + ")");
        } else {
            // Generate debug texture for unused slots
            generateDebugTexture(tileX, tileY);
        }
    }
    
    /**
     * Generates a solid color texture for a tile.
     */
    private void generateSolidTexture(int r, int g, int b, int a) {
        for (int y = 0; y < texturePixelSize; y++) {
            for (int x = 0; x < texturePixelSize; x++) {
                atlasPixelBuffer.put((byte) r); // Red
                atlasPixelBuffer.put((byte) g); // Green
                atlasPixelBuffer.put((byte) b); // Blue
                atlasPixelBuffer.put((byte) a); // Alpha
            }
        }
    }
    
    /**
     * Generates a debug texture with a pattern to identify unused slots.
     */
    private void generateDebugTexture(int tileX, int tileY) {
        for (int y = 0; y < texturePixelSize; y++) {
            for (int x = 0; x < texturePixelSize; x++) {
                // Create a checkerboard pattern with tile coordinates as color
                boolean checker = (x / 2 + y / 2) % 2 == 0;
                if (checker) {
                    atlasPixelBuffer.put((byte) (tileX * 40 + 100)); // Red varies by tileX
                    atlasPixelBuffer.put((byte) (tileY * 40 + 100)); // Green varies by tileY
                    atlasPixelBuffer.put((byte) 50);                 // Blue constant
                    atlasPixelBuffer.put((byte) 255);               // Alpha opaque
                } else {
                    atlasPixelBuffer.put((byte) (tileX * 20 + 50));  // Darker red
                    atlasPixelBuffer.put((byte) (tileY * 20 + 50));  // Darker green
                    atlasPixelBuffer.put((byte) 25);                 // Darker blue
                    atlasPixelBuffer.put((byte) 255);               // Alpha opaque
                }
            }
        }
    }
    
    /**
     * Uploads the generated texture data to the GPU.
     */
    private void uploadToGPU() {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        
        // Set texture parameters for pixel art style and prevent tile bleeding
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        
        // Upload texture data
        int totalPixels = textureSize * texturePixelSize;
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, totalPixels, totalPixels, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, atlasPixelBuffer);
        
        // Check for OpenGL errors
        int error = GL11.glGetError();
        if (error != GL11.GL_NO_ERROR) {
            System.err.println("OpenGL error during texture upload: " + error);
        } else {
            System.out.println("Texture uploaded successfully to GPU");
        }
        
        // Unbind texture
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        
        System.out.println("MobTextureAtlas uploaded: " + totalPixels + "x" + totalPixels + " RGBA texture with " + (textureSize * textureSize) + " tiles");
    }
    
    /**
     * Gets the OpenGL texture ID.
     */
    public int getTextureId() {
        return textureId;
    }
    
    /**
     * Gets the texture size (number of tiles per dimension).
     */
    public int getTextureSize() {
        return textureSize;
    }
    
    /**
     * Gets the pixel size of each texture tile.
     */
    public int getTexturePixelSize() {
        return texturePixelSize;
    }
    
    /**
     * Gets texture coordinates for a specific tile.
     * Returns UV coordinates in the format expected by OpenGL.
     */
    public float[] getTextureCoords(int tileX, int tileY) {
        float tileSize = 1.0f / textureSize;
        float u = tileX * tileSize;
        float v = tileY * tileSize;
        
        System.out.println("Texture coords for tile (" + tileX + ", " + tileY + "): u=" + u + "-" + (u + tileSize) + ", v=" + v + "-" + (v + tileSize));
        
        return new float[] {
            u, v + tileSize,           // Bottom-left
            u + tileSize, v + tileSize, // Bottom-right
            u + tileSize, v,           // Top-right
            u, v                       // Top-left
        };
    }
    
    /**
     * Debug method to print atlas layout.
     */
    public void printAtlasLayout() {
        System.out.println("=== MobTextureAtlas Layout ===");
        System.out.println("Atlas size: " + textureSize + "x" + textureSize + " tiles");
        System.out.println("Tile size: " + texturePixelSize + "x" + texturePixelSize + " pixels");
        System.out.println("Total texture size: " + (textureSize * texturePixelSize) + "x" + (textureSize * texturePixelSize) + " pixels");
        System.out.println("COW_HEAD: (" + COW_HEAD_ATLAS_X + ", " + COW_HEAD_ATLAS_Y + ")");
        System.out.println("COW_BODY: (" + COW_BODY_ATLAS_X + ", " + COW_BODY_ATLAS_Y + ")");
        System.out.println("COW_LEGS: (" + COW_LEGS_ATLAS_X + ", " + COW_LEGS_ATLAS_Y + ")");
        System.out.println("COW_HORNS: (" + COW_HORNS_ATLAS_X + ", " + COW_HORNS_ATLAS_Y + ")");
        System.out.println("COW_UDDER: (" + COW_UDDER_ATLAS_X + ", " + COW_UDDER_ATLAS_Y + ")");
        System.out.println("COW_TAIL: (" + COW_TAIL_ATLAS_X + ", " + COW_TAIL_ATLAS_Y + ")");
        System.out.println("==============================");
    }
    
    /**
     * Cleans up OpenGL resources.
     */
    public void cleanup() {
        if (textureId != 0) {
            GL11.glDeleteTextures(textureId);
            System.out.println("MobTextureAtlas cleaned up, deleted texture ID: " + textureId);
        }
        if (atlasPixelBuffer != null) {
            atlasPixelBuffer = null;
        }
    }
}