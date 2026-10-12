package com.openmason.main.systems.uiEditor.view;

import com.openmason.engine.ui.masonry.textures.MTexture;
import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.ImageInfo;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;

/**
 * A sprite sheet's texture as a GL texture for the Sprites panel (#294): the same composited
 * image the runtime draws ({@link MTexture}, the engine's layer rule), uploaded once per content
 * hash with nearest filtering so texels stay crisp at any zoom. Owned by the panel; closed with it.
 */
final class SheetTexture implements AutoCloseable {

    private int id;
    private String key;
    private int width;
    private int height;

    /** Shows {@code texture} (borrowed); re-uploads only when its content key changes. */
    void show(MTexture texture) {
        if (texture == null || texture.image() == null) {
            return;
        }
        if (texture.resourcePath().equals(key) && id != 0) {
            return;
        }
        int w = texture.width();
        int h = texture.height();
        byte[] rgba;
        try (Bitmap bm = new Bitmap()) {
            bm.allocPixels(new ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL));
            if (!texture.image().readPixels(bm)) {
                return;
            }
            rgba = bm.readPixels();
        }
        ByteBuffer buf = BufferUtils.createByteBuffer(rgba.length).put(rgba).flip();
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int alignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
        if (id == 0) {
            id = GL11.glGenTextures();
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, alignment);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
        key = texture.resourcePath();
        width = w;
        height = h;
    }

    int id() {
        return id;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    @Override
    public void close() {
        if (id != 0) {
            GL11.glDeleteTextures(id);
            id = 0;
            key = null;
        }
    }
}
