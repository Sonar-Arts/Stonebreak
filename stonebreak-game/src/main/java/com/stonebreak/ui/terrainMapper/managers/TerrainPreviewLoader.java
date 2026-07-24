package com.stonebreak.ui.terrainMapper.managers;

import com.stonebreak.world.generation.diffusion.TerrainBridgeException;
import io.github.humbleui.skija.Image;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs terrain preview sampling off the render thread and publishes finished images back to it.
 *
 * <p>Everything this class exists to move off the main thread blocks for a <em>long</em> time.
 * {@code VisualizerRegistry.ensureServices()} boots a CUDA model server and a FastAPI bridge and
 * can take a minute; each individual sample can reach through to that bridge for a diffusion
 * inference with a 35 s request timeout. Previously both happened inline in
 * {@code TerrainMapRenderer.render()}, which is exactly why opening the terrain mapper froze
 * the game. Now the render thread only ever calls {@link #request} (non-blocking) and reads
 * {@link #snapshot()} (a volatile field), so it never waits on terrain at all — the map simply
 * stays blank until there is something to draw.
 *
 * <p>Only one job runs at a time. A request arriving while one is in flight replaces any
 * previously queued request rather than joining a queue, so the dozens of per-frame requests a
 * mouse drag produces collapse into a single follow-up sample, and the job in flight is told to
 * give up as soon as it notices. Abandoning rather than finishing is not a lost cause — the
 * tiles it fetched stay cached both client- and bridge-side — and it is the difference between a
 * new visualizer showing up in about a second and it waiting out a zoomed-out pass, which is
 * thousands of diffusion tiles on a single-GPU queue and can run for tens of minutes.
 *
 * <p>A job publishes as it goes, one row band at a time, so a slow pass fills the map in from
 * the top instead of leaving the previous image up until the very end.
 */
public final class TerrainPreviewLoader {

    private static final Logger LOG = Logger.getLogger(TerrainPreviewLoader.class.getName());
    private static final long FAILURE_BACKOFF_NANOS = 1_000_000_000L;

    /** What the loader is doing right now, for the on-map status readout. */
    public enum Phase { IDLE, STARTING_SERVICES, SAMPLING, READY, FAILED }

    /**
     * The sampling step as this class depends on it — {@link TerrainPreviewSampler#sample} is the
     * implementation. Narrowed to a function so the scheduling here can be exercised without a
     * live terrain bridge behind it.
     */
    @FunctionalInterface
    public interface Sampling {
        PreviewSnapshot sample(SampleRequest request, SamplingSink sink);
    }

    private final Sampling sampler;
    private final ExecutorService executor;

    /**
     * Images displaced by a newer snapshot, waiting to be closed. The worker must never call
     * {@link Image#close()} itself — the render thread may be mid-draw on that exact image, and
     * freeing a live Skija resource under it is a use-after-free. Instead the render thread
     * drains this at the top of a frame, by which point any draw it issued has long since
     * completed on that same thread.
     */
    private final ConcurrentLinkedQueue<Image> retired = new ConcurrentLinkedQueue<>();

    private final Object lock = new Object();
    private boolean busy;
    private SampleRequest inFlight;
    private SampleRequest pending;

    private volatile PreviewSnapshot snapshot;

    /**
     * The last snapshot that covered its whole viewport, kept alive under {@link #snapshot} once
     * a newer request displaces it. Without it, the first band of a new pass would replace a full
     * map with a strip and blank everything else — most obviously on a zoom, where the picture
     * the user just had is still perfectly good terrain, only at the wrong scale.
     *
     * <p>The renderer reprojects both by world extent, so the backdrop lands wherever it belongs
     * under the new pass and is painted over band by band.
     */
    private volatile PreviewSnapshot backdrop;

    private volatile Phase phase = Phase.IDLE;
    private volatile String failureMessage;
    private volatile long retryAfterNanos;

    /**
     * Raised the moment the running job's result stops being worth having. Read from the
     * sampler's innermost loops, so it is a plain volatile flag rather than something that takes
     * {@link #lock} — the sampler polls it far more often than anything sets it.
     */
    private volatile boolean abandonCurrent;

    /** Display name of the visualizer being sampled, for the status line. */
    private volatile String samplingLabel;

    /**
     * Bumped by {@link #reset()}. A job carries the value it started under and its result is
     * dropped if that no longer matches — otherwise a sample still in flight when the screen is
     * left would land after the reset and briefly show the previous world's terrain on re-entry.
     */
    private volatile int generation;

    public TerrainPreviewLoader(Sampling sampler) {
        this.sampler = sampler;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "terrain-preview-loader");
            thread.setDaemon(true);
            return thread;
        });
    }

    // ─────────────────────────────────────────────── Render-thread API

    /** Newest sampling, complete or still filling in, or null when there is nothing to draw yet. */
    public PreviewSnapshot snapshot() { return snapshot; }

    /** Last full picture, to be drawn <em>under</em> {@link #snapshot()}. Null if there isn't one. */
    public PreviewSnapshot backdrop() { return backdrop; }

    public Phase phase() { return phase; }

    /** Short line describing the current phase, or null when the map is up to date. */
    public String statusMessage() {
        return switch (phase) {
            case IDLE -> "Preparing terrain preview...";
            case STARTING_SERVICES -> "Starting terrain services...";
            // Names the visualizer: with a previous mode's image still on the map, "Sampling
            // terrain..." gives no way to tell a mode switch that is working from one that isn't.
            case SAMPLING -> samplingLabel == null
                    ? "Sampling terrain..."
                    : "Sampling " + samplingLabel + "...";
            case FAILED -> failureMessage == null ? "Terrain preview unavailable" : failureMessage;
            case READY -> null;
        };
    }

    /**
     * Asks for a sample matching {@code request}, unless it already matches what is on screen
     * or what is already being worked on. Returns immediately in every case.
     */
    public void request(SampleRequest request) {
        if (request == null) return;
        synchronized (lock) {
            if (request.equals(inFlight) || request.equals(pending)) return;
            PreviewSnapshot current = snapshot;
            // current.complete() matters: an abandoned job leaves a half-drawn snapshot carrying
            // the very request that would redraw it, and without this the two are indistinguishable
            // and the rest of the map would never be filled in.
            if (!busy && current != null && current.complete() && request.equals(current.request())) return;
            if (System.nanoTime() < retryAfterNanos) return;
            if (busy) {
                // Supersede whatever was queued — only the latest viewport matters — and tell the
                // running job to stop, since its result is already out of date.
                pending = request;
                abandonCurrent = true;
                return;
            }
            inFlight = request;
            busy = true;
        }
        executor.execute(this::drain);
    }

    /**
     * Closes images displaced by newer snapshots. Must be called from the render thread, at the
     * start of a frame — see {@link #retired}.
     */
    public void retireCompleted() {
        Image image;
        while ((image = retired.poll()) != null) {
            image.close();
        }
    }

    /** Drops back to a blank map. Any in-flight job gives up and is discarded. */
    public void reset() {
        synchronized (lock) {
            pending = null;
            generation++;
            abandonCurrent = true;
        }
        PreviewSnapshot previous = snapshot;
        PreviewSnapshot previousBackdrop = backdrop;
        snapshot = null;
        backdrop = null;
        phase = Phase.IDLE;
        failureMessage = null;
        if (previous != null) retired.add(previous.image());
        if (previousBackdrop != null) retired.add(previousBackdrop.image());
    }

    public void dispose() {
        executor.shutdownNow();
        try {
            // Wait the worker out before freeing images, so it can't publish into a queue we
            // have already drained.
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                LOG.warning("terrain preview loader did not stop in time; leaking its current image");
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        PreviewSnapshot current = snapshot;
        PreviewSnapshot under = backdrop;
        snapshot = null;
        backdrop = null;
        if (current != null) retired.add(current.image());
        if (under != null) retired.add(under.image());
        retireCompleted();
    }

    // ─────────────────────────────────────────────── Worker thread

    private void drain() {
        while (true) {
            SampleRequest request;
            int startedAt;
            synchronized (lock) {
                request = inFlight;
                startedAt = generation;
                if (request == null) {
                    busy = false;
                    return;
                }
                // Not a blind clear: a request that superseded this one can land in the window
                // between the previous job ending and this block, and clearing the flag it raised
                // would let an already-stale job run to completion anyway.
                abandonCurrent = pending != null;
            }
            runJob(request, startedAt);
            synchronized (lock) {
                inFlight = pending;
                pending = null;
                if (inFlight == null) {
                    busy = false;
                    return;
                }
            }
        }
    }

    private void runJob(SampleRequest request, int startedAt) {
        try {
            phase = Phase.STARTING_SERVICES;
            request.registry().ensureServices();
            samplingLabel = request.visualizer().displayName();
            phase = Phase.SAMPLING;
            PreviewSnapshot finished = sampler.sample(request, new JobSink(startedAt));
            // Abandoned: the partials it published stand, and the request that replaced this one
            // is already waiting in drain(). Leaving the phase on SAMPLING is honest — the next
            // job starts immediately.
            if (finished == null) return;
            if (!publish(finished, startedAt)) return;
            failureMessage = null;
            retryAfterNanos = 0L;
            phase = Phase.READY;
        } catch (TerrainBridgeException e) {
            // The preview is a convenience view, not world data, so a bridge blip (typically the
            // services restarting for a new seed) must not take the game down the way it rightly
            // does during chunk generation. Keep the last good image and back off before retrying.
            retryAfterNanos = System.nanoTime() + FAILURE_BACKOFF_NANOS;
            failureMessage = "Terrain preview failed: " + e.getMessage();
            phase = Phase.FAILED;
            LOG.log(Level.WARNING, "terrain preview sampling failed; keeping previous image", e);
        } catch (RuntimeException e) {
            // A worker that dies silently would leave the map blank with no explanation.
            retryAfterNanos = System.nanoTime() + FAILURE_BACKOFF_NANOS;
            failureMessage = "Terrain preview error: " + e;
            phase = Phase.FAILED;
            LOG.log(Level.SEVERE, "unexpected terrain preview failure", e);
        }
    }

    /**
     * The running job's view of this loader: whether its result is still wanted, and where to
     * hand rows as they finish. Bound to the generation the job started under so a late partial
     * from a job outlived by a {@link #reset()} is dropped like any other stale result.
     */
    private final class JobSink implements SamplingSink {

        private final int startedAt;

        private JobSink(int startedAt) {
            this.startedAt = startedAt;
        }

        @Override
        public boolean abandoned() {
            return abandonCurrent || startedAt != generation;
        }

        @Override
        public void partial(PreviewSnapshot slice) {
            publish(slice, startedAt);
        }
    }

    /**
     * Installs {@code next} as the visible snapshot unless a {@link #reset()} landed while it
     * was being sampled, in which case the finished image is thrown away instead. Returns
     * whether the snapshot was actually published, so the caller can leave the phase alone for
     * work that no longer applies.
     *
     * <p>The snapshot being displaced is kept as the {@link #backdrop} rather than freed when it
     * is a full picture belonging to an older request — the case where a new pass has just begun
     * and has nothing but its first band to show. Every other displaced image is dropped:
     * a partial is superseded by the longer prefix that replaces it, and a snapshot of the same
     * request adds nothing the newer one doesn't already cover.
     */
    private boolean publish(PreviewSnapshot next, int startedAt) {
        synchronized (lock) {
            if (startedAt != generation) {
                retired.add(next.image());
                return false;
            }
            PreviewSnapshot previous = snapshot;
            snapshot = next;
            if (previous == null) {
                return true;
            }
            if (previous.complete() && !previous.request().equals(next.request())) {
                PreviewSnapshot displaced = backdrop;
                backdrop = previous;
                if (displaced != null) retired.add(displaced.image());
            } else {
                retired.add(previous.image());
            }
            return true;
        }
    }
}
