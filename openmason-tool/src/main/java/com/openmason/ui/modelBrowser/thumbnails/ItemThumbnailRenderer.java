package com.openmason.ui.modelBrowser.thumbnails;

import com.stonebreak.items.ItemType;
import com.stonebreak.textures.atlas.AtlasMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Generates thumbnail textures for items by extracting from the texture atlas.
 * Supports multiple sizes (64x64, 32x32, 16x16) with proper alpha channel handling.
 *
 * <p>Features:</p>
 * <ul>
 *   <li>Checkerboard background for transparent item sprites</li>
 *   <li>Alpha compositing over checkerboard pattern</li>
 *   <li>Fallback colored rendering when textures unavailable</li>
 * </ul>
 */
public class ItemThumbnailRenderer {

    private static final Logger logger = LoggerFactory.getLogger(ItemThumbnailRenderer.class);

    // Checkerboard pattern colors
    private static final int CHECKER_LIGHT = 0xFFCCCCCC;
    private static final int CHECKER_DARK = 0xFF999999;
    private static final int CHECKER_SIZE = 8;

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
     * Creates a new item thumbnail renderer.
     *
     * @param cache The thumbnail cache to use
     */
    public ItemThumbnailRenderer(ModelBrowserThumbnailCache cache) {
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
                logger.info("Loaded texture atlas for items: {}x{} with {} textures",
                    atlasImage.getWidth(), atlasImage.getHeight(), atlasMetadata.getTextures().size());
            } else {
                logger.warn("Atlas files not found, using fallback rendering for items");
            }
        } catch (Exception e) {
            logger.error("Failed to load texture atlas for items", e);
        }
    }

    /**
     * Gets or generates a thumbnail for an item.
     *
     * @param itemType The item type
     * @param size The thumbnail size (64, 32, or 16)
     * @return OpenGL texture ID
     */
    public int getThumbnail(ItemType itemType, int size) {
        String key = ModelBrowserThumbnailCache.itemKey(itemType.name(), size);
        return cache.getOrCreate(key, () -> generateThumbnail(itemType, size));
    }

    /**
     * Generates a thumbnail texture for an item.
     */
    private int generateThumbnail(ItemType itemType, int size) {
        try {
            if (atlasLoaded) {
                return generateFromAtlas(itemType, size);
            } else {
                return generateFallback(itemType, size);
            }
        } catch (Exception e) {
            logger.error("Failed to generate thumbnail for item: " + itemType, e);
            return generateFallback(itemType, size);
        }
    }

    /**
     * Generates a thumbnail by extracting from the texture atlas.
     */
    private int generateFromAtlas(ItemType itemType, int size) throws Exception {
        // Get item texture name
        String textureName = getItemTextureName(itemType);

        if (textureName == null) {
            return generateFallback(itemType, size);
        }

        // Find the texture in the atlas
        AtlasMetadata.TextureEntry texture = atlasMetadata.findTexture(textureName);

        if (texture == null) {
            logger.warn("Item texture not found in atlas: {}", textureName);
            return generateFallback(itemType, size);
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
        // This works for item sprites with transparency
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
     * Generates a fallback thumbnail with colored overlay.
     */
    private int generateFallback(ItemType itemType, int size) {
        try {
            ByteBuffer pixels = ByteBuffer.allocateDirect(size * size * 4);

            int color = getItemColor(itemType);

            // Fill with checkerboard background
            fillCheckerboard(pixels, size);

            // Draw border
            drawBorder(pixels, size, BORDER_COLOR);

            // Overlay item color
            overlayColor(pixels, size, color, 0.8f);

            pixels.flip();

            return createTexture(pixels, size);

        } catch (Exception e) {
            logger.error("Failed to generate fallback thumbnail for item: " + itemType, e);
            return 0;
        }
    }

    /**
     * Maps ItemType to texture name in atlas.
     */
    private String getItemTextureName(ItemType itemType) {
        if (itemType == null) return null;

        return switch (itemType) {
            case STICK -> "stick";
            case WOODEN_PICKAXE -> "wooden_pickaxe";
            case WOODEN_AXE -> "wooden_axe";
            case WOODEN_BUCKET -> "wooden_bucket_base";
            case WOODEN_BUCKET_WATER -> "wooden_bucket_water";
            default -> null;
        };
    }

    /**
     * Gets a representative color for an item type (fallback only).
     * Used when texture atlas is unavailable.
     */
    private int getItemColor(ItemType itemType) {
        return switch (itemType) {
            case STICK -> 0xFF8D6E63; // Brown
            case WOODEN_PICKAXE -> 0xFF795548; // Dark brown
            case WOODEN_AXE -> 0xFF6D4C41; // Darker brown
            case WOODEN_BUCKET -> 0xFFA1887F; // Light brown
            case WOODEN_BUCKET_WATER -> 0xFF2196F3; // Blue (water)
            default -> 0xFFBDBDBD; // Light gray
        };
    }

    private void fillCheckerboard(ByteBuffer pixels, int size) {
        fillCheckerboardPattern(pixels, size);
    }

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

    private void overlayColor(ByteBuffer pixels, int size, int color, float alpha) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        int a = (int) (alpha * 255);

        pixels.position(0);
        for (int y = 1; y < size - 1; y++) {
            for (int x = 1; x < size - 1; x++) {
                pixels.position((y * size + x) * 4);

                int existingR = pixels.get() & 0xFF;
                int existingG = pixels.get() & 0xFF;
                int existingB = pixels.get() & 0xFF;
                pixels.get(); // Skip A

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

    private void putPixel(ByteBuffer pixels, int color) {
        pixels.put((byte) ((color >> 16) & 0xFF)); // R
        pixels.put((byte) ((color >> 8) & 0xFF));  // G
        pixels.put((byte) (color & 0xFF));         // B
        pixels.put((byte) 0xFF);                   // A - Always fully opaque
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

    private int createTexture(ByteBuffer pixels, int size) {
        int textureId = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);

        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, size, size, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        return textureId;
    }
}
