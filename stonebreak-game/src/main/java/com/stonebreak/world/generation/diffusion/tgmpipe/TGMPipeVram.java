package com.stonebreak.world.generation.diffusion.tgmpipe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The terrain service's GPU memory, for the F3 debug screen: the {@code "vram"} object of its STATUS
 * reply (Python {@code terrain_slm/tgmpipe/gpu_memory.py}). The service is its own process, usually on
 * another GPU, so the game's own trackers never see it.
 *
 * <p>{@link #latest()} never blocks: it returns the last reading and, at most once per
 * {@link #REFRESH_MS}, asks the service for a new one in the background. It never starts the service;
 * while the service is down (or its last answer is older than {@link #STALE_MS}) it returns null and
 * the debug screen leaves the Daedalus section out.
 */
public final class TGMPipeVram {

    /** One reading; {@code -1} = not reported (no NVML, or a CPU service, which has none of it). */
    public record Reading(String device, String gpu, long processBytes, long allocatedBytes, long reservedBytes,
                          long peakBytes, long deviceUsedBytes, long deviceTotalBytes, long atMillis) {

        public boolean onGpu() {
            return device != null && device.startsWith("cuda");
        }
    }

    static final long REFRESH_MS = 1000;
    static final long STALE_MS = 5000;
    private static final long REPLY_TIMEOUT_MS = 5000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TGMPipeVram SHARED = new TGMPipeVram(() -> TGMPipe.getInstance().status(),
            System::currentTimeMillis);

    private final Supplier<CompletableFuture<String>> status;
    private final LongSupplier clock;
    private final AtomicReference<Reading> last = new AtomicReference<>();
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private volatile long lastAskedMs = Long.MIN_VALUE / 2;

    TGMPipeVram(Supplier<CompletableFuture<String>> status, LongSupplier clock) {
        this.status = status;
        this.clock = clock;
    }

    /** Bound to {@link TGMPipe#getInstance()}. */
    public static TGMPipeVram shared() {
        return SHARED;
    }

    /** The newest reading still fresh, or null; schedules a refresh when one is due. Any thread. */
    public Reading latest() {
        long now = clock.getAsLong();
        if (now - lastAskedMs >= REFRESH_MS && inFlight.compareAndSet(false, true)) {
            lastAskedMs = now;
            CompletableFuture<String> reply;
            try {
                reply = status.get();
            } catch (RuntimeException e) {
                reply = CompletableFuture.failedFuture(e);
            }
            reply.orTimeout(REPLY_TIMEOUT_MS, TimeUnit.MILLISECONDS).whenComplete((json, err) -> {
                last.set(err == null ? parse(json, clock.getAsLong()) : null);   // service gone: drop the section
                inFlight.set(false);
            });
        }
        Reading r = last.get();
        return r != null && now - r.atMillis() <= STALE_MS ? r : null;
    }

    /** The {@code "vram"} object of a STATUS reply, or null when absent / unreadable. */
    static Reading parse(String statusJson, long atMillis) {
        try {
            JsonNode v = JSON.readTree(statusJson).get("vram");
            if (v == null || !v.isObject()) {
                return null;
            }
            return new Reading(v.path("device").asText(null), v.path("gpu").asText(null),
                    v.path("process_bytes").asLong(-1), v.path("allocated_bytes").asLong(-1),
                    v.path("reserved_bytes").asLong(-1), v.path("peak_bytes").asLong(-1),
                    v.path("device_used_bytes").asLong(-1), v.path("device_total_bytes").asLong(-1), atMillis);
        } catch (Exception e) {   // diagnostics only: a bad reply hides the section, never breaks the screen
            return null;
        }
    }
}
