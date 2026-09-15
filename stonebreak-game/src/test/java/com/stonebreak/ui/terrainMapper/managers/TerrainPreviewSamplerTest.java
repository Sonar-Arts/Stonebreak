package com.stonebreak.ui.terrainMapper.managers;

import com.stonebreak.ui.terrainMapper.visualization.NoiseVisualizer;
import com.stonebreak.ui.terrainMapper.visualization.PreviewChannel;
import com.stonebreak.ui.terrainMapper.visualization.PreviewSampleStore;
import com.stonebreak.ui.terrainMapper.visualization.PreviewSource;
import com.stonebreak.ui.terrainMapper.visualization.TerrainColumns;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two behaviours that keep the terrain mapper usable when a pass is slow: it hands over rows
 * as they finish, and it gives up promptly when told to. Both matter because one zoomed-out pass
 * is thousands of diffusion tiles on a single-GPU queue — without them, selecting a different
 * visualizer sits behind tens of minutes of work with nothing on screen to show for it.
 *
 * <p>The visualizer here is a counter, not terrain: it records how many samples were taken so
 * abandonment can be measured, and never touches the bridge.
 */
class TerrainPreviewSamplerTest {

    /** 320x240 rect at step 2 -> a 160x120 sample grid, i.e. 16 bands of 7 rows plus a tail. */
    private static final int WIDTH_PX = 320;
    private static final int HEIGHT_PX = 240;
    private static final int STEP = 2;
    private static final int SAMPLE_W = WIDTH_PX / STEP;
    private static final int SAMPLE_H = HEIGHT_PX / STEP;

    private final AtomicInteger samplesTaken = new AtomicInteger();

    private final NoiseVisualizer counting = new NoiseVisualizer() {
        @Override public String displayName() { return "counting"; }
        @Override public float sample(int worldX, int worldZ) {
            samplesTaken.incrementAndGet();
            return worldZ;
        }
        @Override public float normalize(float raw) { return 0.5f; }
    };

    private final TerrainPreviewSampler sampler = new TerrainPreviewSampler();

    private SampleRequest request() {
        return new SampleRequest(counting, null, WIDTH_PX, HEIGHT_PX, STEP, 0f, 0f, 1f);
    }

    /** Collects partials; abandons once {@code abandonAfter} of them have arrived. */
    private static final class RecordingSink implements SamplingSink {
        private final List<PreviewSnapshot> partials = new ArrayList<>();
        private final int abandonAfter;
        private boolean abandoned;

        RecordingSink(int abandonAfter) {
            this.abandonAfter = abandonAfter;
        }

        @Override public boolean abandoned() { return abandoned; }

        @Override public void partial(PreviewSnapshot slice) {
            partials.add(slice);
            if (partials.size() >= abandonAfter) abandoned = true;
        }
    }

    @Test
    void publishesFinishedRowsBeforeTheWholeGridIsDone() {
        RecordingSink sink = new RecordingSink(Integer.MAX_VALUE);

        PreviewSnapshot finished = sampler.sample(request(), sink);

        assertNotNull(finished);
        assertTrue(sink.partials.size() > 4,
                "a pass should fill in over several slices, got " + sink.partials.size());

        int previousRows = 0;
        for (PreviewSnapshot partial : sink.partials) {
            assertFalse(partial.complete(), "an unfinished slice must not claim to be complete");
            assertTrue(partial.sampleH() > previousRows, "each slice must extend the last");
            assertTrue(partial.sampleH() < SAMPLE_H, "the full grid is published as the result, not a partial");
            assertEquals(SAMPLE_W, partial.sampleW(), "bands are whole rows");
            previousRows = partial.sampleH();
        }
    }

    @Test
    void theFinalSnapshotCoversTheWholeRequestedGrid() {
        PreviewSnapshot finished = sampler.sample(request(), new RecordingSink(Integer.MAX_VALUE));

        assertNotNull(finished);
        assertTrue(finished.complete());
        assertEquals(SAMPLE_W, finished.sampleW());
        assertEquals(SAMPLE_H, finished.sampleH());
        assertEquals(SAMPLE_W * SAMPLE_H, samplesTaken.get(), "every cell sampled exactly once");
    }

