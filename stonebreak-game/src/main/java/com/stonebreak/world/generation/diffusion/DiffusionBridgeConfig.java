package com.stonebreak.world.generation.diffusion;

/**
 * Java-side knobs for talking to {@code terrain-bridge}. Defaults mirror the
 * bridge's own defaults (Models/terrain-bridge/README.md) except where noted.
 *
 * {@code tileSizeBlocks} MUST equal the bridge's {@code TERRAIN_BRIDGE_TILE_SIZE}
 * — it is used to bucket world coordinates into tile keys client-side, the
 * same way {@code TERRAIN_BRIDGE_SEED} must match the bridge's pinned seed
 * (Models/terrain-bridge/README.md's "this bridge cannot verify that match itself").
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
        long tilePendingGraceMs,
        long pendingPollIntervalMs
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
                // Only has to clear one round trip: an ordinary tile, or the bridge's bounded
                // wait (TERRAIN_BRIDGE_MAX_WAIT_S, default 20 s) that ends in 503 + Retry-After
                // for a tile still generating. tilePendingGraceMs below covers the generation.
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
                // Patient budget for a bridge that keeps answering "still generating" (503 +
                // Retry-After) rather than one that isn't there at all -- separate from both
                // unreachableGraceMs above and the fast maxRetries ladder, because the bridge is
                // up and answering, just not done (a cold model's first tile takes ~30 s).
                // Costs nothing when unused since polls are cheap on both ends.
                Long.getLong("stonebreak.terrainBridge.tilePendingGraceMs", 1_800_000L),
                // Fallback poll interval, used only if a 503 arrives without a parseable
                // Retry-After header — the bridge always sends one, so this is a safety net,
                // not the number that actually paces polling in practice.
                Long.getLong("stonebreak.terrainBridge.pendingPollIntervalMs", 5_000L)
        );
    }
}
