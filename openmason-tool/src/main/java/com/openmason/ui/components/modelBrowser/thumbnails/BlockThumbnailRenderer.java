package com.openmason.ui.components.modelBrowser.thumbnails;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.textures.atlas.AtlasMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Generates thumbnail textures for blocks by extracting from the texture atlas.
 * Supports multiple sizes (64x64, 32x32, 16x16) with proper alpha channel handling.
 *
 * <p>Features:</p>
 * <ul>
 *   <li>Checkerboard background for transparent areas</li>
 *   <li>Special handling for AIR block (fully transparent)</li>
 *   <li>Fail-fast validation for missing textures</li>
 * </ul>
 *
 * <p>This renderer requires a valid texture atlas and uses fail-fast validation.
 * Throws {@link IllegalStateException} if the atlas is not available.</p>
 */
public class BlockThumbnailRenderer {

    private static final Logger logger = LoggerFactory.getLogger(BlockThumbnailRenderer.class);

    // Checkerboard pattern colors for transparent backgrounds (light/dark gray)
    private static final int CHECKER_LIGHT = 0xFFCCCCCC;
    private static final int CHECKER_DARK = 0xFF999999;
    private static final int CHECKER_SIZE = 8; // 8x8 pixel checkers

    // Atlas paths
    private static final Path ATLAS_METADATA_PATH = Paths.get("stonebreak-game", "src", "main", "resources", "texture atlas", "atlas_metadata.json");
    private static final Path ATLAS_IMAGE_PATH = Paths.get("stonebreak-game", "src", "main", "resources", "texture atlas", "TextureAtlas.png");

    private final ModelBrowserThumbnailCache cache;
    private BufferedImage atlasImage;
    private AtlasMetadata atlasMetadata;
    private boolean atlasLoaded = false;

    /**
     * Creates a new block thumbnail renderer.
     *
     * @param cache The thumbnail cache to use
     */
    public BlockThumbnailRenderer(ModelBrowserThumbnailCache cache) {
        this.cache = cache;
        loadAtlas();
    }

    /**
     * Loads the texture atlas image and metadata.
     *
     * @throws RuntimeException if atlas files are missing or cannot be loaded
     */
    private void loadAtlas() {
        try {
            if (!Files.exists(ATLAS_IMAGE_PATH) || !Files.exists(ATLAS_METADATA_PATH)) {
                throw new IllegalStateException("Atlas files not found at: " + ATLAS_IMAGE_PATH + " or " + ATLAS_METADATA_PATH);
            }

            // Load atlas image
            atlasImage = ImageIO.read(ATLAS_IMAGE_PATH.toFile());

            // Load metadata
            ObjectMapper objectMapper = new ObjectMapper();
            atlasMetadata = objectMapper.readValue(ATLAS_METADATA_PATH.toFile(), AtlasMetadata.class);
            atlasMetadata.initializeLookupMaps();

            atlasLoaded = true;
            logger.info("Loaded texture atlas: {}x{} with {} textures",
                atlasImage.getWidth(), atlasImage.getHeight(), atlasMetadata.getTextures().size());
        } catch (Exception e) {
            logger.error("Failed to load texture atlas", e);
            throw new RuntimeException("Failed to load texture atlas - thumbnail rendering will not work", e);
        }
    }

    /**
     * Gets or generates a thumbnail for a block.
     *
     * @param blockType The block type
     * @param size The thumbnail size (64, 32, or 16)
     * @return OpenGL texture ID
     */
    public int getThumbnail(BlockType blockType, int size) {
        String key = ModelBrowserThumbnailCache.blockKey(blockType.name(), size);
        return cache.getOrCreate(key, () -> generateThumbnail(blockType, size));
    }

