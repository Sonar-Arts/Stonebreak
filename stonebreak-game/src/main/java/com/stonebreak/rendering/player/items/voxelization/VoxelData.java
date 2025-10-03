package com.stonebreak.rendering.player.items.voxelization;

import org.joml.Vector3f;

/**
 * Represents a single voxel in a 3D sprite projection.
 * Contains position and texture coordinate information for rendering.
 */
public class VoxelData {

    private final Vector3f position;
    private final float paletteCoordinate;
    private final int originalRGBA;
    private final int textureX;
    private final int textureY;

    /**
     * Creates a new voxel with position and palette coordinate information.
     *
     * @param x World X position
     * @param y World Y position
     * @param z World Z position
     * @param paletteCoordinate Texture coordinate for color palette (0.0-1.0)
     * @param originalRGBA Original RGBA color value
     * @param textureX Original texture X coordinate
     * @param textureY Original texture Y coordinate
     */
    public VoxelData(float x, float y, float z, float paletteCoordinate, int originalRGBA, int textureX, int textureY) {
        this.position = new Vector3f(x, y, z);
        this.paletteCoordinate = paletteCoordinate;
        this.originalRGBA = originalRGBA;
        this.textureX = textureX;
        this.textureY = textureY;
    }

    /**
     * Creates a new voxel with Vector3f position.
     */
    public VoxelData(Vector3f position, float paletteCoordinate, int originalRGBA, int textureX, int textureY) {
        this.position = new Vector3f(position);
        this.paletteCoordinate = paletteCoordinate;
        this.originalRGBA = originalRGBA;
        this.textureX = textureX;
        this.textureY = textureY;
    }

    /**
     * Gets the world position of this voxel.
     */
    public Vector3f getPosition() {
        return new Vector3f(position);
    }

    /**
     * Gets the palette texture coordinate for this voxel.
     */
    public float getPaletteCoordinate() {
        return paletteCoordinate;
    }

    /**
     * Gets the original RGBA color value.
     */
    public int getOriginalRGBA() {
        return originalRGBA;
    }

    /**
     * Gets the original texture X coordinate.
     */
    public int getTextureX() {
        return textureX;
    }

    /**
     * Gets the original texture Y coordinate.
     */
    public int getTextureY() {
        return textureY;
    }

    /**
     * Checks if this voxel is transparent.
     */
    public boolean isTransparent() {
        int alpha = (originalRGBA >> 24) & 0xFF;
        return alpha < 255;
    }

    /**
     * Checks if this voxel is fully opaque.
     */
    public boolean isOpaque() {
        return !isTransparent();
    }

    @Override
    public String toString() {
        int alpha = (originalRGBA >> 24) & 0xFF;
        int red = (originalRGBA >> 16) & 0xFF;
        int green = (originalRGBA >> 8) & 0xFF;
        int blue = originalRGBA & 0xFF;

        return String.format("VoxelData{pos=(%.3f,%.3f,%.3f), paletteCoord=%.3f, rgba=(%d,%d,%d,%d), tex=(%d,%d)}",
            position.x, position.y, position.z, paletteCoordinate,
            red, green, blue, alpha,
            textureX, textureY);
    }
}