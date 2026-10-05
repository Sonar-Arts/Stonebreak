package com.openmason.engine.ui.runtime.paint;

import com.openmason.engine.ui.masonry.textures.MTexture;
import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;

/** Procedural textures for paint tests. Not a test class. */
final class UiPaintFixtures {

    private UiPaintFixtures() {
    }

    /** 4x4 black/white checker, one texel per square, white at (0, 0). */
    static MTexture checker() {
        Bitmap bitmap = new Bitmap();
        bitmap.allocPixels(ImageInfo.makeN32Premul(4, 4));
        byte[] px = new byte[4 * 4 * 4];
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                byte v = (byte) (((x + y) & 1) == 0 ? 0xFF : 0x00);
                int i = (y * 4 + x) * 4;
                px[i] = v;
                px[i + 1] = v;
                px[i + 2] = v;
                px[i + 3] = (byte) 0xFF;
            }
        }
        bitmap.installPixels(px);
        bitmap.setImmutable();
        Image image = Image.makeRasterFromBitmap(bitmap);
        bitmap.close();
        return MTexture.fromImage("checker", image);
    }
}
