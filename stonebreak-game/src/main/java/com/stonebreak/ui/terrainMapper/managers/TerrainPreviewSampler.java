package com.stonebreak.ui.terrainMapper.managers;

import com.stonebreak.ui.terrainMapper.visualization.NoiseVisualizer;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;

import java.util.stream.IntStream;

/**
 * Turns a {@link SampleRequest} into a {@link PreviewSnapshot}. Pure production of pixels —
 * it owns no threading, no phase, and no notion of what is currently on screen; the caller
 * decides when and on which thread a sample happens, and hears back through a {@link SamplingSink}.
 *
 * <p>This is the slow part: every sample can reach through to the terrain bridge, so a call
 * may block for many seconds. {@link TerrainPreviewLoader} is the only caller and always
 * invokes it on its worker thread.
 *
 * <p>The grid is sampled in row bands rather than in one shot. That buys two things a single
 * pass cannot: the sink sees finished rows as they land (a zoomed-out view is thousands of
 * diffusion tiles on a single-GPU queue — tens of minutes — and would otherwise show nothing
 * for all of it), and {@link SamplingSink#abandoned()} gets polled often enough that a newer
 * request preempts this one instead of queueing behind it.
 *
 * Perf notes:
 * <ul>
 *   <li>Row sampling runs in parallel via the common ForkJoin pool — every row writes to a
 *       disjoint slice of the buffers, so no locking is needed. Visualizers/noise samplers
 *       are read-only after {@code VisualizerRegistry.rebuild(seed)}.</li>
 *   <li>The int pixel buffer is reused across calls; a realloc happens only when the sample
 *       grid grows. The float raw buffer is <em>not</em> reused — it is handed to the
 *       published snapshot and read by the render thread, so recycling it would mutate a
 *       snapshot somebody is still looking at.</li>
 * </ul>
 */
public final class TerrainPreviewSampler {

    /**
     * How many slices a pass is published in. Small enough that the first pixels show up early,
     * large enough that rebuilding the image per band stays noise next to the tile fetches.
     */
    private static final int BANDS = 16;

    /** Samples between {@link SamplingSink#abandoned()} polls inside a row. */
    private static final int ABANDON_POLL_INTERVAL = 32;

    private int[] pixelBuffer;

    /**
     * Samples the whole grid band by band, colors it, lets the visualizer overlay any
     * neighbour-dependent detail, and packs each finished prefix into a raster image.
     *
     * @param sink notified of every finished band, and polled for abandonment throughout
     * @return the finished snapshot, or null if {@code sink} abandoned the work part-way — in
     *         which case the rows already handed to {@link SamplingSink#partial} stand as the
     *         last word on this request
     * @throws com.stonebreak.world.generation.diffusion.TerrainBridgeException if the terrain
     *         bridge fails mid-sample; the caller decides how to recover.
     */
    public PreviewSnapshot sample(SampleRequest request, SamplingSink sink) {
        int sampleW = Math.max(1, request.widthPx() / request.step());
        int sampleH = Math.max(1, request.heightPx() / request.step());
        int pixelCount = sampleW * sampleH;

        if (pixelBuffer == null || pixelBuffer.length < pixelCount) {
            pixelBuffer = new int[pixelCount];
        }
        // One array for the whole pass, shared by every partial: a band only ever writes rows
        // past the last one published, so a snapshot already on screen is never mutated.
        float[] raw = new float[pixelCount];

        NoiseVisualizer visualizer = request.visualizer();
        int bandRows = Math.max(1, sampleH / BANDS);

        for (int bandStart = 0; bandStart < sampleH; bandStart += bandRows) {
            if (sink.abandoned()) return null;
            int rowsDone = Math.min(sampleH, bandStart + bandRows);
            samplePixels(request, visualizer, raw, pixelBuffer, sampleW, bandStart, rowsDone, sink);
            if (sink.abandoned()) return null;
            if (rowsDone == sampleH) break;
            sink.partial(buildSnapshot(request, visualizer, raw, sampleW, rowsDone, false));
        }
        return buildSnapshot(request, visualizer, raw, sampleW, sampleH, true);
    }

    /**
     * Colors and packs the first {@code rowsDone} rows. The post-process runs over the whole
     * prefix rather than just the new band because a contour line is a property of the boundary
     * between two samples — the band's first row can only be resolved against the last row of
     * the band before it. Re-running it is safe: {@code postProcess} reads only {@code raw},
     * never the pixels it writes, so painting the same lines twice is idempotent.
     */
    private PreviewSnapshot buildSnapshot(SampleRequest request, NoiseVisualizer visualizer,
                                          float[] raw, int sampleW, int rowsDone, boolean complete) {
        visualizer.postProcess(raw, pixelBuffer, sampleW, rowsDone, request.blocksPerSample());
        Image image = buildImage(pixelBuffer, sampleW * rowsDone, sampleW, rowsDone);
        return new PreviewSnapshot(request, image, raw, sampleW, rowsDone, complete);
    }

    private static void samplePixels(SampleRequest request, NoiseVisualizer visualizer,
                                     float[] raw, int[] pixels, int sampleW,
                                     int fromRow, int toRow, SamplingSink sink) {
        int step = request.step();
        float halfStep = step * 0.5f;
        // Rows are independent; the common ForkJoin pool sizes itself to the CPU so we get
        // parallel speedup without configuring threads here.
        IntStream.range(fromRow, toRow).parallel().forEach(sy -> {
            if (sink.abandoned()) return;
            int worldZ = Math.round(request.worldZAt(sy * step + halfStep));
            int rowOffset = sy * sampleW;
            for (int sx = 0; sx < sampleW; sx++) {
                // A single row can span a hundred bridge tiles, so polling once per row would
                // leave a preempting request waiting minutes for it to drain.
                if (sx % ABANDON_POLL_INTERVAL == 0 && sink.abandoned()) return;
                int worldX = Math.round(request.worldXAt(sx * step + halfStep));
                float value = visualizer.sample(worldX, worldZ);
                raw[rowOffset + sx] = value;
                pixels[rowOffset + sx] = visualizer.colorFor(visualizer.normalize(value));
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
        // Raster image, no GPU context involved — safe to build off the render thread.
        return Image.makeRasterFromBytes(info, bytes, rowBytes);
    }
}
