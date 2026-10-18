package com.openmason.engine.rendering.sprite;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The one sprite → voxel layout (#381): held items in the game and the editor's .SBT viewport
 * both build their geometry here, so these pin what both see.
 */
class SpriteVoxelsTest {

    private static final float EPS = 1e-6f;

    @Test
    void oneVoxelPerVisiblePixelCentredAndFlipped() {
        BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, 0xFFFF0000);           // top-left, red
        img.setRGB(1, 0, 0x05FFFFFF);           // below the alpha threshold: skipped
        img.setRGB(0, 1, 0x8000FF00);           // bottom-left, half-transparent green
        img.setRGB(1, 1, 0xFF0000FF);           // bottom-right, blue

        List<VoxelData> voxels = SpriteVoxels.voxelize(img, argb -> argb == 0xFF0000FF ? 0.75f : 0.25f);

        assertEquals(3, voxels.size());
        VoxelData topLeft = voxels.get(0);
        float start = -(2 * SpriteVoxels.SPRITE_SCALE) / 2f;
        assertEquals(start, topLeft.getPosition().x, EPS);
        // image row 0 is the top: it lands one row up, plus the hand offset
        assertEquals(start + SpriteVoxels.SPRITE_SCALE + SpriteVoxels.VERTICAL_OFFSET, topLeft.getPosition().y, EPS);
        assertEquals(0f, topLeft.getPosition().z, EPS);
        assertEquals(0xFFFF0000, topLeft.getOriginalRGBA());
        assertEquals(0, topLeft.getTextureX());
        assertEquals(0, topLeft.getTextureY());

        VoxelData green = voxels.get(1);
        assertEquals(0x8000FF00, green.getOriginalRGBA(), "partial alpha is kept");
        assertEquals(start + SpriteVoxels.VERTICAL_OFFSET, green.getPosition().y, EPS);
        assertEquals(2 * 0.0001f, green.getPosition().z, EPS, "per-pixel z step: index y*w+x");

        VoxelData blue = voxels.get(2);
        assertEquals(0.75f, blue.getPaletteCoordinate(), EPS, "the palette lookup is the caller's");
        assertEquals(start + SpriteVoxels.SPRITE_SCALE, blue.getPosition().x, EPS);
    }

    @Test
    void anEmptySpriteHasNoVoxels() {
        assertEquals(0, SpriteVoxels.voxelize(new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB), argb -> 0f).size());
    }
}
