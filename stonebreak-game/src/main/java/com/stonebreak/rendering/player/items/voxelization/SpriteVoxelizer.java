package com.stonebreak.rendering.player.items.voxelization;

import com.stonebreak.items.ItemType;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.joml.Quaternionf;
import org.joml.AxisAngle4f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts 2D item sprites into 3D voxel representations.
 * Loads sprite textures and generates voxel data for rendering.
 */
public class SpriteVoxelizer {

    // Cache for loaded sprite images
    private static final Map<ItemType, BufferedImage> spriteCache = new HashMap<>();

    // Voxel size in world units (each pixel becomes a cube this size)
    private static final float DEFAULT_VOXEL_SIZE = 0.02f; // Original size

    // Depth of the voxelized sprite (how thick the 3D projection is)
    private static final float DEFAULT_DEPTH = 0.08f; // 2 voxels deep

    // Scale factor for positioning the voxelized sprite
    private static final float SPRITE_SCALE = 0.02f; // Original size

    // Vertical offset to raise the tool in the hand
    private static final float VERTICAL_OFFSET = 0.3f;

    /**
     * Converts a 2D item sprite into 3D voxel data using color palette mapping.
     *
     * @param itemType The item type to voxelize
     * @param colorPalette The color palette for texture coordinate mapping
     * @return List of voxel data representing the 3D sprite
     */
    public static List<VoxelData> voxelizeSprite(ItemType itemType, ColorPalette colorPalette) {
        // System.out.println("Starting voxelization for " + itemType.getName()); // Debug only
        BufferedImage sprite = loadSprite(itemType);
        if (sprite == null) {
            System.err.println("Failed to load sprite for " + itemType.getName() + ", returning empty voxel list");
            return new ArrayList<>(); // Return empty list if sprite can't be loaded
        }

        List<VoxelData> voxels = new ArrayList<>();
        int width = sprite.getWidth();
        int height = sprite.getHeight();

        // Center the sprite around origin
        float startX = -(width * SPRITE_SCALE) / 2.0f;
        float startY = -(height * SPRITE_SCALE) / 2.0f;

        // Process each pixel in the sprite
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgba = sprite.getRGB(x, y);

                // Extract RGBA components
                int alpha = (rgba >> 24) & 0xFF;
                int red = (rgba >> 16) & 0xFF;
                int green = (rgba >> 8) & 0xFF;
                int blue = rgba & 0xFF;

                // Debug: Print first few pixels (disabled for production)
                // if (x < 3 && y < 3) {
                //     System.out.printf("Pixel (%d,%d): RGBA=(%d,%d,%d,%d) raw=0x%08X%n",
                //         x, y, red, green, blue, alpha, rgba);
                // }

                // Skip transparent pixels - handle different image formats
                // For images without alpha channel, alpha might be 0 but we should still process the pixel
                boolean isTransparent = (alpha < 1) && (red == 0 && green == 0 && blue == 0);
                if (isTransparent) {
                    continue;
                }

                // Get palette coordinate for this color
                int finalAlpha = alpha == 0 ? 255 : alpha;
                int finalRGBA = (finalAlpha << 24) | (red << 16) | (green << 8) | blue;
                float paletteCoordinate = colorPalette.getTextureCoordinate(rgba);

                // Calculate voxel position
                // Flip Y coordinate since image coordinates are top-down
                float voxelX = startX + (x * SPRITE_SCALE);
                float voxelY = startY + ((height - 1 - y) * SPRITE_SCALE) + VERTICAL_OFFSET;

                // Create single voxel per pixel with proper Z spacing to avoid z-fighting
                // Use a small offset based on pixel position to ensure unique Z values
                float voxelZ = (y * width + x) * 0.0001f; // Tiny offset per pixel

                // Create voxel data with palette coordinate
                VoxelData voxel = new VoxelData(
                    voxelX, voxelY, voxelZ,
                    paletteCoordinate,
                    finalRGBA,
                    x, y
                );

                voxels.add(voxel);
            }
        }

        // System.out.println("Voxelization complete for " + itemType.getName() + ": " + voxels.size() + " voxels generated"); // Debug only
        return voxels;
    }

    /**
     * Convenience method that creates a color palette and voxelizes a sprite in one call.
     * Use this for simple cases where you don't need to manage the palette separately.
     *
     * @param itemType The item type to voxelize
     * @return VoxelizationResult containing both the palette and voxel data
     */
    public static VoxelizationResult voxelizeSpriteWithPalette(ItemType itemType) {
        BufferedImage sprite = loadSprite(itemType);
        if (sprite == null) {
            return new VoxelizationResult(null, new ArrayList<>());
        }

        ColorPalette palette = new ColorPalette(itemType, sprite);
        List<VoxelData> voxels = voxelizeSprite(itemType, palette);

        return new VoxelizationResult(palette, voxels);
    }

    /**
     * Result container for voxelization with palette.
     */
    public static class VoxelizationResult {
        private final ColorPalette colorPalette;
        private final List<VoxelData> voxels;

        public VoxelizationResult(ColorPalette colorPalette, List<VoxelData> voxels) {
            this.colorPalette = colorPalette;
            this.voxels = voxels;
        }

        public ColorPalette getColorPalette() {
            return colorPalette;
        }

        public List<VoxelData> getVoxels() {
            return voxels;
        }

        public boolean isValid() {
            return colorPalette != null && !voxels.isEmpty();
        }
    }

    /**
     * Loads a sprite image for the given item type.
     * Uses caching to avoid reloading the same sprite multiple times.
     */
    private static BufferedImage loadSprite(ItemType itemType) {
        if (spriteCache.containsKey(itemType)) {
            return spriteCache.get(itemType);
        }

        String spritePath = getSpritePathForItem(itemType);
        if (spritePath == null) {
            System.err.println("No sprite path defined for item: " + itemType.getName());
            return null;
        }

        try {
            // Try different approaches for module compatibility (following existing patterns)
            InputStream inputStream = null;

            // First try: Module's class loader
            inputStream = SpriteVoxelizer.class.getClassLoader().getResourceAsStream(spritePath);

            // Second try: Module class itself with leading slash
            if (inputStream == null) {
                inputStream = SpriteVoxelizer.class.getResourceAsStream("/" + spritePath);
            }

            // Third try: Context class loader
            if (inputStream == null) {
                inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(spritePath);
            }

            if (inputStream == null) {
                System.err.println("Could not find sprite file using any method: " + spritePath);
                return null;
            }

            BufferedImage rawImage = ImageIO.read(inputStream);
            if (rawImage == null) {
                System.err.println("ImageIO.read returned null for " + spritePath);
                inputStream.close();
                return null;
            }

            // Convert to ARGB format to ensure consistent alpha handling
            BufferedImage image = new BufferedImage(rawImage.getWidth(), rawImage.getHeight(), BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g2d = image.createGraphics();
            g2d.drawImage(rawImage, 0, 0, null);
            g2d.dispose();

            spriteCache.put(itemType, image);
            inputStream.close();

            // System.out.println("Successfully loaded sprite for " + itemType.getName() + " (" + image.getWidth() + "x" + image.getHeight() + ", type=" + image.getType() + ")"); // Debug only
            return image;

        } catch (IOException e) {
            System.err.println("Failed to load sprite for " + itemType.getName() + ": " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Gets the resource path for a sprite based on item type.
     */
    private static String getSpritePathForItem(ItemType itemType) {
        switch (itemType) {
            case WOODEN_PICKAXE:
                return "Items/Textures/wooden_pickaxe_texture.png";
            case WOODEN_AXE:
                return "Items/Textures/wooden_axe_texture.png";
            case STICK:
                return "Items/Textures/stick_texture.png";
            default:
                return null;
        }
    }


    /**
     * Gets the default voxel size for rendering.
     */
    public static float getDefaultVoxelSize() {
        return DEFAULT_VOXEL_SIZE;
    }

    /**
     * Checks if an item type supports voxelized rendering.
     */
    public static boolean isVoxelizable(ItemType itemType) {
        return getSpritePathForItem(itemType) != null;
    }

    /**
     * Clears the sprite cache (useful for resource management).
     */
    public static void clearCache() {
        spriteCache.clear();
    }

    /**
     * Gets information about a voxelized sprite for debugging.
     */
    public static String getVoxelizationInfo(ItemType itemType) {
        VoxelizationResult result = voxelizeSpriteWithPalette(itemType);
        BufferedImage sprite = loadSprite(itemType);

        if (sprite == null || !result.isValid()) {
            return "No voxelization data available for " + itemType.getName();
        }

        return String.format("Voxelized %s: %dx%d sprite -> %d voxels (%d colors)",
            itemType.getName(),
            sprite.getWidth(),
            sprite.getHeight(),
            result.getVoxels().size(),
            result.getColorPalette().getColorCount());
    }

    /**
     * Tests voxelization for all supported items and prints results.
     * Useful for debugging and verifying the system works correctly.
     */
    public static void testVoxelization() {
        System.out.println("=== Sprite Voxelization Test ===");

        for (ItemType itemType : ItemType.values()) {
            if (isVoxelizable(itemType)) {
                try {
                    String info = getVoxelizationInfo(itemType);
                    System.out.println(info);

                    // Try to create voxel data to ensure no exceptions
                    VoxelizationResult result = voxelizeSpriteWithPalette(itemType);
                    if (!result.getVoxels().isEmpty()) {
                        VoxelData firstVoxel = result.getVoxels().get(0);
                        System.out.println("  First voxel: " + firstVoxel);
                        System.out.println("  Palette colors: " + result.getColorPalette().getColorCount());
                    }
                } catch (Exception e) {
                    System.err.println("Error voxelizing " + itemType.getName() + ": " + e.getMessage());
                }
            }
        }

        System.out.println("=== Test Complete ===");
    }

    /**
     * Main method for standalone testing of the voxelization system.
     * Can be run independently to debug sprite loading issues.
     */
    public static void main(String[] args) {
        System.out.println("=== Standalone Sprite Voxelizer Test ===");

        // Test each item individually
        ItemType[] testItems = {ItemType.STICK, ItemType.WOODEN_PICKAXE, ItemType.WOODEN_AXE};

        for (ItemType itemType : testItems) {
            System.out.println("\n--- Testing " + itemType.getName() + " ---");

            // Test if path is defined
            String path = getSpritePathForItem(itemType);
            System.out.println("Sprite path: " + path);

            if (path == null) {
                System.err.println("No sprite path defined!");
                continue;
            }

            // Test sprite loading
            BufferedImage sprite = loadSprite(itemType);
            if (sprite == null) {
                System.err.println("Failed to load sprite!");
                continue;
            }

            System.out.println("Sprite loaded: " + sprite.getWidth() + "x" + sprite.getHeight() + " (type=" + sprite.getType() + ")");

            // Test voxelization with palette
            VoxelizationResult result = voxelizeSpriteWithPalette(itemType);
            System.out.println("Voxels generated: " + result.getVoxels().size());
            System.out.println("Palette colors: " + (result.getColorPalette() != null ? result.getColorPalette().getColorCount() : 0));

            if (!result.getVoxels().isEmpty()) {
                VoxelData first = result.getVoxels().get(0);
                System.out.println("First voxel: " + first);
            }
        }

        System.out.println("\n=== Standalone Test Complete ===");
    }
}