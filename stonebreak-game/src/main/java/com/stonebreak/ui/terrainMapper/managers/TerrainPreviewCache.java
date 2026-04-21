package com.stonebreak.ui.terrainMapper.managers;

import com.stonebreak.ui.terrainMapper.components.TerrainMapViewport;
import com.stonebreak.ui.terrainMapper.visualization.NoiseVisualizer;
import com.stonebreak.ui.terrainMapper.visualization.VisualizerKind;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;

import java.util.stream.IntStream;

/**
 * Sample-and-pack cache for the terrain preview. Holds a single
 * {@link Image} at a sample resolution derived from the caller-supplied
 * step; the map renderer scales it up with nearest-neighbor filtering.
 *
 * Perf notes:
 * <ul>
 *   <li>Row sampling runs in parallel via the common ForkJoin pool — every
 *       row writes to a disjoint slice of the int buffer, so no locking is
 *       needed. Visualizers/noise samplers are read-only after
 *       {@code VisualizerRegistry.rebuild(seed)}.</li>
 *   <li>The int pixel buffer is reused across rebuilds; a realloc happens
 *       only when the sample grid size grows.</li>
 *   <li>The cache key includes the sample step so switching between
 *       hi-res (idle) and lo-res (drag/zoom) triggers a proper rebuild.</li>
 * </ul>
 */
public final class TerrainPreviewCache {

    private Image image;
    private int[] pixelBuffer;

    private int cachedWidthPx;
    private int cachedHeightPx;
    private int cachedStep;
    private VisualizerKind cachedKind;
    private long cachedSeed;
    private int cachedViewportStamp;

    public Image image() { return image; }

    /**
     * Ensure the cached image matches the current viewport, visualizer, and
     * requested sample step, rebuilding only if any input changed.
     */
    public void ensure(int widthPx, int heightPx, int sampleStep,
                       VisualizerKind kind, long seed,
                       NoiseVisualizer visualizer,
                       TerrainMapViewport viewport) {
        if (widthPx <= 0 || heightPx <= 0 || visualizer == null) return;
        int step = Math.max(1, sampleStep);
        boolean changed = image == null
                || cachedWidthPx != widthPx
                || cachedHeightPx != heightPx
                || cachedStep != step
                || cachedKind != kind
                || cachedSeed != seed
                || cachedViewportStamp != viewport.stamp();
        if (!changed) return;
        rebuild(widthPx, heightPx, step, kind, seed, visualizer, viewport);
    }

    public void dispose() {
        if (image != null) { image.close(); image = null; }
        pixelBuffer = null;
    }

    private void rebuild(int widthPx, int heightPx, int step,
                         VisualizerKind kind, long seed,
                         NoiseVisualizer visualizer,
                         TerrainMapViewport viewport) {
        int sampleW = Math.max(1, widthPx / step);
        int sampleH = Math.max(1, heightPx / step);
        int pixelCount = sampleW * sampleH;
        if (pixelBuffer == null || pixelBuffer.length < pixelCount) {
            pixelBuffer = new int[pixelCount];
        }
        samplePixels(pixelBuffer, sampleW, sampleH, step, widthPx, heightPx, visualizer, viewport);

        if (image != null) { image.close(); image = null; }
        image = buildImage(pixelBuffer, pixelCount, sampleW, sampleH);

        cachedWidthPx = widthPx;
        cachedHeightPx = heightPx;
        cachedStep = step;
        cachedKind = kind;
        cachedSeed = seed;
        cachedViewportStamp = viewport.stamp();
    }

    private static void samplePixels(int[] pixels, int sampleW, int sampleH, int step,
                                     int widthPx, int heightPx,
                                     NoiseVisualizer visualizer,
                                     TerrainMapViewport viewport) {
        float centerX = widthPx * 0.5f;
        float centerZ = heightPx * 0.5f;
        float halfStep = step * 0.5f;
        // Rows are independent; the common ForkJoin pool sizes itself to the
        // CPU so we get parallel speedup without configuring threads here.
        IntStream.range(0, sampleH).parallel().forEach(sy -> {
            float screenZ = sy * step + halfStep;
            int worldZ = Math.round(viewport.screenToWorldZ(screenZ, centerZ));
            int rowOffset = sy * sampleW;
            for (int sx = 0; sx < sampleW; sx++) {
                float screenX = sx * step + halfStep;
                int worldX = Math.round(viewport.screenToWorldX(screenX, centerX));
                float raw = visualizer.sample(worldX, worldZ);
                pixels[rowOffset + sx] = visualizer.colorFor(visualizer.normalize(raw));
            }
        });
    }

    private static Image buildImage(int[] argbPixels, int pixelCount, int width, int height) {
        ImageInfo info = ImageInfo.makeN32(width, height, ColorAlphaType.OPAQUE);
        int rowBytes = width * 4;
        // Skija's makeRasterFromBytes wants a tightly-sized array, so we can't
        // reuse this across rebuilds. N32 is BGRA in native byte order;
        // visualizers always produce opaque pixels so OPAQUE alpha type skips
        // premul conversion inside Skija.
        byte[] bytes = new byte[height * rowBytes];
        for (int i = 0, off = 0; i < pixelCount; i++, off += 4) {
            int argb = argbPixels[i];
            bytes[off]     = (byte) (argb & 0xFF);          // B
            bytes[off + 1] = (byte) ((argb >>> 8) & 0xFF);  // G
            bytes[off + 2] = (byte) ((argb >>> 16) & 0xFF); // R
            bytes[off + 3] = (byte) 0xFF;                   // A (opaque)
        }
        return Image.makeRasterFromBytes(info, bytes, rowBytes);
    }
}
