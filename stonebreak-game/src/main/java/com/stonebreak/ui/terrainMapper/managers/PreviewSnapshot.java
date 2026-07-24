package com.stonebreak.ui.terrainMapper.managers;

import io.github.humbleui.skija.Image;

/**
 * One finished terrain sampling, published from the loader's worker thread to the render
 * thread. Immutable after construction — the sampler hands over ownership of {@code raw} and
 * never touches it again — so it crosses threads on a single volatile reference assignment.
 *
 * <p>It carries the {@link SampleRequest} it was produced for, not just the pixels. The
 * viewport keeps moving while a sample is in flight, so the renderer needs to know where in
 * the world these pixels actually belong in order to draw them in the right place. That is
 * what lets a pan stay instant instead of blanking the map on every drag.
 *
 * <p>A snapshot may be a <em>partial</em> one: the sampler publishes the rows it has finished
 * after every row band, so a pass that takes minutes fills the map in from the top rather than
 * showing nothing at all. A partial covers less world than the request asked for — {@code sampleH}
 * is the number of rows actually sampled — which the geometry below already accounts for.
 *
 * @param raw     pre-normalize samples, row-major; the first {@code sampleW * sampleH} entries
 *                are this snapshot's. A partial shares the whole pass's array, so it can be
 *                longer than that — the tail is the rows still being sampled.
 * @param sampleW/sampleH image dimensions; may be a step short of the full rect, since the
 *                        sampler divides the rect by the step
 * @param complete whether every row the request asked for is present. Callers use this to tell a
 *                 finished view from an abandoned half-drawn one, which otherwise carry the same
 *                 {@link SampleRequest} and would be indistinguishable.
 */
public record PreviewSnapshot(
        SampleRequest request,
        Image image,
        float[] raw,
        int sampleW,
        int sampleH,
        boolean complete
) {

    /** World X of the image's left edge. */
    public float worldLeft() {
        return request.worldXAt(0f);
    }

    /** World Z of the image's top edge. */
    public float worldTop() {
        return request.worldZAt(0f);
    }

    /**
     * World-space width the image covers. Derived from {@code sampleW * step} rather than the
     * rect width because integer division in the sampler can leave the sample grid a step
     * short of the rect; using the rect width here would stretch the image slightly.
     */
    public float worldWidth() {
        return (sampleW * request.step()) / request.zoom();
    }

    public float worldHeight() {
        return (sampleH * request.step()) / request.zoom();
    }

    /**
     * Raw sampled value at a world position, or {@link Float#NaN} if that position falls
     * outside what this snapshot covers. Lets the hover readout report exactly the value
     * behind the pixel under the cursor without touching the terrain bridge.
     */
    public float valueAt(float worldX, float worldZ) {
        float scale = request.zoom() / request.step();
        int sx = (int) Math.floor((worldX - worldLeft()) * scale);
        int sz = (int) Math.floor((worldZ - worldTop()) * scale);
        if (sx < 0 || sx >= sampleW || sz < 0 || sz >= sampleH) return Float.NaN;
        return raw[sz * sampleW + sx];
    }
}
