package com.openmason.engine.rendering.sprite;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a 2D sprite into 3D voxels: one voxel per visible pixel, the layout every held/dropped
 * item sprite uses (#381). One implementation so the game's held items and the editor's .SBT
 * viewport produce the same geometry by construction.
 *
 * <p>The sprite is centred on the origin in X/Y (image rows flipped, raised by
 * {@link #VERTICAL_OFFSET}), and each voxel gets a tiny per-pixel Z offset so coplanar faces never
 * z-fight. Pixels with alpha below {@link #ALPHA_THRESHOLD} are skipped.
 */
public final class SpriteVoxels {

    /** Edge of one voxel in world units (a pixel becomes a cube this size). */
    public static final float VOXEL_SIZE = 0.02f;

    /** Thickness of the voxelized sprite (2 voxels). */
    public static final float DEPTH = 0.08f;

    /** Distance between neighbouring pixel voxels. */
    public static final float SPRITE_SCALE = 0.02f;

    /** Lift of the sprite above the origin (raises a tool in the hand). */
    public static final float VERTICAL_OFFSET = 0.3f;

    /** Pixels whose alpha is below this are transparent (anti-aliasing fringe included). */
    public static final int ALPHA_THRESHOLD = 10;

    /** Palette coordinate for an ARGB pixel ({@link ColorPalette#getTextureCoordinate}); {@code rgba -> 0f} for none. */
    @FunctionalInterface
    public interface PaletteCoordinates {
        float coordinateOf(int argb);
    }

    private SpriteVoxels() {
    }

    /**
     * The voxels of {@code sprite}, row by row, left to right. Each voxel carries its pixel's
     * colour (alpha forced opaque only when it was 0) and texel position.
     */
    public static List<VoxelData> voxelize(BufferedImage sprite, PaletteCoordinates palette) {
        List<VoxelData> voxels = new ArrayList<>();
        int width = sprite.getWidth();
        int height = sprite.getHeight();
        float startX = -(width * SPRITE_SCALE) / 2.0f;
        float startY = -(height * SPRITE_SCALE) / 2.0f;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgba = sprite.getRGB(x, y);
                int alpha = (rgba >> 24) & 0xFF;
                if (alpha < ALPHA_THRESHOLD) {
                    continue;
                }
                int red = (rgba >> 16) & 0xFF;
                int green = (rgba >> 8) & 0xFF;
                int blue = rgba & 0xFF;
                int finalAlpha = alpha == 0 ? 255 : alpha;
                int finalRGBA = (finalAlpha << 24) | (red << 16) | (green << 8) | blue;

                // Image rows run top-down; the tiny Z step keeps every voxel's depth unique.
                float voxelX = startX + (x * SPRITE_SCALE);
                float voxelY = startY + ((height - 1 - y) * SPRITE_SCALE) + VERTICAL_OFFSET;
                float voxelZ = (y * width + x) * 0.0001f;

                voxels.add(new VoxelData(voxelX, voxelY, voxelZ, palette.coordinateOf(rgba), finalRGBA, x, y));
            }
        }
        return voxels;
    }
}
