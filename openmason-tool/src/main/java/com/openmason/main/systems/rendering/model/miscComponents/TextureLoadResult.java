package com.openmason.main.systems.rendering.model.miscComponents;

/**
 * Result of loading an OMT texture file.
 */
public class TextureLoadResult {

    private final int textureId;
    private final int width;
    private final int height;
    private final boolean success;
    private final boolean hasTransparency;

    /**
     * Creates a successful texture load result.
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
     */
    public static TextureLoadResult failed() {
        return new TextureLoadResult(0, 0, 0, false);
    }

    /**
     * Gets the OpenGL texture ID.
     */
    public int getTextureId() {
        return textureId;
    }

    /**
     * Gets the texture width.
     */
    public int getWidth() {
        return width;
    }

    /**
     * Gets the texture height.
     */
    public int getHeight() {
        return height;
    }

    /**
     * Checks if the texture loaded successfully.
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * Checks if the texture contains transparent or translucent pixels.
     */
    public boolean hasTransparency() {
        return hasTransparency;
    }

    /**
     * Checks if this is a cube net texture (64x48).
     */
    public boolean isCubeNet() {
        return width == 64 && height == 48;
    }

    /**
     * Checks if this is a flat texture (16x16).
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
