package com.openmason.main.systems.uiPreview;

import com.openmason.engine.ui.masonry.MasonryUI;
import com.openmason.engine.ui.rendering.GpuMasonryBackend;
import com.openmason.engine.ui.rendering.MasonryBackend;
import com.openmason.engine.ui.rendering.OffscreenFramebuffer;
import com.openmason.engine.ui.rendering.PreviewMapping;
import com.openmason.engine.ui.rendering.RasterMasonryBackend;
import com.openmason.engine.ui.rendering.RasterTextureUpload;
import com.openmason.engine.ui.rendering.UiRenderTarget;
import imgui.ImGui;
import io.github.humbleui.skija.Typeface;

import java.util.function.BiConsumer;

/**
 * Shows a Stonebreak Masonry frame inside an ImGui window: the same widget drawing code as the
 * game, painted offscreen and composited as an image item. Two paths, chosen per preview:
 * <ul>
 *   <li>{@link Path#GPU}: Skia on the GPU into an {@link OffscreenFramebuffer} (pixel-identical to
 *       the game window; GL state is captured and restored around the paint).</li>
 *   <li>{@link Path#RASTER}: Skia's CPU rasterizer, uploaded with {@code glTexSubImage2D}; the
 *       path that does not flicker in popped-out windows on Mesa/XWayland.</li>
 * </ul>
 * The painter first fills an opaque backdrop, so ImGui's straight-alpha blend shows the
 * premultiplied frame without darkening translucent edges. Pointer input is converted with
 * {@link #mapping()}, separately from drawing. GL thread only; {@link #close()} releases every
 * GL and Skia object the preview created.
 */
public final class MasonryPreview implements AutoCloseable {

    public enum Path { GPU, RASTER }

    private static int nextId;

    /**
     * One Skia GPU context for every preview that shares it (the UI editor opens a runtime per
     * document): one resource cache and glyph atlas however many documents are open, instead of a
     * {@code DirectContext} each. Each preview keeps its own framebuffer and points the shared
     * backend at it for its paint. GL thread only, like everything here.
     */
    private static final class SharedGpu {
        final GpuMasonryBackend backend;
        final MasonryUI ui;
        int users;
        /** The framebuffer the backend's surface wraps now (the last preview that painted). */
        OffscreenFramebuffer surfaceOf;

        SharedGpu(Typeface typeface) {
            backend = new SharedTypefaceGpuBackend(typeface);
            backend.initialize(UiRenderTarget.offscreen(0, 1, 1, 1f));
            ui = new MasonryUI(backend);
        }
    }

    private static final java.util.Map<Typeface, SharedGpu> SHARED = new java.util.IdentityHashMap<>();

    private final Typeface typeface;
    private final boolean shareContext;
    private final String buttonId = "##masonry_preview_" + (++nextId);
    private Path path;
    private SharedGpu shared;
    private GpuMasonryBackend gpu;
    private OffscreenFramebuffer framebuffer;
    private RasterMasonryBackend raster;
    private RasterTextureUpload upload;
    private MasonryUI ui;
    private PreviewMapping mapping = new PreviewMapping(0, 0, 1);

    /** @param typeface shared typeface, owned by the caller and outliving the preview */
    public MasonryPreview(Typeface typeface, Path path) {
        this(typeface, path, false);
    }

    /**
     * @param shareContext on the GPU path, paint through one Skia context shared by every preview
     *                     created with the same typeface and this flag (many previews open at once)
     */
    public MasonryPreview(Typeface typeface, Path path, boolean shareContext) {
        this.typeface = typeface;
        this.path = path;
        this.shareContext = shareContext;
    }

    /** Previews that currently share a GPU context (tests, diagnostics). */
    public static int sharedContexts() {
        return SHARED.size();
    }

    public Path path() {
        return path;
    }

    /** Switches paths, releasing the old path's resources. */
    public void setPath(Path newPath) {
        if (newPath != path) {
            releaseBackend();
            path = newPath;
        }
    }

    /**
     * Paints a {@code canvasW x canvasH} frame and shows it at {@code zoom} at the cursor,
     * overlaid with an invisible button so ImGui item queries ({@code isItemHovered}) work.
     *
     * @param painter draws one frame: (ui, canvas size), canvas units are frame pixels
     */
    public void draw(int canvasW, int canvasH, float zoom, BiConsumer<MasonryUI, int[]> painter) {
        if (canvasW < 1 || canvasH < 1 || zoom <= 0f) {
            return;
        }
        Frame f = paint(canvasW, canvasH, painter);
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        mapping = new PreviewMapping(x, y, zoom);
        ImGui.image(f.texture(), canvasW * zoom, canvasH * zoom, 0f, 0f, f.u1(), f.v1());
        ImGui.setCursorScreenPos(x, y);
        ImGui.invisibleButton(buttonId, canvasW * zoom, canvasH * zoom);
    }

