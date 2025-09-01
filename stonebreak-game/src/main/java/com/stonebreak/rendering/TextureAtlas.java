package com.stonebreak.rendering;

import java.nio.ByteBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import com.stonebreak.blocks.BlockType;

/**
 * Modern texture atlas system with JSON-based texture loading.
 * Replaces the legacy hardcoded texture generation system.
 */
public class TextureAtlas {
    
    private final int textureId;
    private final int textureSize;
    private final int texturePixelSize = 16; // Size of each tile in pixels
    private final ByteBuffer waterTileUpdateBuffer; // Buffer for updating water tile
    private ByteBuffer atlasPixelBuffer_cached; // Cached buffer for NanoVG
    
    // NanoVG image caching
    private int nvgImageId = -1;
    private long lastVgContext = 0;
    
    // Atlas coordinates for the water tile (temporary - will be loaded from metadata)
    private static final int WATER_ATLAS_X = 9;
    private static final int WATER_ATLAS_Y = 0;
    
    /**
     * Creates a texture atlas with the specified texture size.
     * The texture size is the number of tiles in the atlas in each dimension.
     */
    public TextureAtlas(int textureSize) {
        this.textureSize = textureSize;
        System.out.println("Creating texture atlas with size: " + textureSize);
        this.textureId = generatePlaceholderAtlas();
        // Initialize the buffer for water tile updates
        this.waterTileUpdateBuffer = BufferUtils.createByteBuffer(texturePixelSize * texturePixelSize * 4);
        System.out.println("Texture atlas created with ID: " + textureId);
    }
    
    /**
     * Generates a placeholder texture atlas until the new system is fully implemented.
     * Creates a simple colored grid for testing purposes.
     */
    private int generatePlaceholderAtlas() {
        // Create a texture ID
        int generatedTextureId = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, generatedTextureId);
        
