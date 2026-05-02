package com.stonebreak.network.sync.synchronizers;

import com.stonebreak.core.Game;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.network.protocol.NetworkChunkCodec;
import com.stonebreak.network.protocol.Packet;
import com.stonebreak.network.server.IntegratedServer;
import com.stonebreak.network.server.RemoteClient;
import com.stonebreak.network.sync.SyncContext;
import com.stonebreak.network.sync.SyncEvent;
import com.stonebreak.network.sync.SyncMode;
import com.stonebreak.network.sync.Synchronizer;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.operations.WorldConfiguration;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Sends host-modified chunks to clients so non-deterministic divergence
 * (player builds, structure-edits, anything not reproducible from the seed)
 * is reflected on every joiner.
 *
 * <p><b>Strategy (Minecraft-style per-player view-distance tracking):</b> the
 * host maintains a bounded set of chunks it has modified since session start.
 * Per connected client, we track the chunks already pushed ("loaded" on that
 * client's view). Every server tick we look at the client's current chunk
 * position and find any modified chunks in their view radius they don't yet
 * have, paced at {@link #MAX_PUSH_PER_TICK}. Chunks leaving the view are
 * <em>not</em> explicitly forgotten — the client manages its own chunk
 * lifecycle (regenerating from seed when needed), and we re-push the
 * modification snapshot if the player walks back into range.
 *
 * <p>Single-block edits ride on {@link BlockSynchronizer}; this synchronizer
 * only handles the bulk-snapshot path.
 */
public final class ChunkSynchronizer implements Synchronizer {

    /** Same as {@link WorldConfiguration#DEFAULT_RENDER_DISTANCE}; can diverge later. */
    private static final int VIEW_DISTANCE_CHUNKS = 8;

    /** Maximum chunk pushes per tick per player — caps bandwidth & main-thread cost. */
    private static final int MAX_PUSH_PER_TICK = 8;

    /**
     * Hard cap on tracked modified chunks (LRU). Eviction means a long-departed
     * chunk's edits won't be sent to future joiners — acceptable for sessions
     * with very wide exploration; the alternative is unbounded growth.
     */
    private static final int MAX_TRACKED_CHUNKS = 4096;

    /** Encoded {@code (cx, cz)} pairs of chunks the host has modified, in LRU order. */
    private final Set<Long> modifiedChunks = Collections.newSetFromMap(
            new LinkedHashMap<Long, Boolean>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
                    return size() > MAX_TRACKED_CHUNKS;
                }
            });

    /** Host-side: per-player view tracker. */
    private final Map<Integer, PlayerView> trackers = new HashMap<>();

    /** Client-side: chunk pushes that arrived before the local world finished generating. */
    private final Deque<Packet.ChunkDataS2C> pending = new ArrayDeque<>();

    @Override
    public void onSessionStart(SyncContext ctx) {
        modifiedChunks.clear();
        trackers.clear();
        pending.clear();
    }

    @Override
    public void onSessionEnd() {
        modifiedChunks.clear();
        trackers.clear();
        pending.clear();
    }

    @Override
    public void tick(float deltaTime, SyncContext ctx) {
        // Client: drain deferred chunk pushes once the local world is ready.
        if (!pending.isEmpty() && Game.getWorld() != null) {
            Iterator<Packet.ChunkDataS2C> it = pending.iterator();
            while (it.hasNext()) {
                Packet.ChunkDataS2C cd = it.next();
                Chunk chunk = Game.getWorld().getChunkAt(cd.chunkX(), cd.chunkZ());
                if (chunk == null) continue;
                Game.getWorld().installNetworkChunk(cd.chunkX(), cd.chunkZ(), cd.payload());
                it.remove();
            }
        }

        if (ctx.mode() != SyncMode.HOST) return;
        IntegratedServer srv = MultiplayerSession.getServer();
        if (srv == null) return;
        World world = Game.getWorld();
        if (world == null) return;

        // GC trackers for departed clients.
        trackers.keySet().removeIf(id -> srv.getClient(id) == null);

        for (Map.Entry<Integer, PlayerView> entry : trackers.entrySet()) {
            int playerId = entry.getKey();
            PlayerView view = entry.getValue();
            RemoteClient rc = srv.getClient(playerId);
            if (rc == null) continue;
            // Wait for the player to report a position before pushing — avoids
            // shipping the chunks around (0,0) to a player who actually spawned
            // far away.
            if (rc.getLastStateNs() == 0L && view.lastCx == Integer.MIN_VALUE) continue;

            int cx = (int) Math.floor(rc.getX() / 16.0);
            int cz = (int) Math.floor(rc.getZ() / 16.0);
            view.lastCx = cx;
            view.lastCz = cz;

            int budget = MAX_PUSH_PER_TICK;
            // Spiral-ish: nearest first by Chebyshev distance.
            outer:
            for (int r = 0; r <= VIEW_DISTANCE_CHUNKS; r++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int dx = -r; dx <= r; dx++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                        long key = packKey(cx + dx, cz + dz);
                        if (!modifiedChunks.contains(key)) continue;
                        if (view.sent.contains(key)) continue;
                        Chunk chunk = world.getChunkAt(cx + dx, cz + dz);
                        if (chunk == null) continue; // not loaded host-side
                        byte[] payload = NetworkChunkCodec.encode(chunk);
                        rc.send(new Packet.ChunkDataS2C(cx + dx, cz + dz, payload));
                        view.sent.add(key);
                        if (--budget <= 0) break outer;
                    }
                }
            }
        }
    }

    @Override
    public boolean handlesInbound(Packet packet) {
        return packet instanceof Packet.ChunkDataS2C;
    }

    @Override
    public void applyInbound(Packet packet, Integer originId, SyncContext ctx) {
        if (!(packet instanceof Packet.ChunkDataS2C cd)) return;
        World world = Game.getWorld();
        if (world == null) {
            pending.add(cd);
            return;
        }
        Chunk chunk = world.getChunkAt(cd.chunkX(), cd.chunkZ());
        if (chunk == null) {
            pending.add(cd);
            return;
        }
        world.installNetworkChunk(cd.chunkX(), cd.chunkZ(), cd.payload());
    }

    @Override
    public boolean handlesLocal(SyncEvent event) {
        return event instanceof SyncEvent.BlockChanged
            || event instanceof SyncEvent.PeerJoined;
    }

    @Override
    public void emitLocal(SyncEvent event, SyncContext ctx) {
        if (ctx.mode() != SyncMode.HOST) return;
        switch (event) {
            case SyncEvent.BlockChanged b -> {
                // Players already in view get the live BlockChange{S2C|Multi}; players
                // who walk in later get the full snapshot via tick(). All we need
                // here is to mark the chunk as modified.
                markChunkModified(
                        Math.floorDiv(b.x(), WorldConfiguration.CHUNK_SIZE),
                        Math.floorDiv(b.z(), WorldConfiguration.CHUNK_SIZE));
            }
            case SyncEvent.PeerJoined p -> trackers.computeIfAbsent(p.playerId(), id -> new PlayerView());
            default -> {}
        }
    }

    /**
     * Mark a chunk as having diverged from procedural generation, so future
     * joiners get its current state in their view-distance snapshot push.
     *
     * <p>Called both from the local-edit hook (via {@link #emitLocal}) and by
     * {@link BlockSynchronizer} after applying inbound client edits — those
     * inbound applications can't reach us via the normal {@code SyncEvent}
     * path because the SyncService's {@code applyingInbound} flag suppresses
     * re-broadcast (and would suppress this side-effect too).
     */
    public void markChunkModified(int cx, int cz) {
        modifiedChunks.add(packKey(cx, cz));
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private static long packKey(int cx, int cz) {
        return (((long) cx) << 32) | (cz & 0xFFFFFFFFL);
    }

    /** Per-player tracker for view-distance chunk pushes. */
    private static final class PlayerView {
        int lastCx = Integer.MIN_VALUE;
        int lastCz = Integer.MIN_VALUE;
        final Set<Long> sent = new HashSet<>();
    }
}
