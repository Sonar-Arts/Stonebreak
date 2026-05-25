package com.stonebreak.network.server.handlers;

import com.openmason.engine.net.protocol.codec.VoxelChunkCodec;
import com.stonebreak.network.packet.world.ChunkDataS2C;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.chunk.api.voxel.ChunkDataAdapter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Pushes host-modified chunks to each client within their view distance — the authoritative
 * successor of the old {@code ChunkSynchronizer} HOST path. The host tracks a bounded LRU
 * set of chunks that have diverged from procedural generation; each tick it ships any such
 * chunks inside a player's radius they haven't yet received, nearest-first and paced.
 *
 * <p>The per-player "already sent" set now lives on {@link ServerPlayer}, so trackers are
 * cleaned up automatically when a player disconnects.
 */
public final class ServerChunkHandler {

    private static final int VIEW_DISTANCE_CHUNKS = 8;
    private static final int MAX_PUSH_PER_TICK = 8;
    private static final int MAX_TRACKED_CHUNKS = 4096;

    /** Encoded {@code (cx, cz)} of chunks modified since session start, in LRU order. */
    private final Set<Long> modifiedChunks = Collections.newSetFromMap(
        new LinkedHashMap<Long, Boolean>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
                return size() > MAX_TRACKED_CHUNKS;
            }
        });

    /** Mark a chunk as diverged so future joiners get its snapshot in their view push. */
    public void markChunkModified(int cx, int cz) {
        modifiedChunks.add(packKey(cx, cz));
    }

    public void onSessionStart() {
        modifiedChunks.clear();
    }

    public void tick(ServerWorldContext ctx) {
        World world = ctx.world();
        if (world == null) {
            return;
        }
        for (ServerPlayer sp : ctx.players()) {
            // Wait for a reported position before pushing — avoids shipping chunks around
            // (0,0) to a player who actually spawned far away.
            if (sp.lastStateNs() == 0L && sp.lastCx() == Integer.MIN_VALUE) {
                continue;
            }

            int cx = (int) Math.floor(sp.x() / 16.0);
            int cz = (int) Math.floor(sp.z() / 16.0);
            sp.setLastChunk(cx, cz);

            int budget = MAX_PUSH_PER_TICK;
            outer:
            for (int r = 0; r <= VIEW_DISTANCE_CHUNKS; r++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int dx = -r; dx <= r; dx++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                            continue;
                        }
                        long key = packKey(cx + dx, cz + dz);
                        if (!modifiedChunks.contains(key) || sp.hasSentChunk(key)) {
                            continue;
                        }
                        Chunk chunk = world.getChunkAt(cx + dx, cz + dz);
                        if (chunk == null) {
                            continue; // not loaded host-side
                        }
                        byte[] payload = VoxelChunkCodec.encode(new ChunkDataAdapter(chunk));
                        sp.send(new ChunkDataS2C(cx + dx, cz + dz, payload), false);
                        sp.markChunkSent(key);
                        if (--budget <= 0) {
                            break outer;
                        }
                    }
                }
            }
        }
    }

    private static long packKey(int cx, int cz) {
        return (((long) cx) << 32) | (cz & 0xFFFFFFFFL);
    }
}
