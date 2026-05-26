package com.stonebreak.network.server.handlers;

import com.openmason.engine.net.protocol.codec.VoxelChunkCodec;
import com.stonebreak.network.packet.world.ChunkDataS2C;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.chunk.api.voxel.ChunkDataAdapter;

import java.util.HashMap;
import java.util.Map;

/**
 * Streams authoritative chunk snapshots to each client within their view distance. In the
 * two-world model the client generates <b>no</b> terrain (see the separation plan, decision
 * #1), so the server ships <b>every</b> in-view chunk the client hasn't yet received — not
 * just modified ones — generating it on demand via {@code world.getChunkAt}.
 *
 * <p>Chunks carry a server-side <b>version</b> bumped by {@link #markChunkModified} (player
 * edits, and any server mutation that routes through it). Each player records the version it
 * last received per chunk, so a changed chunk is re-streamed. This self-heals client
 * divergence for changes not covered by the per-block change feed.
 *
 * <p>Per-player "sent version" state lives on {@link ServerPlayer}, so it is cleaned up
 * automatically when a player disconnects.
 */
public final class ServerChunkHandler {

    private static final int VIEW_DISTANCE_CHUNKS = 8;
    /** One ring beyond the view is generated (not streamed) so view-edge chunks get features. */
    private static final int GEN_DISTANCE_CHUNKS = VIEW_DISTANCE_CHUNKS + 1;
    /**
     * Beyond this Chebyshev radius a player's "already sent" record is forgotten, so the chunk
     * re-streams if the player returns. MUST match {@code World.CLIENT_KEEP_RADIUS} (the client
     * unloads at the same radius) — otherwise a returning player gets holes (client dropped the
     * chunk but the server still thinks it was sent).
     */
    private static final int FORGET_DISTANCE_CHUNKS = 10;
    /**
     * Chunks streamed per remote player per tick. Kept modest because both the server encode
     * and the client decode run on the main game thread (the integrated server ticks there) —
     * a large burst causes frame hitches over the wire. 8/tick @ 20 Hz = 160 chunks/s fills a
     * view in ~2 s. See {@link #LOCAL_PUSH_PER_TICK} for the in-JVM host fast path.
     */
    private static final int MAX_PUSH_PER_TICK = 8;
    /**
     * Streaming budget for the in-JVM host player (Netty LocalChannel). Pre-Netty singleplayer
     * had no streaming throttle at all — chunks generated and rendered in a single world. With
     * the two-world model the host self-streams over an in-process channel where encode/decode
     * is an object handoff (no serialization), so we can ship a much larger burst per tick
     * without paying any wire cost. Keeps host responsiveness close to the pre-Netty feel and
     * stops a fast-flying host from outrunning its own chunk stream.
     */
    private static final int LOCAL_PUSH_PER_TICK = 256;

    /** Current version per chunk key; bumped on modification so clients re-receive it. */
    private final Map<Long, Integer> chunkVersions = new HashMap<>();

    /** Mark a chunk changed so every player re-receives its snapshot within view. */
    public void markChunkModified(int cx, int cz) {
        long key = packKey(cx, cz);
        chunkVersions.merge(key, 1, Integer::sum);
    }

    public void onSessionStart() {
        chunkVersions.clear();
    }

    public void tick(ServerWorldContext ctx) {
        World world = ctx.world();
        if (world == null) {
            return;
        }
        for (ServerPlayer sp : ctx.players()) {
            // Wait for a reported position before streaming — avoids shipping chunks around
            // (0,0) to a player who actually spawned far away.
            if (sp.lastStateNs() == 0L && sp.lastCx() == Integer.MIN_VALUE) {
                continue;
            }

            int cx = (int) Math.floor(sp.x() / 16.0);
            int cz = (int) Math.floor(sp.z() / 16.0);
            sp.setLastChunk(cx, cz);

            // Forget chunks the client has unloaded (left its keep radius) so they re-stream on
            // return, and to bound the per-player sent-set as the player explores.
            sp.forgetChunksMatching(key -> {
                int kx = (int) (key >> 32);
                int kz = (int) key;
                return Math.max(Math.abs(kx - cx), Math.abs(kz - cz)) > FORGET_DISTANCE_CHUNKS;
            });

            // Generate one ring BEYOND the streamed view so the view-edge chunks have the
            // east/south/southeast neighbors that feature population (trees/flowers) requires;
            // those border chunks are generated but never streamed.
            for (int dz = -GEN_DISTANCE_CHUNKS; dz <= GEN_DISTANCE_CHUNKS; dz++) {
                for (int dx = -GEN_DISTANCE_CHUNKS; dx <= GEN_DISTANCE_CHUNKS; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) > VIEW_DISTANCE_CHUNKS) {
                        world.getChunkAt(cx + dx, cz + dz);
                    }
                }
            }

            int budget = sp.isLocal() ? LOCAL_PUSH_PER_TICK : MAX_PUSH_PER_TICK;
            outer:
            for (int r = 0; r <= VIEW_DISTANCE_CHUNKS; r++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int dx = -r; dx <= r; dx++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                            continue;
                        }
                        long key = packKey(cx + dx, cz + dz);
                        int version = chunkVersions.getOrDefault(key, 0);
                        if (sp.sentChunkVersion(key) >= version) {
                            continue; // already has the current version
                        }
                        Chunk chunk = world.getChunkAt(cx + dx, cz + dz);
                        if (chunk == null) {
                            continue; // async gen in flight — retry next tick
                        }
                        // Don't stream a chunk until its features (trees, flowers, ...) are
                        // populated — otherwise the client receives a terrain-only snapshot and,
                        // since feature population doesn't bump the version, never gets the rest.
                        if (!chunk.areFeaturesPopulated()) {
                            continue; // not ready — retry next tick (do NOT mark sent)
                        }
                        byte[] payload = VoxelChunkCodec.encode(new ChunkDataAdapter(chunk));
                        sp.send(new ChunkDataS2C(cx + dx, cz + dz, payload), false);
                        sp.markChunkSent(key, version);
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
