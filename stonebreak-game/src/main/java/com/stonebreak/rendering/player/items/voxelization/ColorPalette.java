package com.stonebreak.rendering.player.items.voxelization;

import com.stonebreak.items.ItemType;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.*;

/**
 * Manages color palettes for voxelized sprites.
 * Creates 1D textures containing the unique colors from each sprite
 * for proper color mapping in the shader system.
 */
public class ColorPalette {

    private final ItemType itemType;
    private final List<Integer> uniqueColors;
    private final Map<Integer, Integer> colorToIndex;
    private int textureId = -1;
    private boolean created = false;

    /**
     * Creates a color palette from a sprite image.
     */
    public ColorPalette(ItemType itemType, BufferedImage sprite) {
        this.itemType = itemType;
        this.uniqueColors = new ArrayList<>();
        this.colorToIndex = new HashMap<>();

        extractColors(sprite);
    }

    /**
     * Extracts unique colors from the sprite image.
     */
    private void extractColors(BufferedImage sprite) {
        Set<Integer> colorSet = new HashSet<>();

        int width = sprite.getWidth();
        int height = sprite.getHeight();

        // Scan all pixels to find unique colors
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgba = sprite.getRGB(x, y);

                // Extract alpha to check for transparency
                int alpha = (rgba >> 24) & 0xFF;
                int red = (rgba >> 16) & 0xFF;
                int green = (rgba >> 8) & 0xFF;
                int blue = rgba & 0xFF;

                // Skip transparent pixels (same logic as voxelizer)
                boolean isTransparent = (alpha < 1) && (red == 0 && green == 0 && blue == 0);
                if (isTransparent) {
                    continue;
                }

                // Convert to RGBA with proper alpha handling
                int finalAlpha = alpha == 0 ? 255 : alpha; // Use opaque if no alpha channel
                int finalColor = (finalAlpha << 24) | (red << 16) | (green << 8) | blue;

                colorSet.add(finalColor);
            }
        }

        // Convert set to list and create index mapping
        uniqueColors.addAll(colorSet);
        Collections.sort(uniqueColors); // Sort for consistent ordering

        for (int i = 0; i < uniqueColors.size(); i++) {
            colorToIndex.put(uniqueColors.get(i), i);
        }

        // Debug output removed - uncomment if needed for debugging
        // System.out.println("Color palette for " + itemType.getName() + ": " + uniqueColors.size() + " unique colors");
    }

    /**
     * Gets the texture coordinate for a given color.
     * Returns the U coordinate for the 1D palette texture.
     */
    public float getTextureCoordinate(int rgba) {
        // Convert input color to same format as our palette
        int alpha = (rgba >> 24) & 0xFF;
        int red = (rgba >> 16) & 0xFF;
        int green = (rgba >> 8) & 0xFF;
        int blue = rgba & 0xFF;

        // Handle alpha the same way as during extraction
        int finalAlpha = alpha == 0 ? 255 : alpha;
        int finalColor = (finalAlpha << 24) | (red << 16) | (green << 8) | blue;

        Integer index = colorToIndex.get(finalColor);
        if (index == null) {
            // Fallback to first color if not found
            System.err.println("Color not found in palette: " + Integer.toHexString(rgba));
            return 0.0f;
        }

        // Return normalized coordinate (0.0 to 1.0)
        // Add 0.5 to sample from center of texel
        return (index + 0.5f) / uniqueColors.size();
    }

    /**
     * Creates the OpenGL 1D texture for this color palette.
     */
    public void createTexture() {
        if (created || uniqueColors.isEmpty()) {
            return;
        }

        // Create texture data
        ByteBuffer textureData = BufferUtils.createByteBuffer(uniqueColors.size() * 4); // RGBA

        for (int color : uniqueColors) {
            int alpha = (color >> 24) & 0xFF;
            int red = (color >> 16) & 0xFF;
            int green = (color >> 8) & 0xFF;
            int blue = color & 0xFF;

            textureData.put((byte) red);
            textureData.put((byte) green);
            textureData.put((byte) blue);
            textureData.put((byte) alpha);
        }
        textureData.flip();

        // Generate OpenGL texture
        textureId = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_1D, textureId);

        // Set texture parameters
        GL11.glTexParameteri(GL11.GL_TEXTURE_1D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_1D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_1D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);

        // Upload texture data
        GL11.glTexImage1D(GL11.GL_TEXTURE_1D, 0, GL11.GL_RGBA8, uniqueColors.size(), 0,
                         GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, textureData);

        GL11.glBindTexture(GL11.GL_TEXTURE_1D, 0);
        created = true;

        System.out.println("Created color palette texture for " + itemType.getName() +
                          " (ID: " + textureId + ", " + uniqueColors.size() + " colors)");
    }

    /**
     * Binds this color palette texture for rendering.
     */
    public void bind() {
        if (!created) {
            createTexture();
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_1D, textureId);
    }

    /**
     * Unbinds the current 1D texture.
     */
    public void unbind() {
        GL11.glBindTexture(GL11.GL_TEXTURE_1D, 0);
    }

    /**
     * Gets the OpenGL texture ID.
     */
    public int getTextureId() {
        if (!created) {
            createTexture();
        }
        return textureId;
    }

    /**
     * Gets the number of unique colors in this palette.
     */
    public int getColorCount() {
        return uniqueColors.size();
    }

    /**
     * Gets the item type this palette belongs to.
     */
    public ItemType getItemType() {
        return itemType;
    }

    /**
     * Checks if this palette has been created.
     */
    public boolean isCreated() {
        return created;
    }

    /**
     * Cleans up OpenGL resources.
     */
    public void cleanup() {
        if (created && textureId != -1) {
            GL11.glDeleteTextures(textureId);
            textureId = -1;
            created = false;
        }
    }

    /**
     * Gets debug information about this palette.
     */
    public String getDebugInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("ColorPalette[").append(itemType.getName()).append("]: ");
        sb.append(uniqueColors.size()).append(" colors");
        if (created) {
            sb.append(", textureID=").append(textureId);
        } else {
            sb.append(", not created");
        }
        return sb.toString();
    }
}