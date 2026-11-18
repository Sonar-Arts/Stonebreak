package com.stonebreak.network.server;

import com.openmason.engine.voxel.world.CubePos;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One player's cube-stream state on the server (cubic voxel runtime, #447) — successor of the per-chunk
 * version map. Server tick thread only (requests, unloads and acks are dispatched there too).
 *
 * <ul>
 *   <li>{@link #requested}: cubes the client's REMOTE stream asked for ({@code CubeRequestC2S}), in request
 *       order (nearest first), each with the tick it was asked for; served when the server has the cube.</li>
 *   <li>{@link #sent}: cubes this client holds, with the server cube revision it holds and the tick it was
 *       sent. Replicated edits advance the revision ({@code ServerChunkHandler.afterDeltasFlushed}); a cube
 *       whose server revision moved on without a replicated edit (population, tree growth) is re-sent.</li>
 *   <li>{@link #sentColumns}: column records this client holds, by the server column version it holds.</li>
 *   <li>Flow control (after Minecraft's chunk batching, re-sized for cubes): the client's install rate
 *       (cubes per tick), this tick's quota, and a window of unacknowledged cubes AND bytes.</li>
 * </ul>
 */
public final class PlayerCubeView {

    /** A sent cube: the server revision the client holds and the tick it went out. */
    public static final class Sent {
        public long revision;
        public final int tick;

        Sent(long revision, int tick) {
            this.revision = revision;
            this.tick = tick;
        }
    }

    /** Requests one player may have pending; the oldest are dropped past it (the client asks again). */
    public static final int MAX_REQUESTED = 8192;

    /** Cube -> tick requested; insertion (= request) ordered. */
    public final LinkedHashMap<CubePos, Integer> requested = new LinkedHashMap<>();
    public final Map<CubePos, Sent> sent = new HashMap<>();
    /** Column key ({@code CubePos(cx, 0, cz)}) -> column version sent, and the tick it was sent. */
    public final Map<CubePos, int[]> sentColumns = new HashMap<>();
    /** Cubes to send again (revision moved on, hash audit mismatch), oldest first; no duplicates. */
    public final ArrayDeque<CubePos> resend = new ArrayDeque<>();
    public final java.util.Set<CubePos> resendSet = new java.util.HashSet<>();
    /** Columns whose record must go out again (version bumped while held). */
    public final java.util.LinkedHashSet<CubePos> columnResend = new java.util.LinkedHashSet<>();

    /** Snapshot of {@link #sent}'s keys walked a few per tick by the revision sweep, and the cursor. */
    public CubePos[] sweep = new CubePos[0];
    public int sweepIndex;

    /** Block-state registry names this client has been sent (ids 1..n). */
    public int stateNamesSent;

    // flow control
    private float rate = Float.NaN;
    private float quota;
    private int inFlightCubes;
    private long inFlightBytes;
    private int ticksSinceAck;
    private long cubesSentTotal;

    /** Queues {@code pos} for a re-send (no-op when already queued). */
    public void queueResend(CubePos pos) {
        if (resendSet.add(pos)) {
            resend.add(pos);
        }
    }

    public CubePos pollResend() {
        CubePos p = resend.poll();
        if (p != null) {
            resendSet.remove(p);
        }
        return p;
    }

    /** Adds a request (keeps its first request tick); false when the request table is full. */
    public boolean addRequest(CubePos pos, int tick) {
        if (requested.containsKey(pos)) {
            return true;
        }
        if (requested.size() >= MAX_REQUESTED) {
            return false;
        }
        requested.put(pos, tick);
        return true;
    }

    /** Forgets everything about a cube the client dropped. */
    public void forget(CubePos pos) {
        sent.remove(pos);
        requested.remove(pos);
        if (resendSet.remove(pos)) {
            resend.remove(pos);
        }
    }

    public void markSent(CubePos pos, long revision, int tick, int bytes) {
        sent.put(pos, new Sent(revision, tick));
        requested.remove(pos);
        quota -= 1f;
        inFlightCubes++;
        inFlightBytes += bytes;
        cubesSentTotal++;
    }

    public boolean holds(CubePos pos) {
        return sent.containsKey(pos);
    }

    // ─── flow control ───────────────────────────────────────────────────────────

    public float rate() { return rate; }
    public float quota() { return quota; }
    public void setQuota(float quota) { this.quota = quota; }
    public int inFlightCubes() { return inFlightCubes; }
    public long inFlightBytes() { return inFlightBytes; }
    public long cubesSentTotal() { return cubesSentTotal; }

    /** CubeAckC2S: {@code cubes} records ({@code kib} KiB) left the client's queue; its new rate. */
    public void onAck(int cubes, int kib, float desiredPerTick) {
        inFlightCubes = Math.max(0, inFlightCubes - cubes);
        inFlightBytes = inFlightCubes == 0 ? 0 : Math.max(0, inFlightBytes - (long) kib * 1024L);
        rate = desiredPerTick;
        ticksSinceAck = 0;
    }

    /**
     * True when cubes have been in flight for {@code limit} ticks without an ack: the window reopens (a lost
     * ack or a client that stopped processing must not freeze the stream for good).
     */
    public boolean ackOverdue(int limit) {
        if (inFlightCubes == 0) {
            ticksSinceAck = 0;
            return false;
        }
        if (++ticksSinceAck < limit) {
            return false;
        }
        inFlightCubes = 0;
        inFlightBytes = 0;
        ticksSinceAck = 0;
        return true;
    }
}
