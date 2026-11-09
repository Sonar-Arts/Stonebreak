package com.stonebreak.fx.bindings;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The colour {@code tintFromTexture} bindings take from a block (Stonebreak#437). */
class BlockTintTest {

    @Test
    void averagesOpaqueTexels() {
        assertEquals(0x804020FF, BlockTint.ofArgb(new int[]{0xFF804020, 0xFF804020}));
        assertEquals(0x7F7F7FFF, BlockTint.ofArgb(new int[]{0xFF000000, 0xFFFEFEFE}));
    }

    @Test
    void holesDoNotWashTheColourOut() {
        // Leaves: green texels with transparent gaps between them.
        int tint = BlockTint.ofArgb(new int[]{0xFF2A7A1E, 0x00000000, 0x10FFFFFF, 0xFF2A7A1E});
        assertEquals(0x2A7A1EFF, tint);
    }

    @Test
    void coverageWeightsPartlyTransparentTexels() {
        int tint = BlockTint.ofArgb(new int[]{0xFFFF0000, 0x550000FF});
        int r = tint >>> 24, b = (tint >>> 8) & 0xFF;
        assertEquals(191, r);
        assertEquals(63, b);
    }

    @Test
    void severalTexturesAverageTogether() {
        BufferedImage red = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        red.setRGB(0, 0, 0xFFFF0000);
        BufferedImage blue = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        blue.setRGB(0, 0, 0xFF0000FF);
        assertEquals(0x7F007FFF, BlockTint.ofAll(java.util.List.of(red, blue)));
    }

    @Test
    void nothingToSampleFallsBackToWhite() {
        assertEquals(BlockTint.FALLBACK, BlockTint.of((BufferedImage) null));
        assertEquals(BlockTint.FALLBACK, BlockTint.of(new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)));
    }
}
