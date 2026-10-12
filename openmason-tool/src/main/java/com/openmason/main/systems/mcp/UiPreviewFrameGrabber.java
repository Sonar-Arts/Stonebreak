package com.openmason.main.systems.mcp;

import com.openmason.main.systems.uiEditor.automation.UiPreviewAutomation;
import com.openmason.main.systems.uiEditor.view.DesignerRuntime;
import com.openmason.main.systems.uiPreview.MasonryPreview;
import org.lwjgl.system.MemoryUtil;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.GL_PACK_ALIGNMENT;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_HEIGHT;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WIDTH;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glGetInteger;
import static org.lwjgl.opengl.GL11.glGetTexImage;
import static org.lwjgl.opengl.GL11.glGetTexLevelParameteri;
import static org.lwjgl.opengl.GL11.glPixelStorei;

/**
 * Reads the running preview's own frame back from the GPU (#324): the designer runtime paints
 * the live view at the requested size through {@link MasonryPreview} (the same path the canvas
 * draws) and the frame texture is copied out. Must run on the GL thread.
 *
 * <p>The frame texture is stored top row first (the canvas draws it with uv (0,0) at the top),
 * so rows are copied without flipping.
 */
final class UiPreviewFrameGrabber implements UiPreviewAutomation.FrameGrabber {

    @Override
    public BufferedImage grab(DesignerRuntime runtime, int width, int height, float uiScale, float pixelRatio) {
        MasonryPreview.Frame frame = runtime.paint(width, height, uiScale, pixelRatio, 0, true);
        if (frame == null || frame.texture() <= 0) {
            return null;
        }
        int previous = glGetInteger(GL_TEXTURE_BINDING_2D);
        int alignment = glGetInteger(GL_PACK_ALIGNMENT);
        ByteBuffer px = null;
        try {
            glBindTexture(GL_TEXTURE_2D, frame.texture());
            int tw = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH);
            int th = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT);
            if (tw < width || th < height) {
                return null;
            }
            px = MemoryUtil.memAlloc(Math.multiplyExact(Math.multiplyExact(tw, th), 4)); // freed below, not by GC
            glPixelStorei(GL_PACK_ALIGNMENT, 1);
            glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, px);
            int[] argb = new int[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int o = (y * tw + x) * 4;
                    argb[y * width + x] = (px.get(o + 3) & 0xFF) << 24 | (px.get(o) & 0xFF) << 16
                        | (px.get(o + 1) & 0xFF) << 8 | px.get(o + 2) & 0xFF;
                }
            }
            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            img.setRGB(0, 0, width, height, argb, 0, width);
            return img;
        } finally {
            if (px != null) {
                MemoryUtil.memFree(px);
            }
            glBindTexture(GL_TEXTURE_2D, previous);
            glPixelStorei(GL_PACK_ALIGNMENT, alignment);
        }
    }
}
