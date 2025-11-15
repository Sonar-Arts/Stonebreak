package com.openmason.rendering.blockmodel;

/**
 * Result of loading an OMT texture file.
 * Contains both the OpenGL texture ID and metadata about the texture.
 *
 * <p>Design Principles:
 * <ul>
 *   <li>KISS: Simple data container with essential properties</li>
 *   <li>SOLID: Single responsibility - holds texture load results only</li>
 *   <li>Immutable: All fields final for thread safety</li>
 * </ul>
 *
 * @since 1.0
 */
public class TextureLoadResult {

    private final int textureId;
    private final int width;
    private final int height;
    private final boolean success;
    private final boolean hasTransparency;

    /**
     * Creates a successful texture load result.
     *
     * @param textureId OpenGL texture ID
     * @param width texture width in pixels
     * @param height texture height in pixels
     * @param hasTransparency true if texture contains transparent/translucent pixels
     */
    public TextureLoadResult(int textureId, int width, int height, boolean hasTransparency) {
        this.textureId = textureId;
        this.width = width;
        this.height = height;
        this.success = textureId > 0;
        this.hasTransparency = hasTransparency;
    }

    /**
     * Creates a failed texture load result.
     *
     * @return failed result with 0 textureId
     */
    public static TextureLoadResult failed() {
        return new TextureLoadResult(0, 0, 0, false);
    }

    /**
     * Gets the OpenGL texture ID.
     *
     * @return texture ID, or 0 if load failed
     */
    public int getTextureId() {
        return textureId;
    }

    /**
     * Gets the texture width.
     *
     * @return width in pixels
     */
    public int getWidth() {
        return width;
    }

    /**
     * Gets the texture height.
     *
     * @return height in pixels
     */
    public int getHeight() {
        return height;
    }

    /**
     * Checks if the texture loaded successfully.
     *
     * @return true if textureId > 0
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * Checks if the texture contains transparent or translucent pixels.
     *
     * @return true if texture has alpha values less than 255
     */
    public boolean hasTransparency() {
        return hasTransparency;
    }

    /**
     * Checks if this is a cube net texture (64x48).
     *
     * @return true if dimensions are 64x48
     */
    public boolean isCubeNet() {
        return width == 64 && height == 48;
    }

    /**
     * Checks if this is a flat texture (16x16).
     *
     * @return true if dimensions are 16x16
     */
    public boolean isFlat16x16() {
        return width == 16 && height == 16;
    }

    @Override
    public String toString() {
        if (!success) {
            return "TextureLoadResult{failed}";
        }
        return String.format("TextureLoadResult{id=%d, %dx%d, type=%s, transparency=%s}",
            textureId, width, height, isCubeNet() ? "CUBE_NET" : "FLAT",
            hasTransparency ? "YES" : "NO");
    }
}
