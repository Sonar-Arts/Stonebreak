package com.openmason.main.systems.mcp;

import com.openmason.main.systems.MainImGuiInterface;
import com.openmason.main.systems.menus.textureCreator.TextureCreatorController;
import com.openmason.main.systems.menus.textureCreator.TextureCreatorImGui;
import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.layers.LayerManager;
import com.openmason.main.systems.threading.MainThreadExecutor;

import java.awt.image.BufferedImage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Captures the texture editor canvas (the CPU layer composite, or a single
 * layer) as a PNG for the MCP server — the canvas counterpart of
 * {@link ViewportCaptureService}.
 *
 * <p>Reads the composited {@link PixelCanvas} directly, so no GL is involved,
 * but the hop to the main thread stays: the controller and {@link LayerManager}
 * are main-thread state. Canvas textures are tiny (typically 16-64 px), so the
 * image is nearest-neighbor UPSCALED by a whole factor toward {@code max_size}
 * — pixel art stays hard-edged and actually viewable.
 */
public final class CanvasCaptureService {

    private static final long DEFAULT_TIMEOUT_MS = 10_000;

    static final int DEFAULT_MAX_SIZE = 1024;
    private static final int MIN_SIZE = 64;
    private static final int MAX_SIZE = 2048;

    private final MainImGuiInterface mainInterface;

    public CanvasCaptureService(MainImGuiInterface mainInterface) {
        this.mainInterface = mainInterface;
    }

    /**
     * @param maxSize    longest-side target, clamped to [64, 2048]
     * @param layerIndex layer to capture, or {@code null} for the visible composite
     */
    public McpImageContent capture(int maxSize, Integer layerIndex) {
        int target = Math.clamp(maxSize, MIN_SIZE, MAX_SIZE);
        BufferedImage canvas = await(MainThreadExecutor.submit(() -> readCanvas(layerIndex)));
        BufferedImage scaled = McpImageCodec.upscaleNearest(canvas, target);
        // Oversized canvases (e.g. a large imported texture) still get capped.
        scaled = McpImageCodec.downscale(scaled, target);
        return new McpImageContent(McpImageCodec.encodePngBase64(scaled), "image/png");
    }

    private BufferedImage readCanvas(Integer layerIndex) {
        LayerManager lm = requireController().getLayerManager();
        PixelCanvas source;
        if (layerIndex == null) {
            source = lm.compositeLayersToCanvas();
        } else {
            if (layerIndex < 0 || layerIndex >= lm.getLayerCount()) {
                throw new IllegalArgumentException("Layer index " + layerIndex
                        + " out of range 0-" + (lm.getLayerCount() - 1));
            }
            source = lm.getLayer(layerIndex).getCanvas();
        }
        return toImage(source);
    }

    private static BufferedImage toImage(PixelCanvas canvas) {
        int w = canvas.getWidth();
        int h = canvas.getHeight();
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int[] rgba = PixelCanvas.unpackRGBA(canvas.getPixel(x, y));
                image.setRGB(x, y, (rgba[3] << 24) | (rgba[0] << 16) | (rgba[1] << 8) | rgba[2]);
            }
        }
        return image;
    }

    private TextureCreatorController requireController() {
        TextureCreatorImGui ui = mainInterface.getTextureCreator();
        if (ui == null) throw new IllegalStateException("Texture editor not initialized");
        TextureCreatorController c = ui.getController();
        if (c == null) throw new IllegalStateException("Texture editor controller not available");
        return c;
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("Canvas capture timed out on main thread", e);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        }
    }
}
