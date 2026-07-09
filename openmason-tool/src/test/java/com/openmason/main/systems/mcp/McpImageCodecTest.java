package com.openmason.main.systems.mcp;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class McpImageCodecTest {

    private static final int BLACK = 0xFF000000;
    private static final int WHITE = 0xFFFFFFFF;

    /** A checkerboard where (x+y) parity picks the color. */
    private static BufferedImage checker(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, (x + y) % 2 == 0 ? BLACK : WHITE);
            }
        }
        return img;
    }

    // ===================== upscaleNearest =====================

    @Test
    void upscaleReplicatesPixelsExactlyWithHardEdges() {
        BufferedImage out = McpImageCodec.upscaleNearest(checker(16, 16), 1024);
        assertEquals(1024, out.getWidth());
        assertEquals(1024, out.getHeight());
        int factor = 64;

        // Every output pixel is an exact copy of its source cell — no blending.
        assertEquals(BLACK, out.getRGB(0, 0));
        assertEquals(BLACK, out.getRGB(factor - 1, factor - 1));
        assertEquals(WHITE, out.getRGB(factor, 0));
        assertEquals(WHITE, out.getRGB(2 * factor - 1, factor - 1));
        // Hard edge between adjacent cells.
        assertNotEquals(out.getRGB(factor - 1, 0), out.getRGB(factor, 0));
        // Last cell maps to source (15,15): (15+15) even -> black.
        assertEquals(BLACK, out.getRGB(1023, 1023));
        // Mid-cell spot check: (10,3) odd -> white block.
        assertEquals(WHITE, out.getRGB(10 * factor + 17, 3 * factor + 40));
    }

    @Test
    void upscaleReturnsSourceWhenTargetIsNotLarger() {
        BufferedImage img = checker(16, 16);
        assertSame(img, McpImageCodec.upscaleNearest(img, 8), "smaller target: factor 1");
        assertSame(img, McpImageCodec.upscaleNearest(img, 16), "equal target: factor 1");
        // 31 / 16 = 1 -> still the source.
        assertSame(img, McpImageCodec.upscaleNearest(img, 31));
    }

    @Test
    void upscaleUsesFloorFactorOfTheLongestSide() {
        // 20x8, target 64: factor = 64 / 20 = 3 (floor), never 64 / 8.
        BufferedImage out = McpImageCodec.upscaleNearest(checker(20, 8), 64);
        assertEquals(60, out.getWidth());
        assertEquals(24, out.getHeight());
        assertEquals(BLACK, out.getRGB(0, 0));
        assertEquals(WHITE, out.getRGB(3, 0));
    }

    @Test
    void upscalePreservesTransparency() {
        BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, 0x00000000); // fully transparent
        img.setRGB(1, 1, 0x80FF0000); // half-alpha red
        BufferedImage out = McpImageCodec.upscaleNearest(img, 4);
        assertEquals(BufferedImage.TYPE_INT_ARGB, out.getType());
        assertEquals(0x00000000, out.getRGB(0, 0));
        assertEquals(0x80FF0000, out.getRGB(3, 3));
    }

    // ===================== downscale =====================

    @Test
    void downscaleIsANoOpWhenAlreadyWithinTarget() {
        BufferedImage img = checker(16, 16);
        assertSame(img, McpImageCodec.downscale(img, 512));
        assertSame(img, McpImageCodec.downscale(img, 16));
    }

    @Test
    void downscaleShrinksLongestSideToTargetPreservingAspect() {
        BufferedImage img = new BufferedImage(256, 128, BufferedImage.TYPE_INT_RGB);
        BufferedImage out = McpImageCodec.downscale(img, 64);
        assertEquals(64, out.getWidth());
        assertEquals(32, out.getHeight());
    }

    // ===================== encodePngBase64 =====================

    @Test
    void base64PngRoundTripsThroughImageIO() throws Exception {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                img.setRGB(x, y, (0xFF << 24) | (x * 60 << 16) | (y * 60 << 8) | (x + y));
            }
        }
        img.setRGB(0, 0, 0x00000000); // transparency must survive PNG

        String base64 = McpImageCodec.encodePngBase64(img);
        BufferedImage decoded = ImageIO.read(
                new ByteArrayInputStream(Base64.getDecoder().decode(base64)));
        assertNotNull(decoded);
        assertEquals(4, decoded.getWidth());
        assertEquals(4, decoded.getHeight());
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                assertEquals(img.getRGB(x, y), decoded.getRGB(x, y),
                        "pixel (" + x + "," + y + ") must survive the PNG round-trip");
            }
        }
    }
}
