package com.stonebreak.ui.terrainMapper.managers;

import com.stonebreak.ui.terrainMapper.visualization.VisualizerRegistry;
import com.stonebreak.world.generation.diffusion.TerrainTile;
import com.stonebreak.world.generation.diffusion.TerrainTileSource;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Preemption: a request that arrives while a pass is running must take over rather than queue
 * behind it. This is what makes the visualizer buttons feel like buttons — a zoomed-out pass is
 * thousands of diffusion tiles on a single-GPU queue, so "wait your turn" means tens of minutes.
 *
 * <p>The sampler is faked: the real one would need the terrain bridge, and what is under test
 * here is the loader's scheduling, not terrain production. The snapshots it returns still carry a
 * real (1x1) raster image, because handing images back for closing is part of what the loader
 * does with a published snapshot. Requests are told apart by their tile source, which is what
 * distinguishes them in production too — a reseed installs a fresh one. The registry is real but with service
 * autostart switched off — the same escape hatch developers use to run the two Python services by
 * hand — so {@code ensureServices()} is the no-op it is meant to be once they are up.
 */
class TerrainPreviewLoaderTest {

    /**
     * Two distinct terrain sources, standing in for two seeds. Neither is ever read: the loader
     * schedules around requests, and the fake sampler never samples.
     */
    private static final TerrainTileSource ONE_SEED = unusedSource();
    private static final TerrainTileSource ANOTHER_SEED = unusedSource();

    private static VisualizerRegistry registry;

    @BeforeAll
    static void withoutLaunchingTerrainServices() {
        // Must be set before TerrainServiceProcessManager's singleton reads it.
        System.setProperty("stonebreak.terrainService.autostart", "false");
        registry = new VisualizerRegistry(1234L);
    }

    private static TerrainTileSource unusedSource() {
        return (worldX, worldZ) -> {
            throw new UnsupportedOperationException("loader scheduling must not touch terrain");
        };
    }

    /**
     * Stands in for {@link TerrainPreviewSampler}: blocks on a latch to hold a job "in flight",
     * polls the sink exactly the way the real sampler does, and records what it was asked for.
     */
    private static final class FakeSampler {
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch started = new CountDownLatch(1);
        private volatile SampleRequest lastAbandoned;

        PreviewSnapshot sample(SampleRequest request, SamplingSink sink) {
            started.countDown();
            try {
                // Stands in for the bridge round trips a real pass blocks on.
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (sink.abandoned()) {
                lastAbandoned = request;
                return null;
            }
            return snapshotFor(request, true);
        }
    }

    private static PreviewSnapshot snapshotFor(SampleRequest request, boolean complete) {
        ImageInfo info = new ImageInfo(1, 1, ColorType.RGBA_8888, ColorAlphaType.OPAQUE, null);
        byte[] bytes = new byte[FieldPacking.BYTES_PER_TEXEL];
        FieldPacking.pack(bytes, 0, 400, 0);
        Image field = Image.makeRasterFromBytes(info, bytes, 4);
        return new PreviewSnapshot(request, field, bytes, 1, 1, complete);
    }

    private final FakeSampler fake = new FakeSampler();
    private final TerrainPreviewLoader loader = new TerrainPreviewLoader(fake::sample);

    @AfterEach
    void tearDown() {
        fake.release.countDown();
        loader.dispose();
    }

    private static SampleRequest requestFor(TerrainTileSource tiles) {
        return requestFor(tiles, 1f);
    }

    private static SampleRequest requestFor(TerrainTileSource tiles, float zoom) {
        return new SampleRequest(tiles, registry, 320, 240, 2, 0f, 0f, zoom);
    }

    @Test
    void aNewRequestPreemptsThePassInFlight() throws InterruptedException {
        SampleRequest first = requestFor(ONE_SEED);
        SampleRequest second = requestFor(ANOTHER_SEED);

        loader.request(first);
        assertTrue(fake.started.await(2, TimeUnit.SECONDS), "the first job should start immediately");

        loader.request(second);       // seed changed mid-pass
        fake.release.countDown();     // the first pass reaches its next abandonment check

        PreviewSnapshot published = awaitSnapshot();
        assertSame(second, published.request(), "the view the user actually asked for must win");
        assertSame(first, fake.lastAbandoned, "the superseded pass must have given up, not finished");
    }

