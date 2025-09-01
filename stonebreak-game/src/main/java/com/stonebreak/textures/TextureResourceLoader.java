package com.stonebreak.textures;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import org.lwjgl.BufferUtils;

/**
 * Handles loading of texture resources from the filesystem.
 * Supports PNG files for blocks and items, with special handling for Errockson.gif.
 */
public class TextureResourceLoader {
    
    // Resource paths
    private static final String BLOCKS_TEXTURE_PATH = "/Blocks/Textures/";
    private static final String ITEMS_TEXTURE_PATH = "/Items/Textures/";
    
    // Cache for loaded textures to avoid reloading
    private static final Map<String, BufferedImage> textureCache = new HashMap<>();
    
    /**
     * Represents a loaded texture with its metadata.
     */
    public static class LoadedTexture {
        public final BufferedImage image;
        public final String fileName;
        public final int width;
        public final int height;
        public final TextureType type;
        
        public LoadedTexture(BufferedImage image, String fileName, TextureType type) {
            this.image = image;
            this.fileName = fileName;
            this.width = image.getWidth();
            this.height = image.getHeight();
            this.type = type;
        }
    }
    
    /**
     * Texture type enumeration.
     */
    public enum TextureType {
        BLOCK_UNIFORM,    // 16x16 single texture
        BLOCK_CUBE_CROSS, // 16x96 six-face texture
        ITEM,             // 16x16 item texture
        ERROR             // Error/fallback texture
    }
    
    /**
     * Loads a PNG texture from the blocks directory.
     * @param fileName The texture filename (e.g., "grass_block_texture.png")
     * @return LoadedTexture object, or null if loading fails
     */
    public static LoadedTexture loadBlockTexture(String fileName) {
        if (fileName.equals("Errockson.gif")) {
            // Handle special case for GIF error texture
            BufferedImage image = GifTextureLoader.loadErrocksonGif();
            if (image != null) {
                return new LoadedTexture(image, fileName, TextureType.ERROR);
            }
            return null;
        }
        
        return loadTextureFromPath(BLOCKS_TEXTURE_PATH + fileName, fileName, detectBlockTextureType(fileName));
    }
    
    /**
     * Loads a PNG texture from the items directory.
     * @param fileName The texture filename (e.g., "stick_texture.png")
     * @return LoadedTexture object, or null if loading fails
     */
    public static LoadedTexture loadItemTexture(String fileName) {
        return loadTextureFromPath(ITEMS_TEXTURE_PATH + fileName, fileName, TextureType.ITEM);
    }
    
