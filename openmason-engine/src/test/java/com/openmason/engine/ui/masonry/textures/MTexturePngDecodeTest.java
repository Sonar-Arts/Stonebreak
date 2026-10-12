package com.openmason.engine.ui.masonry.textures;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.EncodedImageFormat;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.types.Rect;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A plain PNG texture decodes exactly as the game's own {@code Image.makeFromEncoded} (#299): a
 * document image of the menu logo draws the same pixels as the legacy screen's drawImageRect,
 * translucent edges included (a straight-alpha decode rounded those one level apart).
 */
class MTexturePngDecodeTest {

    @Test
    void aPngTextureDrawsLikeTheEncodedImage() {
        byte[] png = translucentPng();
        Image legacy = Image.makeFromEncoded(png);
        MTexture texture = MTexture.decode("test.png", png);
        assertNotNull(texture);
        assertArrayEquals(draw(legacy), draw(texture.image()));
    }

    /** 7x5 pixels of every alpha level band, so premultiply rounding shows. */
    private static byte[] translucentPng() {
        try (Bitmap b = new Bitmap()) {
            b.allocPixels(ImageInfo.makeN32Premul(7, 5));
            try (Canvas c = new Canvas(b)) {
                for (int y = 0; y < 5; y++) {
                    for (int x = 0; x < 7; x++) {
                        int a = (x * 37 + y * 11) % 256;
                        c.drawRect(Rect.makeXYWH(x, y, 1, 1), new io.github.humbleui.skija.Paint()
                            .setColor((a << 24) | (0x9A << 16) | ((x * 40) << 8) | (y * 50)).setBlendMode(
                                io.github.humbleui.skija.BlendMode.SRC));
                    }
                }
            }
            try (Image img = Image.makeRasterFromBitmap(b)) {
                return img.encodeToData(EncodedImageFormat.PNG).getBytes();
            }
        }
    }

    private static byte[] draw(Image img) {
        try (Bitmap b = new Bitmap()) {
            b.allocPixels(ImageInfo.makeN32Premul(40, 30));
            try (Canvas c = new Canvas(b)) {
                c.clear(0xFF203040);
                c.drawImageRect(img, Rect.makeWH(img.getWidth(), img.getHeight()), Rect.makeXYWH(1.37f, 2.5f, 31.3f, 21.7f),
                    SamplingMode.DEFAULT, null, true);
            }
            return b.readPixels();
        }
    }
}
