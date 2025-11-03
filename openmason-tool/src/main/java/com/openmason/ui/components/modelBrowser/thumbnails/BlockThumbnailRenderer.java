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
 * Renders thumbnails for blocks from texture atlas coordinates.
 *
 * <p>Extracts the block texture from the atlas and generates OpenGL textures
 * at specified sizes (64x64, 32x32, 16x16).</p>
 *
 * <p>Features:</p>
 * <ul>
 *   <li>Checkerboard background for transparent areas</li>
 *   <li>Border rendering for visual separation</li>
 *   <li>Efficient texture generation with proper filtering</li>
 * </ul>
 */
public class BlockThumbnailRenderer {

    private static final Logger logger = LoggerFactory.getLogger(BlockThumbnailRenderer.class);

    // Checkerboard pattern colors (light/dark gray)
    private static final int CHECKER_LIGHT = 0xFFCCCCCC;
    private static final int CHECKER_DARK = 0xFF999999;
    private static final int CHECKER_SIZE = 8; // 8x8 pixel checkers

    // Border color
    private static final int BORDER_COLOR = 0xFF666666;

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
     */
    private void loadAtlas() {
        try {
            if (Files.exists(ATLAS_IMAGE_PATH) && Files.exists(ATLAS_METADATA_PATH)) {
                // Load atlas image
                atlasImage = ImageIO.read(ATLAS_IMAGE_PATH.toFile());

                // Load metadata
                ObjectMapper objectMapper = new ObjectMapper();
                atlasMetadata = objectMapper.readValue(ATLAS_METADATA_PATH.toFile(), AtlasMetadata.class);
                atlasMetadata.initializeLookupMaps();

                atlasLoaded = true;
                logger.info("Loaded texture atlas: {}x{} with {} textures",
                    atlasImage.getWidth(), atlasImage.getHeight(), atlasMetadata.getTextures().size());
            } else {
                logger.warn("Atlas files not found, using fallback rendering");
            }
        } catch (Exception e) {
            logger.error("Failed to load texture atlas", e);
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
     */
    private int generateThumbnail(BlockType blockType, int size) {
        try {
            if (atlasLoaded) {
                return generateFromAtlas(blockType, size);
            } else {
                return generateFallback(blockType, size);
            }
        } catch (Exception e) {
            logger.error("Failed to generate thumbnail for block: " + blockType, e);
            return generateFallback(blockType, size);
        }
    }

    /**
     * Generates a thumbnail by extracting from the texture atlas.
     */
    private int generateFromAtlas(BlockType blockType, int size) throws Exception {
        // Get block texture base name
        String textureName = getBlockTextureName(blockType);

        if (textureName == null) {
            return generateFallback(blockType, size);
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
            logger.warn("Texture not found in atlas: {}", textureName);
            return generateFallback(blockType, size);
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

        // Convert to ByteBuffer
        ByteBuffer pixels = ByteBuffer.allocateDirect(size * size * 4);
        for (int py = 0; py < size; py++) {
            for (int px = 0; px < size; px++) {
                int argb = scaled.getRGB(px, py);
                pixels.put((byte) ((argb >> 16) & 0xFF)); // R
                pixels.put((byte) ((argb >> 8) & 0xFF));  // G
                pixels.put((byte) (argb & 0xFF));         // B
                pixels.put((byte) 0xFF);                   // A - Always opaque
            }
        }
        pixels.flip();

        return createTexture(pixels, size);
    }

    /**
     * Generates a fallback thumbnail with colored squares.
     */
    private int generateFallback(BlockType blockType, int size) {
        try {
            ByteBuffer pixels = ByteBuffer.allocateDirect(size * size * 4);

            int color = getBlockColor(blockType);

            // Fill with checkerboard background
            fillCheckerboard(pixels, size);

            // Draw border
            drawBorder(pixels, size, BORDER_COLOR);

            // Overlay block color (semi-transparent to show checker)
            overlayColor(pixels, size, color, 0.7f);

            pixels.flip();

            return createTexture(pixels, size);
        } catch (Exception e) {
            logger.error("Failed to generate fallback thumbnail", e);
            return 0;
        }
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
     * Gets a representative color for a block type (fallback).
     */
    private int getBlockColor(BlockType blockType) {
        // Fallback colors based on block type for visual representation
        return switch (blockType) {
            case GRASS -> 0xFF7CB342; // Green
            case DIRT -> 0xFF8D6E63;  // Brown
            case STONE -> 0xFF9E9E9E; // Gray
            case COAL_ORE -> 0xFF424242; // Dark gray
            case IRON_ORE -> 0xFFBCAAA4; // Tan
            case WATER -> 0xFF2196F3; // Blue
            case SAND -> 0xFFFFF176; // Light yellow
            case PINE -> 0xFF6D4C41; // Dark brown
            case ROSE -> 0xFFE91E63; // Pink
            case DANDELION -> 0xFFFFEB3B; // Yellow
            case LEAVES -> 0xFF4CAF50; // Green
            case PINE_LEAVES -> 0xFF2E7D32; // Dark green
            case ELM_LEAVES -> 0xFF66BB6A; // Light green
            case WOOD -> 0xFF8D6E63; // Brown
            case WOOD_PLANKS -> 0xFFA1887F; // Light brown
            case PINE_WOOD_PLANKS -> 0xFF6D4C41; // Dark brown
            case ELM_WOOD_LOG -> 0xFF795548; // Medium brown
            case ELM_WOOD_PLANKS -> 0xFF9C786C; // Elm brown
            case COBBLESTONE -> 0xFF757575; // Gray
            case GRAVEL -> 0xFF8C8C8C; // Light gray
            case CLAY -> 0xFF90A4AE; // Blue-gray
            case BEDROCK -> 0xFF424242; // Very dark gray
            case SANDSTONE -> 0xFFDEB887; // Tan
            case RED_SANDSTONE -> 0xFFCD853F; // Orange-tan
            case RED_SAND -> 0xFFFF8A65; // Orange
            case SNOWY_DIRT -> 0xFFECEFF1; // White-gray
            case ICE -> 0xFF81D4FA; // Light blue
            case SNOW -> 0xFFFFFFFF; // White
            case MAGMA -> 0xFFFF5722; // Deep orange
            case CRYSTAL -> 0xFFAB47BC; // Purple
            case WORKBENCH -> 0xFF795548; // Brown
            case RED_SAND_COBBLESTONE -> 0xFFBF360C; // Dark orange
            case SAND_COBBLESTONE -> 0xFFD7CCC8; // Light tan
            default -> 0xFFBDBDBD; // Light gray
        };
    }

    /**
     * Fills buffer with checkerboard pattern.
     */
    private void fillCheckerboard(ByteBuffer pixels, int size) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean light = ((x / CHECKER_SIZE) + (y / CHECKER_SIZE)) % 2 == 0;
                int color = light ? CHECKER_LIGHT : CHECKER_DARK;
                putPixel(pixels, color);
            }
        }
    }

    /**
     * Draws a border around the thumbnail.
     */
    private void drawBorder(ByteBuffer pixels, int size, int borderColor) {
        pixels.position(0);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (x == 0 || y == 0 || x == size - 1 || y == size - 1) {
                    pixels.position((y * size + x) * 4);
                    putPixel(pixels, borderColor);
                }
            }
        }
    }

    /**
     * Overlays a color with alpha blending.
     */
    private void overlayColor(ByteBuffer pixels, int size, int color, float alpha) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        int a = (int) (alpha * 255);

        pixels.position(0);
        for (int y = 1; y < size - 1; y++) {
            for (int x = 1; x < size - 1; x++) {
                pixels.position((y * size + x) * 4);

                // Simple alpha blend over existing pixel
                int existingR = pixels.get() & 0xFF;
                int existingG = pixels.get() & 0xFF;
                int existingB = pixels.get() & 0xFF;
                int existingA = pixels.get() & 0xFF;

                int blendedR = (r * a + existingR * (255 - a)) / 255;
                int blendedG = (g * a + existingG * (255 - a)) / 255;
                int blendedB = (b * a + existingB * (255 - a)) / 255;

                pixels.position((y * size + x) * 4);
                pixels.put((byte) blendedR);
                pixels.put((byte) blendedG);
                pixels.put((byte) blendedB);
                pixels.put((byte) 255);
            }
        }
    }

    /**
     * Puts a pixel in RGBA format with full opacity.
     */
    private void putPixel(ByteBuffer pixels, int color) {
        pixels.put((byte) ((color >> 16) & 0xFF)); // R
        pixels.put((byte) ((color >> 8) & 0xFF));  // G
        pixels.put((byte) (color & 0xFF));         // B
        pixels.put((byte) 0xFF);                   // A - Always fully opaque
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