    /**
     * A mode switch is no longer a resample at all — one field serves every visualizer — so a
     * request that differs only in the selected mode must not restart sampling.
     */
    @Test
    void switchingVisualizerDoesNotResample() throws InterruptedException {
        AtomicInteger passes = new AtomicInteger();
        TerrainPreviewLoader counting = new TerrainPreviewLoader((request, sink) -> {
            passes.incrementAndGet();
            return snapshotFor(request, true);
        });
        try {
            counting.request(requestFor(ONE_SEED));
            awaitSnapshot(counting);
            // The renderer asks again every frame; nothing about the viewport or seed has changed,
            // and the visualizer is not part of the request, so there is nothing new to sample.
            for (int frame = 0; frame < 10; frame++) {
                counting.request(requestFor(ONE_SEED));
                Thread.sleep(2);
            }
            assertEquals(1, passes.get(), "the same view was sampled more than once");
        } finally {
            counting.dispose();
        }
    }

    @Test
    void statusReportsSamplingWhileAPassIsRunning() throws InterruptedException {
        loader.request(requestFor(ANOTHER_SEED));
        assertTrue(fake.started.await(2, TimeUnit.SECONDS));

        assertEquals("Sampling terrain...", loader.statusMessage());
    }

    @Test
    void aHalfDrawnViewIsResampledRatherThanTakenAsDone() throws InterruptedException {
        // An abandoned pass leaves behind a partial carrying the very request that would redraw
        // it. Deduping against that would strand the map half drawn for good.
        AtomicInteger passes = new AtomicInteger();
        TerrainPreviewLoader gaveUpHalfWay = new TerrainPreviewLoader((request, sink) -> {
            passes.incrementAndGet();
            sink.partial(snapshotFor(request, false));
            return null;
        });
        try {
            SampleRequest request = requestFor(ONE_SEED);
            // Mirrors the render loop, which asks for the current view every frame.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (passes.get() < 2 && System.nanoTime() < deadline) {
                gaveUpHalfWay.request(request);
                Thread.sleep(5);
            }
            assertEquals(2, passes.get(), "the rest of the view was never asked for");
        } finally {
            gaveUpHalfWay.dispose();
        }
    }

    @Test
    void theLastFullPictureStaysUnderThePassThatReplacesIt() throws InterruptedException {
        // Zooming out asks for a whole new grid. Its first band is a strip across the top, and
        // swapping that in for the finished map the user was just looking at would blank almost
        // the entire viewport for the length of the pass.
        CountDownLatch zoomedPassDone = new CountDownLatch(1);
        TerrainPreviewLoader zooming = new TerrainPreviewLoader((request, sink) -> {
            if (request.zoom() == 1f) return snapshotFor(request, true);
            sink.partial(snapshotFor(request, false));
            sink.partial(snapshotFor(request, false));
            zoomedPassDone.countDown();
            return null;
        });
        try {
            zooming.request(requestFor(ONE_SEED, 1f));
            PreviewSnapshot full = awaitSnapshot(zooming);
            assertTrue(full.complete(), "the first pass should have finished");

            zooming.request(requestFor(ONE_SEED, 0.5f));
            assertTrue(zoomedPassDone.await(2, TimeUnit.SECONDS));

            assertSame(full, zooming.backdrop(), "the finished map must survive under the new pass");
            assertFalse(zooming.snapshot().complete(), "the top layer is the pass still filling in");
            assertEquals(0.5f, zooming.snapshot().request().zoom(), 0f, "drawn over at the new zoom");
        } finally {
            zooming.dispose();
        }
    }

    @Test
    void aBackdropIsOnlyReplacedByAnotherFullPicture() throws InterruptedException {
        // Two zooms in a row: the backdrop must advance a whole picture at a time, never to a
        // strip from a pass that was itself abandoned part-way.
        CountDownLatch secondZoomDone = new CountDownLatch(1);
        TerrainPreviewLoader zooming = new TerrainPreviewLoader((request, sink) -> {
            if (request.zoom() == 1f) return snapshotFor(request, true);
            sink.partial(snapshotFor(request, false));
            if (request.zoom() == 0.25f) secondZoomDone.countDown();
            return null;
        });
        try {
            zooming.request(requestFor(ONE_SEED, 1f));
            PreviewSnapshot full = awaitSnapshot(zooming);

            zooming.request(requestFor(ONE_SEED, 0.5f));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (zooming.snapshot() == full && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            zooming.request(requestFor(ONE_SEED, 0.25f));
            assertTrue(secondZoomDone.await(2, TimeUnit.SECONDS));

            assertSame(full, zooming.backdrop(), "a half-drawn pass must not become the backdrop");
        } finally {
            zooming.dispose();
        }
    }

    private PreviewSnapshot awaitSnapshot() throws InterruptedException {
        return awaitSnapshot(loader);
    }

    private static PreviewSnapshot awaitSnapshot(TerrainPreviewLoader loader) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            PreviewSnapshot snapshot = loader.snapshot();
            if (snapshot != null) return snapshot;
            Thread.sleep(10);
        }
        PreviewSnapshot snapshot = loader.snapshot();
        assertNotNull(snapshot, "no snapshot was ever published");
        return snapshot;
    }
}
