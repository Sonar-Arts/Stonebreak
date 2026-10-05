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

    private final Typeface typeface;
    private final String buttonId = "##masonry_preview_" + (++nextId);
    private Path path;
    private GpuMasonryBackend gpu;
    private OffscreenFramebuffer framebuffer;
    private RasterMasonryBackend raster;
    private RasterTextureUpload upload;
    private MasonryUI ui;
    private PreviewMapping mapping = new PreviewMapping(0, 0, 1);

    /** @param typeface shared typeface, owned by the caller and outliving the preview */
    public MasonryPreview(Typeface typeface, Path path) {
        this.typeface = typeface;
        this.path = path;
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
        ensureBackend();
        int[] size = {canvasW, canvasH};
        int texture;
        float u1;
        float v1;
        if (path == Path.GPU) {
            if (framebuffer.ensureSize(canvasW, canvasH)) {
                gpu.releaseTargetSurface();
            }
            gpu.setTarget(framebuffer.target(1f));
            paint(painter, size, framebuffer.allocatedWidth(), framebuffer.allocatedHeight());
            texture = framebuffer.textureId();
            u1 = framebuffer.uvMaxX();
            v1 = framebuffer.uvMaxY();
        } else {
            paint(painter, size, canvasW, canvasH);
            upload.upload(raster);
            texture = upload.textureId();
            u1 = upload.uvMaxX();
            v1 = upload.uvMaxY();
        }
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        mapping = new PreviewMapping(x, y, zoom);
        ImGui.image(texture, canvasW * zoom, canvasH * zoom, 0f, 0f, u1, v1);
        ImGui.setCursorScreenPos(x, y);
        ImGui.invisibleButton(buttonId, canvasW * zoom, canvasH * zoom);
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
