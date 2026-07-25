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
        long unreachableGraceMs
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
                // A bit over the bridge's own default 30s upstream timeout (TERRAIN_BRIDGE_UPSTREAM_TIMEOUT_S)
                // so the bridge's 502 arrives before Java's own request times out.
                Long.getLong("stonebreak.terrainBridge.requestTimeoutMs", 35_000L),
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
                Long.getLong("stonebreak.terrainBridge.unreachableGraceMs", 60_000L)
        );
    }
}
