package com.stonebreak.mobs.entities.ai.perception;

import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The one-off sounds made in a world lately — a block broken, a swing, a death — for every
 * listening mob to hear once (#321). Continuous movement noise is not posted here; perception
 * reads that from the player itself each tick.
 *
 * <p>Each event is numbered in order and kept for {@link #RETENTION_NANOS}, comfortably longer
 * than one perception period, so every mob's next perception tick sees it. A listener asks for
 * the events after the last number it heard, so it hears each event exactly once whatever its
 * tick phase.
 *
 * <p>Written and read on the authoritative world's tick thread only.
 */
public final class NoiseBus {

    /** Sound number {@code seq}: {@code loudness} (0–10, see {@link Loudness}) at {@code position}, made at {@code time}. */
    public record NoiseEvent(long seq, Vector3f position, float loudness, long time) {}

    static final long RETENTION_NANOS = 1_000_000_000L;

    private final ArrayDeque<NoiseEvent> events = new ArrayDeque<>();
    private final LongSupplier clock;
    private long lastSeq;

    public NoiseBus() {
        this(System::nanoTime);
    }

    /** With a controllable clock, for tests. */
    public NoiseBus(LongSupplier clock) {
        this.clock = clock;
    }

    /** The number of the latest sound posted (0 before any); listeners keep the one they heard up to. */
    public long latest() {
        return lastSeq;
    }

    /** Posts a sound to {@code world}'s bus; nothing without a world or a bus (tests, mocks). */
    public static void emitIn(com.stonebreak.world.World world, Vector3f position, float loudness) {
        NoiseBus bus = world != null ? world.noiseBus() : null;
        if (bus != null) {
            bus.emit(position, loudness);
        }
    }

    /** Posts a sound. Silent sounds (loudness ≤ 0) are dropped. */
    public void emit(Vector3f position, float loudness) {
        if (!(loudness > 0f)) {
            return;
        }
        long now = clock.getAsLong();
        prune(now);
        events.addLast(new NoiseEvent(++lastSeq, new Vector3f(position), loudness, now));
    }

    /** Hands every still-retained event numbered after {@code since} to {@code sink}, oldest first. */
    public void heardSince(long since, Consumer<NoiseEvent> sink) {
        prune(clock.getAsLong());
        for (NoiseEvent e : events) {
            if (e.seq() > since) {
                sink.accept(e);
            }
        }
    }

    private void prune(long now) {
        Iterator<NoiseEvent> it = events.iterator();
        while (it.hasNext() && now - it.next().time() > RETENTION_NANOS) {
            it.remove();
        }
    }
}