    /**
     * Generates a thumbnail texture for a block.
     *
     * @param blockType The block type
     * @param size The thumbnail size
     * @return OpenGL texture ID
     * @throws IllegalStateException if texture atlas is not loaded
     * @throws RuntimeException if thumbnail generation fails
     */
    private int generateThumbnail(BlockType blockType, int size) {
        if (!atlasLoaded) {
            throw new IllegalStateException("Texture atlas not loaded - cannot generate thumbnail for " + blockType);
        }

        // Special case: AIR has no texture - return empty/transparent texture
        if (blockType == BlockType.AIR) {
            return generateAirTexture(size);
        }

        try {
            return generateFromAtlas(blockType, size);
        } catch (Exception e) {
            logger.error("Failed to generate thumbnail for block: " + blockType, e);
            throw new RuntimeException("Failed to generate thumbnail for block: " + blockType, e);
        }
    }

    /**
     * Generates a thumbnail by extracting from the texture atlas.
     *
     * @throws IllegalArgumentException if block type has no texture mapping
     * @throws IllegalStateException if texture is not found in atlas
     */
    private int generateFromAtlas(BlockType blockType, int size) throws Exception {
        // Get block texture base name
        String textureName = getBlockTextureName(blockType);

        if (textureName == null) {
            throw new IllegalArgumentException("No texture mapping defined for block type: " + blockType);
        }

        AtlasMetadata.TextureEntry texture = null;

        // For multi-face blocks, prefer the side texture (north face) for better representation
        if (hasMultipleFaces(blockType)) {
            texture = atlasMetadata.findBlockTexture(textureName, "north");
        }

        // Fall back to top face for blocks with distinct top textures
        if (texture == null) {
            texture = atlasMetadata.findBlockTexture(textureName, "top");
        }

        // Fall back to general texture for uniform blocks
        if (texture == null) {
            texture = atlasMetadata.findTexture(textureName);
        }

        if (texture == null) {
            throw new IllegalStateException("Texture not found in atlas: " + textureName + " for block type: " + blockType);
        }

        // Extract texture region from atlas
        int x = texture.getX();
        int y = texture.getY();
        int width = texture.getWidth();
        int height = texture.getHeight();

        BufferedImage textureRegion = atlasImage.getSubimage(x, y, width, height);

        // Scale to thumbnail size
        BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(textureRegion, 0, 0, size, size, null);
        g.dispose();

        // Convert to ByteBuffer with checkerboard background for transparent areas
        // This works for all texture types: cubes, directional cubes, cross/sprites (roses, dandelions), etc.
        ByteBuffer pixels = ByteBuffer.allocateDirect(size * size * 4);

        for (int py = 0; py < size; py++) {
            for (int px = 0; px < size; px++) {
                // Get texture pixel with alpha
                int argb = scaled.getRGB(px, py);
                int texR = (argb >> 16) & 0xFF;
                int texG = (argb >> 8) & 0xFF;
                int texB = argb & 0xFF;
                int texA = (argb >> 24) & 0xFF;

                // Get checkerboard background color (DRY - using helper method)
                int[] bgRGB = getCheckerboardRGB(px, py);

                // Alpha blend texture over checkerboard
                // Formula: result = (texture * alpha) + (background * (1 - alpha))
                int finalR = (texR * texA + bgRGB[0] * (255 - texA)) / 255;
                int finalG = (texG * texA + bgRGB[1] * (255 - texA)) / 255;
                int finalB = (texB * texA + bgRGB[2] * (255 - texA)) / 255;

                pixels.put((byte) finalR);
                pixels.put((byte) finalG);
                pixels.put((byte) finalB);
                pixels.put((byte) 0xFF); // Final texture is opaque (checkerboard + blended texture)
            }
        }
        pixels.flip();

        return createTexture(pixels, size);
    }

    /**
     * Generates a special texture for AIR block (fully transparent with checkerboard background).
     */
    private int generateAirTexture(int size) {
        ByteBuffer pixels = ByteBuffer.allocateDirect(size * size * 4);
        fillCheckerboardPattern(pixels, size);
        pixels.flip();
        return createTexture(pixels, size);
    }

