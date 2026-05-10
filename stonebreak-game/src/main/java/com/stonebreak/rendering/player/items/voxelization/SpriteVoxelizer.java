package com.stonebreak.rendering.player.items.voxelization;

import com.stonebreak.items.ItemType;
import com.stonebreak.items.registry.ItemRegistry;
import com.openmason.engine.format.omt.OMTReader;
import com.openmason.engine.format.omt.OMTArchive;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.joml.Quaternionf;
import org.joml.AxisAngle4f;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
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

    /**
     * Composite cache key: an item type plus an optional SBO state name.
     * Different states render with different OMT bytes and so must cache
     * independently. {@code state == null} keys the default state.
     */
    public record SpriteKey(ItemType itemType, String state) {
        public static SpriteKey of(ItemType type) { return new SpriteKey(type, null); }
        public static SpriteKey of(ItemType type, String state) {
            return new SpriteKey(type, (state == null || state.isBlank()) ? null : state);
        }
    }

    // Cache for loaded sprite images, keyed by (itemType, state).
    private static final Map<SpriteKey, BufferedImage> spriteCache = new HashMap<>();

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
        return voxelizeSprite(itemType, null, colorPalette);
    }

    /**
     * State-aware variant of {@link #voxelizeSprite(ItemType, ColorPalette)}.
     */
    public static List<VoxelData> voxelizeSprite(ItemType itemType, String state, ColorPalette colorPalette) {
        BufferedImage sprite = loadSprite(itemType, state);
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

                // Skip transparent pixels based on alpha channel
                // Alpha < 10 is considered fully transparent (threshold for anti-aliasing artifacts)
                if (alpha < 10) {
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
        return voxelizeSpriteWithPalette(itemType, null);
    }

    /**
     * State-aware variant of {@link #voxelizeSpriteWithPalette(ItemType)}.
     */
    public static VoxelizationResult voxelizeSpriteWithPalette(ItemType itemType, String state) {
        BufferedImage sprite = loadSprite(itemType, state);
        if (sprite == null) {
            return new VoxelizationResult(null, new ArrayList<>());
        }

        ColorPalette palette = new ColorPalette(itemType, sprite);
        List<VoxelData> voxels = voxelizeSprite(itemType, state, palette);

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
     *
     * <p>Resolution order:
     * <ol>
     *   <li>{@link ItemRegistry} lookup by namespaced ID — texture-only SBOs
     *       under {@code sbo/items/} are auto-discovered. Sprite is composited
     *       from the embedded OMT.</li>
     *   <li>Hardcoded PNG path for legacy items (sticks, picks, axes,
     *       buckets) that haven't been migrated to SBO yet.</li>
     * </ol>
     * Items that previously routed through .sbt files now flow through the
     * SBO branch via {@link #loadSpriteFromSboItem(ItemType)}.
     */
    private static BufferedImage loadSprite(ItemType itemType) {
        return loadSprite(itemType, null);
    }

    /**
     * State-aware variant. The cache key is keyed on (itemType, state) so
     * different states keep independent images.
     */
    private static BufferedImage loadSprite(ItemType itemType, String state) {
        SpriteKey key = SpriteKey.of(itemType, state);
        if (spriteCache.containsKey(key)) {
            return spriteCache.get(key);
        }

        BufferedImage sboImage = loadSpriteFromSboItem(itemType, state);
        if (sboImage != null) {
            spriteCache.put(key, sboImage);
            return sboImage;
        }

        String spritePath = getSpritePathForItem(itemType);
        if (spritePath == null) {
            System.err.println("No sprite path or item SBO defined for: " + itemType.getName());
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

            spriteCache.put(SpriteKey.of(itemType, state), image);
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
     * Gets the PNG resource path for a sprite based on item type. Only legacy
     * PNG-backed items remain here; SBO-backed items resolve via
     * {@link #loadSpriteFromSboItem(ItemType)}.
     */
    private static String getSpritePathForItem(ItemType itemType) {
        return null;
    }


    /**
     * True if this item has an SBO entry in the item registry (i.e. the
     * voxelizer will pull its sprite from {@code sbo/items/}). Used by UI
     * code that needs to gate SBO-vs-PNG rendering paths without forcing a
     * full sprite load.
     */
    public static boolean isSboBackedItem(ItemType itemType) {
        return ItemRegistry.getInstance().get(sboItemId(itemType)).isPresent();
        switch (itemType) {
            case WOODEN_PICKAXE:
                return "Items/Textures/wooden_pickaxe_texture.png";
            case WOODEN_AXE:
                return "Items/Textures/wooden_axe_texture.png";
            case STICK:
                return "Items/Textures/stick_texture.png";
            case WOODEN_BUCKET:
                return "Items/Textures/wooden_bucket_base.png";
            case WOODEN_BUCKET_WATER:
                return "Items/Textures/wooden_bucket_water.png";
            case SWORD:
                return "Items/Textures/SBT/Sword.sbt";
            case WAR_AXE:
                return "Items/Textures/SBT/WarAxe.sbt";
            case PATTY_SMACKER:
                return "Items/Textures/SBT/Patty Smacker.sbt";
            case SNOWBALL:
                return "Items/Textures/SBT/Snowball.sbt";
            case STONE_SHOVEL:
                return "Items/Textures/SBT/Stone_Shovel.sbt";
            case WOODEN_SHOVEL:
                return "Items/Textures/SBT/Wooden_Shovel.sbt";
            case DAGGER:
                return "Items/Textures/SBT/Dagger.sbt";
            case STAFF:
                return "Items/Textures/SBT/Staff.sbt";
            default:
                return null;
        }
    }

    /**
     * Compose and return the BufferedImage for an SBO-backed item, or null if
     * the item isn't registered as an SBO. Used by UI renderers that need a
     * raster image (NanoVG/Skija) rather than the voxelized form.
     */
    public static BufferedImage loadSpriteFromSboItem(ItemType itemType) {
        return loadSpriteFromSboItem(itemType, null);
    }

    /**
     * State-aware variant — when the SBO declares states (1.3+), pulls the
     * OMT for the requested state. Falls back to the default state for
     * unknown/null state names.
     */
    public static BufferedImage loadSpriteFromSboItem(ItemType itemType, String state) {
        var entry = ItemRegistry.getInstance().get(sboItemId(itemType)).orElse(null);
        if (entry == null) return null;
        byte[] omtBytes = entry.omtBytesFor(state);
        if (omtBytes == null || omtBytes.length == 0) {
            System.err.println("SBO item has empty OMT payload: " + entry.objectId()
                    + (state != null ? " (state=" + state + ")" : ""));
            return null;
        }
        try {
            OMTArchive archive = new OMTReader().read(omtBytes);
            return compositeLayers(archive);
        } catch (IOException e) {
            System.err.println("Failed to decode SBO item OMT for " + entry.objectId() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * The namespaced object ID convention used by the migration utility:
     * {@code stonebreak:<itemtype_name_lowercased>}. Kept in sync with
     * {@code SBTToSBOItemMigration.convertOne}.
     */
    public static String sboItemId(ItemType itemType) {
        if (itemType == null) return null;
        // Prefer the explicit objectId registered when the ItemType was
        // created from the SBO registry (handles cases where the SBO's
        // objectId doesn't match the default "stonebreak:<enum>" convention,
        // e.g. WOODEN_BUCKET → stonebreak:sb_wooden_bucket).
        String explicit = ItemType.objectIdFor(itemType);
        if (explicit != null) return explicit;
        return "stonebreak:" + itemType.name().toLowerCase();
    }

    private static BufferedImage compositeLayers(OMTArchive archive) throws IOException {
        int w = archive.canvasSize().width();
        int h = archive.canvasSize().height();
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = result.createGraphics();
        try {
            for (OMTArchive.Layer layer : archive.layers()) {
                if (!layer.visible() || layer.opacity() <= 0f) continue;
                BufferedImage layerImg = ImageIO.read(new ByteArrayInputStream(layer.pngBytes()));
                if (layerImg == null) continue;
                g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, layer.opacity()));
                g2d.drawImage(layerImg, 0, 0, null);
            }
        } finally {
            g2d.dispose();
        }
        return result;
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
        return getSpritePathForItem(itemType) != null || isSboBackedItem(itemType);
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