    @Test
    void aMarginIsSampledOnEverySide() {
        int margin = 40;
        SampleRequest withMargin = new SampleRequest(counting, null, WIDTH_PX, HEIGHT_PX, STEP, 0f, 0f, 1f, margin, null);

        PreviewSnapshot finished = sampler.sample(withMargin, new RecordingSink(Integer.MAX_VALUE));

        assertNotNull(finished);
        assertEquals(SAMPLE_W + 2 * margin / STEP, finished.sampleW());
        assertEquals(SAMPLE_H + 2 * margin / STEP, finished.sampleH());
        // The counter returns worldZ: the top row sits a margin above the visible rect's top.
        assertEquals(-HEIGHT_PX / 2f - margin, finished.raw()[0], 0f);
    }

    @Test
    void groundAlreadySampledIsServedFromTheStoreInAnyModeSharingItsChannel() {
        AtomicInteger columnReads = new AtomicInteger();
        TerrainColumns columns = (x, z, out) -> {
            columnReads.incrementAndGet();
            out[PreviewChannel.HEIGHT.ordinal()] = x + z;
            out[PreviewChannel.WATER.ordinal()] = 7f;
            out[PreviewChannel.BIOME.ordinal()] = 2f;
        };
        PreviewSource source = new PreviewSource(99L, columns, new PreviewSampleStore(64L * 1024 * 1024));
        NoiseVisualizer height = channelVisualizer(PreviewChannel.HEIGHT);
        NoiseVisualizer water = channelVisualizer(PreviewChannel.WATER);

        PreviewSnapshot first = sampler.sample(
                new SampleRequest(height, null, WIDTH_PX, HEIGHT_PX, STEP, 0f, 0f, 1f, 0, source),
                new RecordingSink(Integer.MAX_VALUE));
        int readsAfterFirstPass = columnReads.get();
        assertEquals(SAMPLE_W * SAMPLE_H, readsAfterFirstPass, "every column read once the first time");
        assertEquals(first.raw()[0], -HEIGHT_PX / 2f - WIDTH_PX / 2f, 0f, "the column's height channel");

        // Panned a little and switched to another channel of the same columns.
        PreviewSnapshot second = sampler.sample(
                new SampleRequest(water, null, WIDTH_PX, HEIGHT_PX, STEP, 20f, 0f, 1f, 0, source),
                new RecordingSink(Integer.MAX_VALUE));
        assertEquals(7f, second.raw()[0], 0f);
        assertEquals(readsAfterFirstPass + 10 * SAMPLE_H, columnReads.get(),
                "only the 10 newly exposed columns should have touched the terrain");
        assertEquals(0, samplesTaken.get(), "a cached visualizer must not be sampled directly");
    }

    private NoiseVisualizer channelVisualizer(PreviewChannel channel) {
        return new NoiseVisualizer() {
            @Override public String displayName() { return channel.name(); }
            @Override public PreviewChannel channel() { return channel; }
            @Override public float sample(int worldX, int worldZ) {
                samplesTaken.incrementAndGet();
                return Float.NaN;
            }
        };
    }

    @Test
    void rowsAlreadyPublishedKeepTheirValues() {
        // Partials share one raw array with the passes that follow them. If a later band could
        // touch an earlier row, the map would flicker under the hover readout.
        RecordingSink sink = new RecordingSink(Integer.MAX_VALUE);
        PreviewSnapshot finished = sampler.sample(request(), sink);

        PreviewSnapshot first = sink.partials.get(0);
        for (int sy = 0; sy < first.sampleH(); sy++) {
            for (int sx = 0; sx < first.sampleW(); sx++) {
                int index = sy * first.sampleW() + sx;
                assertEquals(first.raw()[index], finished.raw()[index], 0f,
                        "row " + sy + " changed after being published");
            }
        }
    }

    @Test
    void abandonsWithoutFinishingTheGrid() {
        RecordingSink sink = new RecordingSink(1);

        PreviewSnapshot finished = sampler.sample(request(), sink);

        assertNull(finished, "abandoned work must not be offered as a result");
        assertEquals(1, sink.partials.size(), "no slices published after the abandon");
        assertTrue(samplesTaken.get() < SAMPLE_W * SAMPLE_H,
                "abandoning should stop the pass short, took " + samplesTaken.get()
                        + " of " + (SAMPLE_W * SAMPLE_H));
    }

    @Test
    void abandonedBeforeItStartsCostsNothing() {
        PreviewSnapshot finished = sampler.sample(request(), new SamplingSink() {
            @Override public boolean abandoned() { return true; }
            @Override public void partial(PreviewSnapshot slice) {
                throw new AssertionError("nothing to publish from a job that never ran");
            }
        });

        assertNull(finished);
        assertEquals(0, samplesTaken.get(), "a job already superseded must not sample at all");
    }
}