        // Set texture parameters
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST); 
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        
        // Ensure mipmaps are disabled
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
        
        System.out.println("Generating placeholder texture atlas with ID: " + generatedTextureId);
        
        int totalSize = textureSize * this.texturePixelSize;
        if (totalSize <= 0) {
            System.err.println("TextureAtlas: Error - totalSize is " + totalSize);
            this.atlasPixelBuffer_cached = BufferUtils.createByteBuffer(4);
            this.atlasPixelBuffer_cached.put((byte)255).put((byte)0).put((byte)255).put((byte)255).flip();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 1, 1, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, this.atlasPixelBuffer_cached);
            return generatedTextureId;
        }
        
        // Create buffer for placeholder atlas
        this.atlasPixelBuffer_cached = BufferUtils.createByteBuffer(totalSize * totalSize * 4);
        ByteBuffer buffer = this.atlasPixelBuffer_cached;
        
        // Create a simple placeholder pattern - checkerboard with colors indicating tile positions
        for (int globalY = 0; globalY < totalSize; globalY++) {
            for (int globalX = 0; globalX < totalSize; globalX++) {
                int tileX = globalX / texturePixelSize;
                int tileY = globalY / texturePixelSize;
                
                // Create a visible pattern based on tile position
                byte r = (byte)(((tileX + tileY) % 2 == 0) ? 128 + tileX * 8 : 64 + tileY * 8);
                byte g = (byte)(128 + (tileX * 16) % 128);
                byte b = (byte)(128 + (tileY * 16) % 128);
                byte a = (byte)255;
                
                buffer.put(r);
                buffer.put(g);
                buffer.put(b);
                buffer.put(a);
            }
        }
        buffer.flip();
        
        // Upload to OpenGL
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, totalSize, totalSize, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
        
        System.out.println("Placeholder atlas generated successfully");
        return generatedTextureId;
    }
    
    /**
     * Generates pixel data for a single animated water tile.
     * Enhanced to match WaterEffects wave system with multiple octaves.
     * @param time Current animation time.
     * @param buffer The ByteBuffer to fill with RGBA data.
     */
    private void generateWaterTileData(float time, ByteBuffer buffer) {
        buffer.clear(); // Prepare buffer for writing
        
        // Constants matching WaterEffects system
        final float WAVE_SPEED = 1.5f;
        final float WAVE_AMPLITUDE = 0.15f;
        
        // Use frequencies that are exact multiples of 2π to ensure seamless tiling
        final float PI2 = (float)(Math.PI * 2.0);
        final float seamlessFreq1 = PI2 * 2.0f; // 2 full cycles per texture
        final float seamlessFreq2 = PI2 * 4.0f; // 4 full cycles per texture  
        final float seamlessFreq3 = PI2 * 8.0f; // 8 full cycles per texture
        
        for (int y = 0; y < texturePixelSize; y++) { // y within the tile
            for (int x = 0; x < texturePixelSize; x++) { // x within the tile
                // Normalize coordinates for texture space (0 to 1)
                float nx = x / (float)texturePixelSize;
                float ny = y / (float)texturePixelSize;
                
                // Primary wave - ensure seamless tiling by using normalized coordinates
                float primaryWave = (float)(Math.sin(nx * seamlessFreq1 + time * WAVE_SPEED) * 
                                           Math.cos(ny * seamlessFreq1 * 0.8f + time * WAVE_SPEED * 0.9f));
                
                // Secondary wave - different frequency for detail, still seamless
                float secondaryWave = (float)(Math.sin(nx * seamlessFreq2 + ny * seamlessFreq1 * 1.5f + time * WAVE_SPEED * 2.0f) * 0.3f);
                
                // Tertiary wave - high frequency detail, seamless tiling
                float tertiaryWave = (float)(Math.sin(nx * seamlessFreq3 + time * 3.0f) * 
                                           Math.cos(ny * seamlessFreq3 * 0.75f + time * 2.5f) * 0.1f);
                
                // Combined wave effect
                float waveHeight = (primaryWave + secondaryWave + tertiaryWave) * WAVE_AMPLITUDE;
                
                // Foam intensity on wave peaks
                float foamIntensity = Math.max(0, waveHeight * 2.0f);
                
                // Calculate water color with wave-based variations
                float brightness = 0.8f + waveHeight * 0.5f; // Brighter at wave peaks
                float waterDepth = 0.7f + waveHeight * 0.3f;
                
                // Add caustic-like shimmer with seamless frequencies
                float causticPattern = (float)(Math.sin(nx * seamlessFreq3 * 1.5f + time * 2.0f) * 
                                             Math.cos(ny * seamlessFreq3 * 1.2f + time * 1.8f) * 0.15f);
                
                // Color calculations with wave influence
                byte r_val = (byte)(40 + foamIntensity * 60 + causticPattern * 10);
                byte g_val = (byte)(110 + waterDepth * 40 + brightness * 20 + causticPattern * 15);
                byte b_val = (byte)(200 + waterDepth * 30 + brightness * 15);
                
                // Alpha varies with wave height - more transparent in troughs
                byte a_val = (byte)(150 + waveHeight * 40 + foamIntensity * 30);

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
        updateAnimatedWater(time, null, 0.0f, 0.0f);
    }
    
    /**
     * Updates the animated water texture tile on the GPU with wave parameters.
     * @param time Current animation time.
     * @param waterEffects Optional WaterEffects instance for advanced wave data
     * @param playerX Player X position for location-based effects
     * @param playerZ Player Z position for location-based effects
     */
    public void updateAnimatedWater(float time, WaterEffects waterEffects, float playerX, float playerZ) {
        if (textureId == 0) return; // Atlas not initialized

        try {
            generateWaterTileData(time, waterTileUpdateBuffer);

            // Check if our texture atlas is currently bound
            int[] currentTexture = new int[1];
            GL11.glGetIntegerv(GL11.GL_TEXTURE_BINDING_2D, currentTexture);
            
            if (currentTexture[0] != textureId) {
                System.err.println("WARNING: updateAnimatedWater called but atlas not bound (bound: " + currentTexture[0] + ", expected: " + textureId + ")");
                return; // Don't update if wrong texture is bound
            }
            
            // Check if texture is valid
            if (!GL11.glIsTexture(textureId)) {
                System.err.println("WARNING: Invalid texture ID " + textureId + " in updateAnimatedWater");
                return;
            }

            int offsetX = WATER_ATLAS_X * texturePixelSize;
            int offsetY = WATER_ATLAS_Y * texturePixelSize;

            // Update the specific region of the texture atlas corresponding to the water tile
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, offsetX, offsetY,
                                 texturePixelSize, texturePixelSize,
                                 GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, waterTileUpdateBuffer);
        } catch (Exception e) {
            System.err.println("ERROR in updateAnimatedWater: " + e.getMessage());
            System.err.println("Stack trace: " + java.util.Arrays.toString(e.getStackTrace()));
        }
    }
    
    /**
     * Gets the texture ID of the atlas.
     */
    public int getTextureId() {
        return textureId;
    }

    /**
     * Gets the width of the entire texture atlas in pixels.
     */
    public int getTextureWidth() {
        return textureSize * texturePixelSize;
    }

    /**
     * Gets the height of the entire texture atlas in pixels.
     */
    public int getTextureHeight() {
        return textureSize * texturePixelSize;
    }
    
    /**
     * Binds the texture atlas for rendering.
     */
    public void bind() {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
    }
    
    /**
     * Cleanup OpenGL resources.
     */
    public void cleanup() {
        if (textureId != 0) {
            GL11.glDeleteTextures(textureId);
        }
        if (nvgImageId != -1 && lastVgContext != 0) {
            // Clean up NanoVG image if it exists
            org.lwjgl.nanovg.NanoVG.nvgDeleteImage(lastVgContext, nvgImageId);
        }
    }
    
    // =================================================================
    // TEMPORARY COMPATIBILITY METHODS
    // These methods provide placeholder texture coordinates until the
    // new atlas system is fully implemented
    // =================================================================
    
    /**
     * Gets texture coordinates for a block type.
     * TEMPORARY: Returns default coordinates until new system is implemented.
     * @param blockType The block type
     * @return Array of texture coordinates [u1, v1, u2, v2]
     */
    public float[] getTextureCoordinatesForBlock(BlockType blockType) {
        // TODO: Replace with atlas metadata lookup
        // Return default UV coordinates covering entire texture for now
        return new float[]{0.0f, 0.0f, 1.0f, 1.0f};
    }
    
    /**
     * Gets texture coordinates for a block face.
     * TEMPORARY: Returns default coordinates until new system is implemented.
     * @param blockType The block type
     * @param face The face name (e.g., "top", "bottom", "side")
     * @return Array of texture coordinates [u1, v1, u2, v2]
     */
    public float[] getTextureCoordinatesForBlockFace(BlockType blockType, String face) {
        // TODO: Replace with atlas metadata lookup
        // Return default UV coordinates for now
        return new float[]{0.0f, 0.0f, 1.0f, 1.0f};
    }
    
    /**
     * Gets texture coordinates for an item type.
     * TEMPORARY: Returns default coordinates until new system is implemented.
     * @param itemId The item ID
     * @return Array of texture coordinates [u1, v1, u2, v2]
     */
    public float[] getTextureCoordinatesForItem(int itemId) {
        // TODO: Replace with atlas metadata lookup
        // Return default UV coordinates for now
        return new float[]{0.0f, 0.0f, 1.0f, 1.0f};
    }
    
    /**
     * Gets UV coordinates for atlas position (legacy compatibility method).
     * TEMPORARY: Returns default coordinates until new system is implemented.
     * @param atlasX Atlas X coordinate (tile position)
     * @param atlasY Atlas Y coordinate (tile position)
     * @return Array of UV coordinates [u1, v1, u2, v2]
     */
    public float[] getUVCoordinates(int atlasX, int atlasY) {
        // TODO: Replace with proper atlas coordinate calculation
        // For now, calculate basic UV coordinates based on atlas position
        float tileSize = 1.0f / textureSize;
        float u1 = atlasX * tileSize;
        float v1 = atlasY * tileSize;
        float u2 = u1 + tileSize;
        float v2 = v1 + tileSize;
        
        return new float[]{u1, v1, u2, v2};
    }
    
    /**
     * Gets UV coordinates for a specific block face (legacy compatibility method).
     * TEMPORARY: Returns default coordinates until new system is implemented.
     * @param blockType The block type
     * @param face The face to get coordinates for
     * @return Array of UV coordinates [u1, v1, u2, v2]
     */
    public float[] getBlockFaceUVs(BlockType blockType, BlockType.Face face) {
        // TODO: Replace with proper block face texture lookup
        // For now, return default coordinates - this will show the placeholder pattern
        return new float[]{0.0f, 0.0f, 1.0f, 1.0f};
    }
    
    // =================================================================
    // NANOLVG INTEGRATION (kept from legacy for UI compatibility)
    // =================================================================
    
    /**
     * Gets or creates a NanoVG image ID for this texture atlas.
     * Used by UI rendering systems.
     */
    public int getNanoVGImageId(long vg) {
        if (nvgImageId == -1 || lastVgContext != vg) {
            ByteBuffer atlasBuffer = getAtlasPixelData();
            if(atlasBuffer != null && vg != 0) {
                int imageFlags = 0; // Default flags
                this.nvgImageId = org.lwjgl.nanovg.NanoVG.nvgCreateImageRGBA(vg, getTextureWidth(), getTextureHeight(), imageFlags, atlasBuffer);
                this.lastVgContext = vg;
                if(this.nvgImageId == -1){
                     System.err.println("TextureAtlas: Failed to create NanoVG image from pixel data.");
                }
            } else {
                System.err.println("TextureAtlas: Cannot create NanoVG image - invalid parameters");
            }
        }
        return nvgImageId;
    }
    
    /**
     * Gets the atlas pixel data for NanoVG usage.
     */
    private ByteBuffer getAtlasPixelData() {
        if (this.atlasPixelBuffer_cached == null) {
            System.err.println("TextureAtlas: Error - atlasPixelBuffer_cached is null");
            return null;
        }
        if (this.atlasPixelBuffer_cached.limit() <= 0) {
            System.err.println("TextureAtlas: Error - atlasPixelBuffer_cached.limit() is " + this.atlasPixelBuffer_cached.limit());
            return null;
        }
        // NanoVG needs the buffer positioned at the beginning for reading.
        this.atlasPixelBuffer_cached.rewind();
        return this.atlasPixelBuffer_cached;
    }
}