    /**
     * Generic texture loading method.
     * @param resourcePath Full resource path
     * @param fileName Original filename for caching/error reporting
     * @param type Expected texture type
     * @return LoadedTexture object, or null if loading fails
     */
    private static LoadedTexture loadTextureFromPath(String resourcePath, String fileName, TextureType type) {
        // Check cache first
        String cacheKey = resourcePath;
        if (textureCache.containsKey(cacheKey)) {
            BufferedImage cachedImage = textureCache.get(cacheKey);
            if (cachedImage != null) {
                return new LoadedTexture(cachedImage, fileName, type);
            }
        }
        
        try {
            InputStream inputStream = TextureResourceLoader.class.getResourceAsStream(resourcePath);
            if (inputStream == null) {
                System.err.println("TextureResourceLoader: Could not find texture: " + resourcePath);
                return null;
            }
            
            BufferedImage image = ImageIO.read(inputStream);
            inputStream.close();
            
            if (image == null) {
                System.err.println("TextureResourceLoader: Failed to read image: " + resourcePath);
                return null;
            }
            
            // Cache the loaded image
            textureCache.put(cacheKey, image);
            
            System.out.println("TextureResourceLoader: Loaded texture " + fileName + " (" + 
                             image.getWidth() + "x" + image.getHeight() + ")");
            
            return new LoadedTexture(image, fileName, type);
            
        } catch (IOException e) {
            System.err.println("TextureResourceLoader: IOException loading " + resourcePath + ": " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Detects the texture type based on filename and expected dimensions.
     * @param fileName The texture filename
     * @return TextureType enum value
     */
    private static TextureType detectBlockTextureType(String fileName) {
        // For now, assume most block textures are uniform unless they follow cube cross naming
        if (fileName.contains("_cube") || fileName.contains("_cross")) {
            return TextureType.BLOCK_CUBE_CROSS;
        }
        return TextureType.BLOCK_UNIFORM;
    }
    
    /**
     * Converts a BufferedImage to RGBA ByteBuffer for OpenGL usage.
     * @param image The source image
     * @return ByteBuffer containing RGBA pixel data, or null if conversion fails
     */
    public static ByteBuffer imageToRGBABuffer(BufferedImage image) {
        if (image == null) {
            return null;
        }
        
        int width = image.getWidth();
        int height = image.getHeight();
        ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * 4);
        
        try {
            // Convert to RGBA format
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int argb = image.getRGB(x, y);
                    
                    // Extract ARGB components
                    int alpha = (argb >> 24) & 0xFF;
                    int red = (argb >> 16) & 0xFF;
                    int green = (argb >> 8) & 0xFF;
                    int blue = argb & 0xFF;
                    
                    // Put in RGBA order
                    buffer.put((byte) red);
                    buffer.put((byte) green);
                    buffer.put((byte) blue);
                    buffer.put((byte) alpha);
                }
            }
            
            buffer.flip();
            return buffer;
            
        } catch (Exception e) {
            System.err.println("TextureResourceLoader: Failed to convert image to RGBA buffer: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Loads a texture and returns it as an RGBA ByteBuffer ready for OpenGL.
     * @param fileName The texture filename
     * @param isBlockTexture true for block textures, false for item textures
     * @return ByteBuffer containing RGBA pixel data, or null if loading fails
     */
    public static ByteBuffer loadTextureAsRGBA(String fileName, boolean isBlockTexture) {
        LoadedTexture texture = isBlockTexture ? loadBlockTexture(fileName) : loadItemTexture(fileName);
        if (texture == null) {
            return null;
        }
        
        return imageToRGBABuffer(texture.image);
    }
    
    /**
     * Gets all available block texture filenames from resources.
     * Note: This is a simplified implementation - a full version would scan the resource directory.
     * @return Array of texture filenames found in the blocks directory
     */
    public static String[] getAvailableBlockTextures() {
        // TODO: Implement resource directory scanning
        // For now, return a hardcoded list based on known textures
        return new String[]{
            "bedrock_texture.png",
            "coal_ore_texture.png",
            "crystal_texture.png",
            "dandelion_texture.png",
            "dirt_block_texture.png",
            "elm_leaves_texture.png",
            "elm_wood_log_texture.png",
            "elm_wood_planks_custom_texture.png",
            "Errockson.gif", // Special case
            "grass_block_texture.png",
            "ice_texture.png",
            "iron_ore_texture.png",
            "leaves_texture.png",
            "magma_texture.png",
            "pine_wood_log_texture.png",
            "pine_wood_planks_texture.png",
            "red_sand_texture.png",
            "red_sandstone_texture.png",
            "rose_texture.png",
            "sand_texture.png",
            "sandstone_texture.png",
            "snow_texture.png",
            "snowy_dirt_texture.png",
            "snowy_leaves_texture.png",
            "stone_texture.png",
            "water_texture.png",
            "wood_log_texture.png",
            "wood_planks_texture.png",
            "workbench_texture.png"
        };
    }
    
    /**
     * Gets all available item texture filenames from resources.
     * @return Array of texture filenames found in the items directory
     */
    public static String[] getAvailableItemTextures() {
        // TODO: Implement resource directory scanning
        return new String[]{
            "stick_texture.png",
            "wooden_pickaxe_texture.png",
            "wooden_axe_texture.png"
        };
    }
    
    /**
     * Validates that a texture exists and can be loaded.
     * @param fileName The texture filename
     * @param isBlockTexture true for block textures, false for item textures
     * @return true if texture exists and loads successfully, false otherwise
     */
    public static boolean validateTextureExists(String fileName, boolean isBlockTexture) {
        LoadedTexture texture = isBlockTexture ? loadBlockTexture(fileName) : loadItemTexture(fileName);
        return texture != null;
    }
    
    /**
     * Clears the texture cache to free memory.
     * Call this when textures are no longer needed.
     */
    public static void clearCache() {
        textureCache.clear();
        System.out.println("TextureResourceLoader: Cache cleared");
    }
    
    /**
     * Gets the current cache size.
     * @return Number of textures currently cached
     */
    public static int getCacheSize() {
        return textureCache.size();
    }
}