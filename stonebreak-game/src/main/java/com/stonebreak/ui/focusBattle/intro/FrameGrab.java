package com.stonebreak.ui.focusBattle.intro;

import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;

/**
 * Reads the GL back buffer into a Skija raster {@link Image} (top-down rows). One synchronous
 * readback, paid once at the freeze-frame moment of an encounter, where a hitch is invisible.
 * Main thread, outside any open Skija frame, before the buffer swap.
 */
public final class FrameGrab {
    private FrameGrab() {}

    /** @return the frame, or null when the size is degenerate or the readback failed */
    public static Image backBuffer(int width, int height) {
        if (width <= 0 || height <= 0) {
            return null;
        }
        try {
            int stride = width * 4;
            ByteBuffer pixels = BufferUtils.createByteBuffer(stride * height);
            GL11.glReadBuffer(GL11.GL_BACK);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            byte[] topDown = new byte[stride * height];
            for (int y = 0; y < height; y++) {
                pixels.position((height - 1 - y) * stride);
                pixels.get(topDown, y * stride, stride);
            }
            for (int i = 3; i < topDown.length; i += 4) {
                topDown[i] = (byte) 0xFF; // the back buffer's alpha is not meaningful
            }
            ImageInfo info = new ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.OPAQUE);
            return Image.makeRasterFromBytes(info, topDown, stride);
        } catch (RuntimeException e) {
            System.err.println("[battle] encounter frame grab failed: " + e);
            return null;
        }
    }
}
