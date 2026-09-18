package com.stonebreak.core.dev;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Dev-hook helper: dumps the GL back buffer to a PNG. Main thread, call before the buffer swap. */
public final class DevScreenshot {
    private DevScreenshot() {}

    /** @return true when the file was written */
    public static boolean capture(String file, int width, int height) {
        ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
        GL11.glReadBuffer(GL11.GL_BACK);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = ((height - 1 - y) * width + x) * 4;
                int r = pixels.get(i) & 0xFF, g = pixels.get(i + 1) & 0xFF, b = pixels.get(i + 2) & 0xFF;
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        try {
            File target = new File(file);
            File parent = target.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            return ImageIO.write(img, "png", target);
        } catch (IOException e) {
            System.err.println("[devscreenshot] failed: " + e.getMessage());
            return false;
        }
    }
}
