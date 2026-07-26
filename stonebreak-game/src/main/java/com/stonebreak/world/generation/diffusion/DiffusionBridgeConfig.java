package com.stonebreak.world.generation.diffusion;

/**
 * Java-side knobs for talking to {@code terrain-bridge}. Defaults mirror the
 * bridge's own defaults (terrain-bridge/README.md) except where noted.
 *
 * {@code tileSizeBlocks} MUST equal the bridge's {@code TERRAIN_BRIDGE_TILE_SIZE}
 * — it is used to bucket world coordinates into tile keys client-side, the
 * same way {@code TERRAIN_BRIDGE_SEED} must match the bridge's pinned seed
 * (terrain-bridge/README.md's "this bridge cannot verify that match itself").
 */
public record DiffusionBridgeConfig(
        String baseUrl,
        int tileSizeBlocks,
        long connectTimeoutMs,
        long requestTimeoutMs,
        int maxRetries,
        long initialBackoffMs,
        long maxBackoffMs,
        int maxCachedTiles,
        long unreachableGraceMs,
        long hydrologySolveGraceMs,
        long solvePollIntervalMs
) {
    public static DiffusionBridgeConfig fromSystemProperties() {
        return new DiffusionBridgeConfig(
                // 8180, not the bridge's own doc default of 8080: this machine already has an
                // unrelated llama-server bound to 8080 (see Phase 1 spike notes), and
                // TerrainServiceProcessManager reads this same property to know which port to
                // launch uvicorn on, so the two must never be set independently.
                System.getProperty("stonebreak.terrainBridge.url", "http://localhost:8180"),
                Integer.getInteger("stonebreak.terrainBridge.tileSizeBlocks", 256),
                Long.getLong("stonebreak.terrainBridge.connectTimeoutMs", 5_000L),
                // Was 35 s, then 300 s while a single request had to cover an entire cold
                // solve end to end — and even 300 s didn't cover the worst case (an L1 tile
                // whose halo straddles four unsolved L0 regions, ~1160 s after the section 18
                // halo widening). Rivers and lakes plan.md section 19 replaces "one request
                // covers the whole solve" with polling: the bridge itself now bounds how long
                // it will hold `/generate_heightmap` open on an unfinished tile
                // (`TERRAIN_BRIDGE_MAX_WAIT_S`, default 20 s) and answers 503 + `Retry-After`
                // instead of hanging, while the job keeps running regardless. This timeout only
                // has to clear one such round trip — an ordinary tile (about a second) or one
                // bounded wait-then-503 — with margin for network and queueing delay, not the
                // solve itself. `hydrologySolveGraceMs` below is what covers the solve.
                Long.getLong("stonebreak.terrainBridge.requestTimeoutMs", 30_000L),
                Integer.getInteger("stonebreak.terrainBridge.maxRetries", 3),
                Long.getLong("stonebreak.terrainBridge.initialBackoffMs", 250L),
                Long.getLong("stonebreak.terrainBridge.maxBackoffMs", 4_000L),
                Integer.getInteger("stonebreak.terrainBridge.maxCachedTiles", 64),
                // Separate, far more patient budget for "nothing is listening on the port at all",
                // which in practice means TerrainServiceProcessManager is mid-restart: it stops both
                // processes and the upstream one needs seconds to reload the model before it binds
                // again. The normal maxRetries/backoff ladder spans under two seconds, so without
                // this a restart turns every request in flight into a hard TerrainBridgeException.
                // 60 s covers a restart with wide margin while still failing eventually when the
                // services are genuinely absent (a fetch is not allowed to hang a chunk worker
                // forever). See DiffusionTerrainClient.attemptFetch.
                Long.getLong("stonebreak.terrainBridge.unreachableGraceMs", 60_000L),
                // Patient budget for a bridge that keeps answering "still solving" (503 +
                // Retry-After) rather than one that isn't there at all — separate from both
                // unreachableGraceMs above and the fast maxRetries ladder, because neither fits:
                // the bridge is up and answering, just not done, and a normal transient-error
                // retry (a couple of seconds) would give up long before a cold L0 region (up to
                // ~290 s) or the worst-case four-region spawn tile (~1160 s projected, section
                // 18.2) finishes. 30 minutes is comfortable margin over that projection; it costs
                // nothing when unused since polls are cheap on both ends (plan section 19).
                Long.getLong("stonebreak.terrainBridge.hydrologySolveGraceMs", 1_800_000L),
                // Fallback poll interval, used only if a 503 arrives without a parseable
                // Retry-After header — the bridge always sends one, so this is a safety net,
                // not the number that actually paces polling in practice.
                Long.getLong("stonebreak.terrainBridge.solvePollIntervalMs", 5_000L)
        );
    }
}
