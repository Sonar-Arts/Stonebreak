package com.openmason.engine.ui.rendering;

import io.github.humbleui.skija.BackendTexture;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ColorSpace;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.DirectContext;
import io.github.humbleui.skija.GLTextureInfo;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.SurfaceOrigin;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Skia images over GL textures a host rendered itself (item icons, 3D previews): the bridge
 * that lets a draw provider do its GL work in {@code prepare} and paint the result through the
 * document's canvas, so it follows the document's transforms, clips, opacity and paint order.
 *
 * <p><b>GPU frames.</b> {@link SkiaGlRenderer} announces the {@link DirectContext} of the frame
 * it is painting ({@link #beginGpuFrame}); {@link #borrow} wraps the texture without copying.
 * The texture stays the caller's: Skia never deletes it.
 *
 * <p><b>Raster frames.</b> Without a GPU frame on this thread, the texture is read back with
 * {@code glGetTexImage} into a CPU image (slow: tests and the raster editor preview only). A GL
 * context must be current; without one, {@code borrow} returns null.
 *
 * <p><b>Lifetime.</b> Images are valid for the frame in progress only and are cached per
 * (texture, size, origin) within it, so twenty slots showing one atlas wrap it once. They are
 * closed when the outermost frame ends (after Skia has flushed). All calls belong on the GL
 * thread.
 */
public final class GlTextureImages {

    private static final int GL_RGBA8 = 0x8058;
    /** The texture is the caller's: nothing to release when Skia drops its wrapper. */
    private static final Runnable NO_RELEASE = () -> { };

    private record Key(int texture, int width, int height, boolean bottomLeft) {
    }

    private static final class FrameState {
        DirectContext context;
        final Map<Key, Image> images = new HashMap<>();
        final List<Image> retired = new ArrayList<>();
    }

    private static final ThreadLocal<FrameState> STATE = ThreadLocal.withInitial(FrameState::new);

    private GlTextureImages() {
    }

    /**
     * A Skia image showing {@code glTexture} (RGBA8, {@code width x height}) for the frame being
     * painted on this thread, or null when no GL context is current. {@code bottomLeftOrigin}:
     * the texture's first row is its bottom row (anything rendered through a GL framebuffer).
     *
     * @param canvas the canvas the caller paints on; only used to document intent today (the frame
     *               is per thread), kept so a future multi-surface host can select the context
     */
    public static Image borrow(Canvas canvas, int glTexture, int width, int height, boolean bottomLeftOrigin) {
        if (glTexture == 0 || width <= 0 || height <= 0) {
            return null;
        }
        FrameState state = STATE.get();
        Key key = new Key(glTexture, width, height, bottomLeftOrigin);
        Image cached = state.images.get(key);
        if (cached != null && !cached.isClosed()) {
            return cached;
        }
        Image image = state.context != null
                ? wrap(state.context, key)
                : readBack(key);
        if (image != null) {
            state.images.put(key, image);
        }
        return image;
    }

    /** True while a GPU frame is open on this thread (diagnostics and tests). */
    public static boolean inGpuFrame() {
        return STATE.get().context != null;
    }

    /** Called by {@link SkiaGlRenderer#begin}: images now wrap textures through {@code context}. */
    static void beginGpuFrame(DirectContext context) {
        FrameState state = STATE.get();
        retireAll(state);
        state.context = context;
    }

    /** Called by {@link SkiaGlRenderer#end} after Skia flushed: the frame's images are closed. */
    static void endGpuFrame() {
        FrameState state = STATE.get();
        state.context = null;
        retireAll(state);
        closeRetired(state);
    }

    /** Called when an outermost raster frame ends: read-back images are closed. */
    static void endRasterFrame() {
        FrameState state = STATE.get();
        if (state.context != null) {
            return;
        }
        retireAll(state);
        closeRetired(state);
    }

    /** Number of live cached images on this thread (tests). */
    static int cachedImages() {
        return STATE.get().images.size();
    }

    private static Image wrap(DirectContext context, Key key) {
        BackendTexture backend = BackendTexture.makeGL(key.width(), key.height(), false,
                new GLTextureInfo(GL11.GL_TEXTURE_2D, key.texture(), GL_RGBA8));
        try {
            return Image.borrowTextureFrom(context, backend,
                    key.bottomLeft() ? SurfaceOrigin.BOTTOM_LEFT : SurfaceOrigin.TOP_LEFT,
                    ColorType.RGBA_8888, ColorAlphaType.PREMUL, ColorSpace.getSRGB(), NO_RELEASE);
        } finally {
            backend.close();
        }
    }

    private static Image readBack(Key key) {
        try {
            GL.getCapabilities();
        } catch (IllegalStateException noContext) {
            return null;
        }
        int w = key.width();
        int h = key.height();
        byte[] pixels = new byte[w * h * 4];
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int prevPack = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int prevAlign = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        int prevRowLength = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
        int prevSkipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
        int prevSkipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
        java.nio.ByteBuffer buffer = org.lwjgl.system.MemoryUtil.memAlloc(pixels.length);
        try {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, key.texture());
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
            buffer.get(0, pixels);
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, prevPack);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, prevAlign);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, prevRowLength);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, prevSkipRows);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, prevSkipPixels);
            org.lwjgl.system.MemoryUtil.memFree(buffer);
        }
        if (key.bottomLeft()) {
            flipRows(pixels, w, h);
        }
        return Image.makeRasterFromBytes(new ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.PREMUL),
                pixels, w * 4L);
    }

    private static void flipRows(byte[] pixels, int w, int h) {
        int stride = w * 4;
        byte[] row = new byte[stride];
        for (int top = 0, bottom = h - 1; top < bottom; top++, bottom--) {
            System.arraycopy(pixels, top * stride, row, 0, stride);
            System.arraycopy(pixels, bottom * stride, pixels, top * stride, stride);
            System.arraycopy(row, 0, pixels, bottom * stride, stride);
        }
    }

    private static void retireAll(FrameState state) {
        state.retired.addAll(state.images.values());
        state.images.clear();
    }

    private static void closeRetired(FrameState state) {
        for (Image image : state.retired) {
            if (!image.isClosed()) {
                image.close();
            }
        }
        state.retired.clear();
    }
}