    /**
     * Maps BlockType to texture base name in atlas.
     * The base name is used with face suffixes like "_top", "_north", etc.
     */
    private String getBlockTextureName(BlockType blockType) {
        if (blockType == null) return null;

        return switch (blockType) {
            case GRASS -> "grass_block";
            case DIRT -> "dirt_block";
            case STONE -> "stone";
            case COBBLESTONE -> "cobblestone";
            case WOOD -> "wood";
            case SAND -> "sand";
            case WATER -> "water_temp";
            case COAL_ORE -> "coal_ore";
            case IRON_ORE -> "iron_ore";
            case LEAVES -> "leaves";
            case BEDROCK -> "bedrock";
            case GRAVEL -> "gravel";
            case CLAY -> "clay";
            case PINE -> "pine_wood";
            case ROSE -> "rose";
            case DANDELION -> "dandelion";
            case SANDSTONE -> "sandstone";
            case RED_SANDSTONE -> "red_sandstone";
            case SNOWY_DIRT -> "snowy_dirt";
            case PINE_LEAVES -> "pine_leaves";
            case ICE -> "ice";
            case SNOW -> "snow";
            case WORKBENCH -> "workbench_custom";
            case WOOD_PLANKS -> "wood_planks_custom";
            case PINE_WOOD_PLANKS -> "pine_wood_planks_custom";
            case ELM_WOOD_LOG -> "elm_wood_log";
            case ELM_WOOD_PLANKS -> "elm_wood_planks_custom";
            case ELM_LEAVES -> "elm_leaves";
            case RED_SAND -> "red_sand";
            case MAGMA -> "magma";
            case CRYSTAL -> "crystal";
            case RED_SAND_COBBLESTONE -> "red_sand_cobblestone";
            case SAND_COBBLESTONE -> "sand_cobblestone";
            default -> null;
        };
    }

    /**
     * Determines if a block has multiple distinct face textures.
     * These blocks benefit from showing a side face in thumbnails.
     */
    private boolean hasMultipleFaces(BlockType blockType) {
        if (blockType == null) return false;

        return switch (blockType) {
            case GRASS, WOOD, SANDSTONE, RED_SANDSTONE, SNOWY_DIRT,
                 PINE, WORKBENCH, ELM_WOOD_LOG -> true;
            default -> false;
        };
    }


    /**
     * Gets the checkerboard color for a given pixel position.
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @return Color value (0xRRGGBB format)
     */
    private int getCheckerboardColor(int x, int y) {
        boolean light = ((x / CHECKER_SIZE) + (y / CHECKER_SIZE)) % 2 == 0;
        return light ? CHECKER_LIGHT : CHECKER_DARK;
    }

    /**
     * Gets the RGB components of the checkerboard color for a given position.
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @return Array with [R, G, B] components (0-255)
     */
    private int[] getCheckerboardRGB(int x, int y) {
        int color = getCheckerboardColor(x, y);
        return new int[] {
            (color >> 16) & 0xFF,  // R
            (color >> 8) & 0xFF,   // G
            color & 0xFF           // B
        };
    }

    /**
     * Fills a ByteBuffer with an opaque checkerboard pattern.
     *
     * @param pixels Buffer to fill
     * @param size Texture size (width and height)
     */
    private void fillCheckerboardPattern(ByteBuffer pixels, int size) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int color = getCheckerboardColor(x, y);
                pixels.put((byte) ((color >> 16) & 0xFF)); // R
                pixels.put((byte) ((color >> 8) & 0xFF));  // G
                pixels.put((byte) (color & 0xFF));         // B
                pixels.put((byte) 0xFF);                   // A - Opaque
            }
        }
    }

    /**
     * Creates an OpenGL texture from pixel data.
     */
    private int createTexture(ByteBuffer pixels, int size) {
        int textureId = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);

        // Set texture parameters
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

        // Upload pixel data
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, size, size, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        return textureId;
    }
}
