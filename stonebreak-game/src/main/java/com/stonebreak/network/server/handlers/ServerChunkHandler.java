package com.stonebreak.network.server.handlers;

import com.openmason.engine.net.protocol.codec.VoxelChunkCodec;
import com.openmason.engine.util.LongIntHashMap;
import com.stonebreak.network.packet.world.ChunkDataS2C;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.chunk.api.voxel.ChunkDataAdapter;

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

    // Per-player view distance comes from the client's render-distance setting
    // (ViewDistanceC2S → ServerPlayer.viewDistanceChunks, clamped server-side).
    // Derived radii, per player:
    //   view   = sp.viewDistanceChunks()  — chunks streamed
    //   gen    = view + 1                 — one ring beyond view is generated (not streamed)
    //                                       so view-edge chunks get features
    //   forget = view + 2                 — beyond this the "already sent" record is dropped
    //                                       so the chunk re-streams on return. MUST match the
    //                                       client's keep radius (World.clientKeepRadius() =
    //                                       renderDistance + 2) or a returning player gets
    //                                       holes (client dropped the chunk but the server
    //                                       still thinks it was sent).
    /**
     * Chunks streamed per remote player per tick. Kept modest because the server encode runs
     * on the main game thread (the integrated server ticks there) and the wire needs
     * backpressure. 8/tick @ 20 Hz = 160 chunks/s fills a view in ~2 s.
     */
    private static final int MAX_PUSH_PER_TICK = 8;
    /**
     * Budget for the LOCAL (host) player. The LocalChannel is an in-JVM object handoff —
     * no real wire to protect — and the wire-rate throttle was the dominant cause of slow
     * chunk pop-in for the host (~2 s to fill a view). The client-side install is cheap
     * enough to absorb this now: decode lands in a detached paletted storage and installs
     * with one section copy + one heightmap recompute (World.installNetworkChunk).
     * 64/tick @ 20 Hz fills the default view (r=8, 289 chunks) in ~0.25 s and the
     * maximum view (r=24, 2401 chunks) in ~1.9 s.
     */
    private static final int LOCAL_PUSH_PER_TICK = 64;

    /** Current version per chunk key; bumped on modification so clients re-receive it.
     *  Primitive-keyed: the view scan probes this per ring cell, and a boxed
     *  {@code Map<Long, Integer>} allocated a Long per probe. */
    private final LongIntHashMap chunkVersions = new LongIntHashMap();

    /** Set when any chunk version bumps; the next tick re-arms every player's view scan. */
    private boolean versionsDirty = false;

    /** Mark a chunk changed so every player re-receives its snapshot within view. */
    public void markChunkModified(int cx, int cz) {
        long key = packKey(cx, cz);
        chunkVersions.put(key, chunkVersions.get(key, 0) + 1);
        versionsDirty = true;
    }

    public void onSessionStart() {
        chunkVersions.clear();
        versionsDirty = true;
    }

    public void tick(ServerWorldContext ctx) {
        World world = ctx.world();
        if (world == null) {
            return;
        }
        // A version bump anywhere re-arms every player's scan once; the scan itself decides
        // per player whether the bumped chunk is in view (the per-cell version check).
        boolean versionsBumped = versionsDirty;
        versionsDirty = false;

        for (ServerPlayer sp : ctx.players()) {
            // Wait for a reported position before streaming — avoids shipping chunks around
            // (0,0) to a player who actually spawned far away.
            if (sp.lastStateNs() == 0L && sp.lastCx() == Integer.MIN_VALUE) {
                continue;
            }

            int cx = (int) Math.floor(sp.x() / 16.0);
            int cz = (int) Math.floor(sp.z() / 16.0);
            sp.setLastChunk(cx, cz); // marks the scan pending on a boundary crossing
            if (versionsBumped) {
                sp.markViewScanPending();
            }

            // Steady state for a stationary player with a fully-streamed view: skip the whole
            // O(view²) ring walk. The flag re-arms on movement, view-distance change, version
            // bump, or join, and stays set while any in-view chunk is still pending below.
            if (!sp.viewScanPending()) {
                continue;
            }

            int viewDistance = sp.viewDistanceChunks();
            int genDistance = viewDistance + 1;
            int forgetDistance = viewDistance + 2;

            // Forget chunks the client has unloaded (left its keep radius) so they re-stream on
            // return, and to bound the per-player sent-set as the player explores.
            sp.forgetChunksMatching(key -> {
                int kx = (int) (key >> 32);
                int kz = (int) key;
                return Math.max(Math.abs(kx - cx), Math.abs(kz - cz)) > forgetDistance;
            });

            // Generate one ring BEYOND the streamed view so the view-edge chunks have the
            // east/south/southeast neighbors that feature population (trees/flowers) requires;
            // those border chunks are generated but never streamed.
            for (int dz = -genDistance; dz <= genDistance; dz++) {
                for (int dx = -genDistance; dx <= genDistance; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) > viewDistance) {
                        world.getChunkAt(cx + dx, cz + dz);
                    }
                }
            }

            // True only if this scan confirmed every in-view chunk is sent at its current
            // version — any deferral (gen in flight, features pending, budget exhausted)
            // keeps the scan armed for next tick.
            boolean viewComplete = true;
            int budget = sp.isLocal() ? LOCAL_PUSH_PER_TICK : MAX_PUSH_PER_TICK;
            outer:
            for (int r = 0; r <= viewDistance; r++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int dx = -r; dx <= r; dx++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                            continue;
                        }
                        long key = packKey(cx + dx, cz + dz);
                        int version = chunkVersions.get(key, 0);
                        if (sp.sentChunkVersion(key) >= version) {
                            continue; // already has the current version
                        }
                        Chunk chunk = world.getChunkAt(cx + dx, cz + dz);
                        if (chunk == null) {
                            viewComplete = false;
                            continue; // async gen in flight — retry next tick
                        }
                        // Don't stream a chunk until its features (trees, flowers, ...) are
                        // populated — otherwise the client receives a terrain-only snapshot and,
                        // since feature population doesn't bump the version, never gets the rest.
                        if (!chunk.areFeaturesPopulated()) {
                            viewComplete = false;
                            continue; // not ready — retry next tick (do NOT mark sent)
                        }
                        byte[] payload = VoxelChunkCodec.encode(new ChunkDataAdapter(chunk));
                        sp.send(new ChunkDataS2C(cx + dx, cz + dz, payload), false);
                        sp.markChunkSent(key, version);
                        if (--budget <= 0) {
                            viewComplete = false; // out of budget — outer rings unverified
                            break outer;
                        }
                    }
                }
            }
            if (viewComplete) {
                sp.clearViewScanPending();
            }
        }
    }

    private static long packKey(int cx, int cz) {
        return (((long) cx) << 32) | (cz & 0xFFFFFFFFL);
    }
}
