package com.stonebreak.rendering.textures;

import java.nio.ByteBuffer;

import com.stonebreak.rendering.WaterEffects;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemType;
import com.stonebreak.textures.atlas.AtlasMetadata;
import com.stonebreak.textures.atlas.AtlasMetadataCache;
import com.stonebreak.textures.loaders.TextureResourceLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Modern texture atlas system with JSON-based texture loading.
 * Replaces the legacy hardcoded texture generation system.
 * Phase 4: Complete integration with atlas metadata system.
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
    
    // Atlas metadata system
    private AtlasMetadata atlasMetadata;
    private AtlasMetadataCache metadataCache;
    private int actualAtlasWidth;
    private int actualAtlasHeight;
    private boolean metadataLoaded = false;
    
    // Atlas coordinates for water tiles (loaded from metadata)
    private int waterAtlasX = 9; // legacy single-tile fallback (grid-based atlas)
    private int waterAtlasY = 0;
    // List of all water face tiles present in the atlas metadata. Each entry is [atlasX, atlasY].
    private java.util.List<int[]> waterFaceTiles = new java.util.ArrayList<>();
    
    // Paths
    private static final String ATLAS_METADATA_PATH = "stonebreak-game/src/main/resources/Texture Atlas/atlas_metadata.json";
    private static final String ATLAS_IMAGE_PATH = "stonebreak-game/src/main/resources/Texture Atlas/TextureAtlas.png";
    
    /**
     * Creates a texture atlas with the specified texture size.
     * Attempts to load from generated atlas, falls back to placeholder if needed.
     */
    public TextureAtlas(int textureSize) {
        this.textureSize = textureSize;
        this.metadataCache = new AtlasMetadataCache();
        
        System.out.println("Creating texture atlas with size: " + textureSize);
        
        // Initialize the texture ID by attempting to load generated atlas first
        this.textureId = initializeTexture(textureSize);
        
        // Initialize the buffer for water tile updates
        this.waterTileUpdateBuffer = BufferUtils.createByteBuffer(texturePixelSize * texturePixelSize * 4);
        System.out.println("Texture atlas created with ID: " + textureId);
    }
    
    /**
     * Initialize texture by trying to load generated atlas first, then fallback to placeholder.
     */
    private int initializeTexture(int textureSize) {
        // Try to load generated atlas first
        int loadedTextureId = loadGeneratedAtlasTexture();
        
        if (loadedTextureId != 0) {
            System.out.println("Generated atlas loaded successfully");
            return loadedTextureId;
        } else {
            System.out.println("Generated atlas not found or invalid, using placeholder");
            this.actualAtlasWidth = textureSize * texturePixelSize;
            this.actualAtlasHeight = textureSize * texturePixelSize;
            return generatePlaceholderAtlas();
        }
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
     * Attempts to load the generated texture atlas from disk.
     * @return OpenGL texture ID if successful, 0 if failed
     */
    private int loadGeneratedAtlasTexture() {
        try {
            // Check if files exist
            File metadataFile = new File(ATLAS_METADATA_PATH);
            File imageFile = new File(ATLAS_IMAGE_PATH);
            
            if (!metadataFile.exists() || !imageFile.exists()) {
                System.out.println("Atlas files not found: metadata=" + metadataFile.exists() + ", image=" + imageFile.exists());
                return 0;
            }
            
            // Load metadata
            ObjectMapper objectMapper = new ObjectMapper();
            atlasMetadata = objectMapper.readValue(metadataFile, AtlasMetadata.class);
            atlasMetadata.initializeLookupMaps();
            System.out.println("[TextureAtlas] Loaded atlas metadata with " + atlasMetadata.getTextures().size() + " textures");
            
            // Load image
            BufferedImage atlasImage = ImageIO.read(imageFile);
            if (atlasImage == null) {
                System.err.println("Failed to load atlas image");
                return 0;
            }
            
            // Store actual dimensions
            actualAtlasWidth = atlasImage.getWidth();
            actualAtlasHeight = atlasImage.getHeight();
            
            // Create OpenGL texture
            int textureId = createTextureFromImage(atlasImage);
            if (textureId == 0) {
                System.err.println("Failed to create OpenGL texture from atlas image");
                return 0;
            }
            
            // Update water coordinates from metadata
            // Support both legacy names ("water", "water_still") and the current "water_temp_*" faces.
            waterFaceTiles.clear();
            AtlasMetadata.TextureEntry waterTexture = atlasMetadata.findTexture("water");
            if (waterTexture == null) {
                waterTexture = atlasMetadata.findTexture("water_still");
            }
            if (waterTexture != null) {
                waterAtlasX = waterTexture.getX() / texturePixelSize;
                waterAtlasY = waterTexture.getY() / texturePixelSize;
                waterFaceTiles.add(new int[]{waterAtlasX, waterAtlasY});
            }

            // Scan all textures for water face entries (e.g., water_temp_top, water_temp_north, etc.)
            for (java.util.Map.Entry<String, AtlasMetadata.TextureEntry> e : atlasMetadata.getTextures().entrySet()) {
                String name = e.getKey();
                if (name.startsWith("water_") || name.startsWith("waterTemp_") || name.startsWith("watertemp_") || name.startsWith("watertemp") || name.startsWith("water_temp")) {
                    AtlasMetadata.TextureEntry tex = e.getValue();
                    int ax = tex.getX() / texturePixelSize;
                    int ay = tex.getY() / texturePixelSize;
                    int[] pair = new int[]{ax, ay};
                    // Avoid duplicates (some names may point to same tile)
                    boolean exists = false;
                    for (int[] p : waterFaceTiles) {
                        if (p[0] == ax && p[1] == ay) { exists = true; break; }
                    }
                    if (!exists) {
                        waterFaceTiles.add(pair);
                    }
                }
            }

            // If we didn’t find any, keep legacy fallback tile
            
            // Cache the pixel buffer for NanoVG
            cachePixelBuffer(atlasImage);
            
            System.out.println("[TextureAtlas] Generated atlas loaded successfully: " + actualAtlasWidth + "x" + actualAtlasHeight);
            System.out.println("[TextureAtlas] Atlas contains " + atlasMetadata.getTextures().size() + " textures");
            
            // Debug: Print first few texture names to verify mapping
            System.out.println("[TextureAtlas] Sample textures in atlas:");
            int count = 0;
            for (String textureName : atlasMetadata.getTextures().keySet()) {
                if (count++ < 10) {
                    System.out.println("  - " + textureName);
                }
            }
            
            metadataLoaded = true;
            return textureId;
            
        } catch (Exception e) {
            System.err.println("Failed to load generated atlas: " + e.getMessage());
            e.printStackTrace();
            return 0;
        }
    }
    
    /**
     * Creates an OpenGL texture from a BufferedImage.
     * @param image The image to create texture from
     * @return OpenGL texture ID, or 0 if failed
     */
    private int createTextureFromImage(BufferedImage image) {
        try {
            int width = image.getWidth();
            int height = image.getHeight();
            
            // Extract pixel data
            int[] pixels = new int[width * height];
            image.getRGB(0, 0, width, height, pixels, 0, width);
            
            // Convert to ByteBuffer
            ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * 4);
            for (int pixel : pixels) {
                buffer.put((byte) ((pixel >> 16) & 0xFF)); // Red
                buffer.put((byte) ((pixel >> 8) & 0xFF));  // Green
                buffer.put((byte) (pixel & 0xFF));         // Blue
                buffer.put((byte) ((pixel >> 24) & 0xFF)); // Alpha
            }
            buffer.flip();
            
            // Create OpenGL texture
            int textureId = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
            
            // Set texture parameters
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            
            // Upload texture data
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
            
            return textureId;
            
        } catch (Exception e) {
            System.err.println("Failed to create texture from image: " + e.getMessage());
            return 0;
        }
    }
    
    /**
     * Cache pixel buffer from atlas image for NanoVG usage.
     */
    private void cachePixelBuffer(BufferedImage image) {
        try {
            int width = image.getWidth();
            int height = image.getHeight();
            
            // Extract pixel data for NanoVG (RGBA format)
            int[] pixels = new int[width * height];
            image.getRGB(0, 0, width, height, pixels, 0, width);
            
            this.atlasPixelBuffer_cached = BufferUtils.createByteBuffer(width * height * 4);
            for (int pixel : pixels) {
                atlasPixelBuffer_cached.put((byte) ((pixel >> 16) & 0xFF)); // Red
                atlasPixelBuffer_cached.put((byte) ((pixel >> 8) & 0xFF));  // Green
                atlasPixelBuffer_cached.put((byte) (pixel & 0xFF));         // Blue
                atlasPixelBuffer_cached.put((byte) ((pixel >> 24) & 0xFF)); // Alpha
            }
            atlasPixelBuffer_cached.flip();
            
        } catch (Exception e) {
            System.err.println("Failed to cache pixel buffer: " + e.getMessage());
        }
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

            // If metadata provided multiple water face tiles, update all of them; else update legacy single tile.
            if (!waterFaceTiles.isEmpty()) {
                for (int i = 0; i < waterFaceTiles.size(); i++) {
                    int[] tile = waterFaceTiles.get(i);
                    int offsetX = tile[0] * texturePixelSize;
                    int offsetY = tile[1] * texturePixelSize;
                    // Reset buffer position for each upload
                    waterTileUpdateBuffer.rewind();
                    GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, offsetX, offsetY,
                                         texturePixelSize, texturePixelSize,
                                         GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, waterTileUpdateBuffer);
                }
            } else {
                int offsetX = waterAtlasX * texturePixelSize;
                int offsetY = waterAtlasY * texturePixelSize;
                GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, offsetX, offsetY,
                                     texturePixelSize, texturePixelSize,
                                     GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, waterTileUpdateBuffer);
            }
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
        if (metadataLoaded && atlasMetadata != null) {
            return actualAtlasWidth;
        }
        return textureSize * texturePixelSize;
    }

    /**
     * Gets the height of the entire texture atlas in pixels.
     */
    public int getTextureHeight() {
        if (metadataLoaded && atlasMetadata != null) {
            return actualAtlasHeight;
        }
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
    // METADATA-DRIVEN TEXTURE COORDINATE METHODS
    // These methods provide real texture coordinates from atlas metadata
    // =================================================================
    
    /**
     * Gets texture coordinates for a block type.
     * Uses atlas metadata for precise coordinate lookup.
     * @param blockType The block type
     * @return Array of texture coordinates [u1, v1, u2, v2]
     */
    public float[] getTextureCoordinatesForBlock(BlockType blockType) {
        if (!metadataLoaded || atlasMetadata == null) {
            return getFallbackCoordinates();
        }
        
        String blockName = getBlockTextureName(blockType);
        if (blockName == null) {
            return getErrorTextureCoordinates();
        }
        
        // For blocks, try to get the "top" face first (most common display)
        AtlasMetadata.TextureEntry texture = atlasMetadata.findBlockTexture(blockName, "top");
        if (texture == null) {
            // Try without face specification for uniform blocks
            texture = atlasMetadata.findTexture(blockName);
        }
        
        if (texture != null) {
            float[] coords = texture.getUVCoordinates(actualAtlasWidth, actualAtlasHeight);
            metadataCache.put("block_" + blockType.name(), 
                new AtlasMetadataCache.TextureCoordinates(
                    blockName, coords[0], coords[1], coords[2], coords[3],
                    texture.getX(), texture.getY(), texture.getWidth(), texture.getHeight(),
                    TextureResourceLoader.TextureType.BLOCK_UNIFORM
                ));
            return coords;
        }
        
        return getErrorTextureCoordinates();
    }
    
    /**
     * Get texture name for a block type.
     * Maps BlockType enum to texture names in the atlas.
     */
    private String getBlockTextureName(BlockType blockType) {
        if (blockType == null) return null;
        
        switch (blockType) {
            case GRASS: return "grass_block";
            case DIRT: return "dirt_block"; // Fixed: atlas has dirt_block_* textures
            case STONE: return "stone";
            case COBBLESTONE: return "cobblestone";
            case WOOD: return "wood";
            case SAND: return "sand";
            case WATER: return "water_temp"; // Fixed: atlas has water_temp_* textures
            case COAL_ORE: return "coal_ore";
            case IRON_ORE: return "iron_ore";
            case LEAVES: return "leaves";
            case BEDROCK: return "bedrock";
            case ICE: return "ice";
            case SNOW: return "snow";
            case DANDELION: return "dandelion";
            case ROSE: return "rose"; // Fixed: atlas has rose_* textures
            case ELM_WOOD_LOG: return "elm_wood_log";
            case MAGMA: return "magma";
            case WORKBENCH: return "workbench_custom"; // Fixed: atlas has workbench_custom_* textures
            case PINE: return "pine_wood";
            case WOOD_PLANKS: return "wood_planks_custom"; // Added missing mapping
            case PINE_WOOD_PLANKS: return "pine_wood_planks_custom"; // Added missing mapping
            case ELM_WOOD_PLANKS: return "elm_wood_planks_custom"; // Added missing mapping
            case SNOWY_DIRT: return "snowy_dirt";
            case PINE_LEAVES: return "pine_leaves";
            case RED_SAND: return "red_sand";
            case CRYSTAL: return "crystal";
            case SANDSTONE: return "sandstone";
            case RED_SANDSTONE: return "red_sandstone";
            case ELM_LEAVES: return "elm_leaves";
            case GRAVEL: return "gravel";
            case CLAY: return "clay";
            case RED_SAND_COBBLESTONE: return "red_sand_cobblestone";
            case SAND_COBBLESTONE: return "sand_cobblestone";
            default: return blockType.name().toLowerCase();
        }
    }
    
    /**
     * Get error texture coordinates using Errockson.gif fallback.
     */
    private float[] getErrorTextureCoordinates() {
        if (metadataLoaded && atlasMetadata != null) {
            // Look for Errockson specifically first
            AtlasMetadata.TextureEntry errorTexture = atlasMetadata.findTexture("Errockson");
            if (errorTexture == null) {
                errorTexture = atlasMetadata.getErrorTexture();
            }
            if (errorTexture != null) {
                float[] coords = errorTexture.getUVCoordinates(actualAtlasWidth, actualAtlasHeight);
                return coords;
            }
        }
        // Fallback to default coordinates
        return getFallbackCoordinates();
    }
    
    /**
     * Get fallback coordinates for placeholder system.
     */
    private float[] getFallbackCoordinates() {
        return new float[]{0.0f, 0.0f, 1.0f, 1.0f};
    }
    
    /**
     * Gets texture coordinates for a block face.
     * Uses atlas metadata for precise face-specific coordinate lookup.
     * @param blockType The block type
     * @param face The face name (e.g., "top", "bottom", "side")
     * @return Array of texture coordinates [u1, v1, u2, v2]
     */
    public float[] getTextureCoordinatesForBlockFace(BlockType blockType, String face) {
        if (!metadataLoaded || atlasMetadata == null) {
            return getFallbackCoordinates();
        }
        
        String blockName = getBlockTextureName(blockType);
        if (blockName == null) {
            return getErrorTextureCoordinates();
        }
        
        // Check cache first
        String cacheKey = "block_" + blockType.name() + "_" + face;
        AtlasMetadataCache.TextureCoordinates cached = metadataCache.get(cacheKey);
        if (cached != null) {
            return cached.getUVArray();
        }
        
        AtlasMetadata.TextureEntry texture = atlasMetadata.findBlockTexture(blockName, face);
        if (texture != null) {
            float[] coords = texture.getUVCoordinates(actualAtlasWidth, actualAtlasHeight);
            metadataCache.put(cacheKey, 
                new AtlasMetadataCache.TextureCoordinates(
                    blockName + "_" + face, coords[0], coords[1], coords[2], coords[3],
                    texture.getX(), texture.getY(), texture.getWidth(), texture.getHeight(),
                    TextureResourceLoader.TextureType.BLOCK_CUBE_CROSS
                ));
            return coords;
        }
        
        return getErrorTextureCoordinates();
    }
    
    /**
     * Gets texture coordinates for an item type.
     * Uses atlas metadata for precise item coordinate lookup.
     * @param itemId The item ID
     * @return Array of texture coordinates [u1, v1, u2, v2]
     */
    public float[] getTextureCoordinatesForItem(int itemId) {
        if (!metadataLoaded || atlasMetadata == null) {
            return getFallbackCoordinates();
        }
        
        // Get item type from ID
        ItemType itemType = ItemType.getById(itemId);
        if (itemType == null) {
            return getErrorTextureCoordinates();
        }
        
        String itemName = getItemTextureName(itemType);
        if (itemName == null) {
            return getErrorTextureCoordinates();
        }
        
        // Check cache first
        String cacheKey = "item_" + itemId;
        AtlasMetadataCache.TextureCoordinates cached = metadataCache.get(cacheKey);
        if (cached != null) {
            return cached.getUVArray();
        }
        
        AtlasMetadata.TextureEntry texture = atlasMetadata.findItemTexture(itemName);
        if (texture != null) {
            float[] coords = texture.getUVCoordinates(actualAtlasWidth, actualAtlasHeight);
            metadataCache.put(cacheKey, 
                new AtlasMetadataCache.TextureCoordinates(
                    itemName, coords[0], coords[1], coords[2], coords[3],
                    texture.getX(), texture.getY(), texture.getWidth(), texture.getHeight(),
                    TextureResourceLoader.TextureType.ITEM
                ));
            return coords;
        }
        
        return getErrorTextureCoordinates();
    }
    
    /**
     * Get texture name for an item type.
     * Maps ItemType enum to texture names in the atlas.
     */
    private String getItemTextureName(ItemType itemType) {
        if (itemType == null) return null;
        
        switch (itemType) {
            case STICK: return "stick";
            case WOODEN_PICKAXE: return "wooden_pickaxe";
            case WOODEN_AXE: return "wooden_axe";
            default: return itemType.name().toLowerCase();
        }
    }
    
    /**
     * Gets UV coordinates for atlas position (legacy compatibility method).
     * Calculates coordinates based on atlas tile positions.
     * @param atlasX Atlas X coordinate (tile position)
     * @param atlasY Atlas Y coordinate (tile position)
     * @return Array of UV coordinates [u1, v1, u2, v2]
     */
    public float[] getUVCoordinates(int atlasX, int atlasY) {
        if (metadataLoaded && actualAtlasWidth > 0 && actualAtlasHeight > 0) {
            // Use actual atlas dimensions for precise calculation
            float pixelX = atlasX * texturePixelSize;
            float pixelY = atlasY * texturePixelSize;
            float u1 = pixelX / actualAtlasWidth;
            float v1 = pixelY / actualAtlasHeight;
            float u2 = (pixelX + texturePixelSize) / actualAtlasWidth;
            float v2 = (pixelY + texturePixelSize) / actualAtlasHeight;
            
            return new float[]{u1, v1, u2, v2};
        } else {
            // Fallback to grid-based calculation
            float tileSize = 1.0f / textureSize;
            float u1 = atlasX * tileSize;
            float v1 = atlasY * tileSize;
            float u2 = u1 + tileSize;
            float v2 = v1 + tileSize;
            
            return new float[]{u1, v1, u2, v2};
        }
    }
    
    /**
     * Gets UV coordinates for a specific block face (legacy compatibility method).
     * Uses atlas metadata for precise face-specific coordinate lookup.
     * @param blockType The block type
     * @param face The face to get coordinates for
     * @return Array of UV coordinates [u1, v1, u2, v2]
     */
    public float[] getBlockFaceUVs(BlockType blockType, BlockType.Face face) {
        if (face == null) {
            return getTextureCoordinatesForBlock(blockType);
        }
        
        String faceName = mapFaceToString(face);
        return getTextureCoordinatesForBlockFace(blockType, faceName);
    }
    
    /**
     * Map BlockType.Face enum to string representation.
     */
    private String mapFaceToString(BlockType.Face face) {
        switch (face) {
            case TOP: return "top";
            case BOTTOM: return "bottom";
            case SIDE_NORTH: return "north";
            case SIDE_SOUTH: return "south";
            case SIDE_EAST: return "east";
            case SIDE_WEST: return "west";
            default: return "top"; // fallback
        }
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
