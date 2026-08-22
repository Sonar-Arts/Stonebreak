package com.stonebreak.world;

import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.chunk.utils.WorldChunkStore;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Owns the install of chunks received from the server onto a client render world:
 * decodes the block payload, swaps the storage into the chunk slot, re-hydrates the
 * game-side metadata (snow layers, SBO block states, water levels, furnace/animated
 * block registries), restores the sky heightmap and hands the chunk to the
 * {@link ChunkMeshScheduler}.
 */
final class NetworkChunkInstaller {
    private final WorldChunkStore chunkStore;
    private final SnowLayerManager snowLayerManager;
    private final com.stonebreak.blocks.furnace.FurnaceStateRegistry furnaceRegistry;
    private final com.stonebreak.blocks.anim.AnimatedBlockRegistry animatedBlockRegistry;
    private final ChunkMeshScheduler meshScheduler;

    NetworkChunkInstaller(WorldChunkStore chunkStore,
                          SnowLayerManager snowLayerManager,
                          com.stonebreak.blocks.furnace.FurnaceStateRegistry furnaceRegistry,
                          com.stonebreak.blocks.anim.AnimatedBlockRegistry animatedBlockRegistry,
                          ChunkMeshScheduler meshScheduler) {
        this.chunkStore = chunkStore;
        this.snowLayerManager = snowLayerManager;
        this.furnaceRegistry = furnaceRegistry;
        this.animatedBlockRegistry = animatedBlockRegistry;
        this.meshScheduler = meshScheduler;
    }

    /**
     * Decode + install. Returns false when the payload could not be decoded/installed —
     * the caller should request a chunk resync, since the server has marked this chunk as sent.
     */
    boolean install(int chunkX, int chunkZ, byte[] payload, byte[] metaPayload) {
        com.openmason.engine.voxel.cco.data.palette.CcoPalettedChunkStorage decoded =
            com.stonebreak.network.client.NetworkChunkDecoder.decodeBlocks(chunkX, chunkZ, payload);
        if (decoded == null) {
            return false; // caller requests a resync — the server thinks this chunk was sent
        }
        return installDecoded(chunkX, chunkZ, decoded,
            com.stonebreak.network.client.NetworkChunkDecoder.computeSkyHeights(decoded), metaPayload);
    }

    /**
     * Install half of {@link #install}: swaps pre-decoded storage into the chunk slot and
     * applies metadata + mesh scheduling. MUST run on the main game thread. {@code heights}
     * is the worker-precomputed sky heightmap (see {@code NetworkChunkDecoder.computeSkyHeights});
     * null falls back to a main-thread rescan.
     */
    boolean installDecoded(int chunkX, int chunkZ,
            com.openmason.engine.voxel.cco.data.palette.CcoPalettedChunkStorage decoded,
            int[] heights, byte[] metaPayload) {
        if (chunkStore == null) return false;
        // Synchronous slot creation — the render-only client has no disk-load or terrain-gen,
        // so the chunk arrives ready in the same call. No async machinery, no race conditions,
        // no chance of dropping the payload because the slot "isn't ready yet".
        Chunk chunk = chunkStore.createOrGetNetworkChunkSlot(chunkX, chunkZ);
        chunk.replaceAllBlocks(decoded);
        // The bulk block install bypasses Chunk.setBlock, so stale water-layer entries from a
        // previous stream of this chunk would survive it — clear unconditionally; the meta
        // below re-hydrates the authoritative set (absence = source, per the layer invariant).
        chunk.getWaterLayer().clear();

        // Apply streamed chunk metadata: snow layer heights + per-block SBO states + water
        // flow levels. Replaces (not merges) this chunk's previous entries so a re-stream is
        // a clean resync.
        if (metaPayload != null && metaPayload.length > 0) {
            try {
                var meta = com.stonebreak.network.bridge.GameChunkMetaCodec.decode(metaPayload);
                snowLayerManager.onChunkUnloaded(chunkX, chunkZ); // clear stale entries first
                int baseX = chunkX * WorldConfiguration.CHUNK_SIZE;
                int baseZ = chunkZ * WorldConfiguration.CHUNK_SIZE;
                for (var e : meta.snowLayers().entrySet()) {
                    int key = e.getKey();
                    snowLayerManager.putRaw(
                        baseX + com.stonebreak.world.chunk.utils.LocalBlockKey.x(key),
                        com.stonebreak.world.chunk.utils.LocalBlockKey.y(key),
                        baseZ + com.stonebreak.world.chunk.utils.LocalBlockKey.z(key),
                        e.getValue());
                }
                for (var e : meta.blockStates().entrySet()) {
                    int key = e.getKey();
                    chunk.setBlockState(
                        com.stonebreak.world.chunk.utils.LocalBlockKey.x(key),
                        com.stonebreak.world.chunk.utils.LocalBlockKey.y(key),
                        com.stonebreak.world.chunk.utils.LocalBlockKey.z(key),
                        e.getValue());
                }
                for (var e : meta.waterLevels().entrySet()) {
                    int key = e.getKey();
                    chunk.getWaterLayer().set(
                        com.stonebreak.world.chunk.utils.LocalBlockKey.x(key),
                        com.stonebreak.world.chunk.utils.LocalBlockKey.y(key),
                        com.stonebreak.world.chunk.utils.LocalBlockKey.z(key),
                        e.getValue());
                }
                // Hydrate the DISPLAY furnace registry from the states just applied. The
                // chunk-load listener fired at slot creation, BEFORE this meta landed, so
                // without this an idle furnace opens empty on a joiner — and their first
                // slot edit would then overwrite the server's real contents.
                if (!meta.blockStates().isEmpty() && furnaceRegistry != null) {
                    furnaceRegistry.onChunkLoaded(chunk);
                }
                // Same re-hydration for animated blocks (doors): the load-time
                // scan saw an all-air placeholder with no states, so streamed
                // doors were never indexed — and rendered invisible.
                if (!meta.blockStates().isEmpty()) {
                    animatedBlockRegistry.onChunkLoaded(chunk);
                }
            } catch (Exception e) {
                System.err.println("[NETWORK] Failed to decode chunk meta (" + chunkX + "," + chunkZ + "): " + e.getMessage());
            }
        }

        // The chunk was an empty placeholder (all-air heightmap). Now that real blocks are in,
        // install the heightmap so sky-shadow/lighting and the mesher's Y-scan are correct —
        // precomputed on the decode worker; the fallback rescan covers direct callers.
        if (heights != null) {
            chunk.getHeightMap().populate(heights);
        } else {
            chunk.getHeightMap().recomputeAll(chunk.getOpacityProbe());
        }
        meshScheduler.onNetworkChunkInstalled(chunk, chunkX, chunkZ);
        com.stonebreak.world.chunk.utils.ChunkPipelineStats.INSTALLED.increment();
        return true;
    }
}