    /** A painted frame: its GL texture and the texture coordinates of the frame's far corner. */
    public record Frame(int texture, float u1, float v1) {
    }

    /**
     * Paints a {@code canvasW x canvasH} frame without placing anything in the ImGui layout: the
     * caller draws the texture where it likes (the UI editor's pan/zoom canvas) and supplies the
     * input mapping with {@link #setMapping}.
     */
    public Frame paint(int canvasW, int canvasH, BiConsumer<MasonryUI, int[]> painter) {
        ensureBackend();
        int[] size = {canvasW, canvasH};
        if (path == Path.GPU) {
            boolean resized = framebuffer.ensureSize(canvasW, canvasH);
            if (resized || shared != null && shared.surfaceOf != framebuffer) {
                gpu.releaseTargetSurface(); // a shared backend last painted some other preview's framebuffer
            }
            if (shared != null) {
                shared.surfaceOf = framebuffer;
            }
            gpu.setTarget(framebuffer.target(1f));
            paint(painter, size, framebuffer.allocatedWidth(), framebuffer.allocatedHeight());
            return new Frame(framebuffer.textureId(), framebuffer.uvMaxX(), framebuffer.uvMaxY());
        }
        paint(painter, size, canvasW, canvasH);
        upload.upload(raster);
        return new Frame(upload.textureId(), upload.uvMaxX(), upload.uvMaxY());
    }

    /** Overrides the screen mapping (callers that place the frame themselves). */
    public void setMapping(PreviewMapping m) {
        mapping = m;
    }

    /**
     * Writes the last raster frame ({@link Path#RASTER}) to a PNG: the document exactly as the
     * preview painted it (dev screenshot hook). @return false on the GPU path or before a frame.
     */
    public boolean saveRasterPng(java.nio.file.Path file, int width, int height) throws java.io.IOException {
        if (raster == null || width < 1 || height < 1) {
            return false;
        }
        java.awt.image.BufferedImage img =
            new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                img.setRGB(x, y, raster.colorAt(x, y));
            }
        }
        return javax.imageio.ImageIO.write(img, "png", file.toFile());
    }

    /** Screen ↔ canvas mapping of the last {@link #draw}. */
    public PreviewMapping mapping() {
        return mapping;
    }

    @Override
    public void close() {
        releaseBackend();
    }

    private void paint(BiConsumer<MasonryUI, int[]> painter, int[] size, int frameW, int frameH) {
        if (raster != null && !raster.isAvailable()) {
            // A raster backend has no surface until a frame sizes it, and MasonryUI refuses to
            // begin a frame on an unavailable backend: allocate it first, or nothing ever paints.
            raster.beginFrame(frameW, frameH, 1f);
            raster.endFrame();
        }
        if (!ui.beginFrame(frameW, frameH, 1f)) {
            return;
        }
        try {
            painter.accept(ui, size);
        } finally {
            ui.endFrame();
        }
    }

    private void ensureBackend() {
        if (ui != null) {
            return;
        }
        MasonryBackend backend;
        if (path == Path.GPU && shareContext) {
            shared = SHARED.computeIfAbsent(typeface, SharedGpu::new);
            shared.users++;
            gpu = shared.backend;
            framebuffer = new OffscreenFramebuffer();
            ui = shared.ui;
            return;
        }
        if (path == Path.GPU) {
            gpu = new SharedTypefaceGpuBackend(typeface);
            gpu.initialize(UiRenderTarget.offscreen(0, 1, 1, 1f));
            framebuffer = new OffscreenFramebuffer();
            backend = gpu;
        } else {
            raster = new RasterMasonryBackend(typeface, false);
            upload = new RasterTextureUpload();
            backend = raster;
        }
        ui = new MasonryUI(backend);
    }

    private void releaseBackend() {
        if (shared != null) {
            if (shared.surfaceOf == framebuffer) {
                shared.backend.releaseTargetSurface(); // it wraps this preview's framebuffer
                shared.surfaceOf = null;
            }
            if (--shared.users == 0) {
                SHARED.remove(typeface);
                shared.ui.dispose();
                shared.backend.dispose();
            }
            shared = null;
            gpu = null;
            ui = null;
        }
        if (ui != null) {
            ui.dispose();
            ui = null;
        }
        if (gpu != null) {
            gpu.releaseTargetSurface();
            gpu.dispose();
            gpu = null;
        }
        if (framebuffer != null) {
            framebuffer.close();
            framebuffer = null;
        }
        if (raster != null) {
            raster.dispose();
            raster = null;
        }
        if (upload != null) {
            upload.close();
            upload = null;
        }
    }

    /** GPU backend that borrows the preview's typeface instead of owning one. */
    private static final class SharedTypefaceGpuBackend extends GpuMasonryBackend {
        private final Typeface shared;

        SharedTypefaceGpuBackend(Typeface shared) {
            this.shared = shared;
        }

        @Override
        public Typeface typeface() {
            return shared;
        }
    }
}
