package com.stonebreak.world.chunk;

import com.openmason.engine.voxel.cco.data.CcoBlockStorage;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.api.commonChunkOperations.data.CcoSerializableSnapshot;
import com.stonebreak.world.chunk.utils.LocalBlockKey;
import com.stonebreak.world.save.model.ChunkData;
import com.stonebreak.world.save.model.EntityData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns the save-format conversion of a chunk's non-block payload: water flow
 * state ({@link ChunkWaterLayer} ⇄ {@link ChunkData.WaterBlockData} map),
 * entities (world {@link EntityManager} ⇄ {@link EntityData} list) and snow
 * layer counts (world {@code SnowLayerManager} ⇄ packed local-key map).
 * Stateless; {@link Chunk#createSnapshot} and {@link Chunk#loadFromSnapshot}
 * compose these pieces around the block storage and metadata they own.
 */
final class ChunkSaveCodec {

    private static final Logger logger = Logger.getLogger(Chunk.class.getName());

    private ChunkSaveCodec() {
    }

    // ===== Snapshot (save) =====

    /**
     * Extracts water metadata from the chunk's own water layer. Only
     * non-source (flowing/falling) cells exist there; sources re-derive
     * from the block array on load. Falling persists as (level 1, true).
     *
     * @param blocksCopy the atomic block copy the snapshot will carry
     */
    static Map<String, ChunkData.WaterBlockData> collectWaterMetadata(ChunkWaterLayer waterLayer,
                                                                      CcoBlockStorage blocksCopy) {
        Map<String, ChunkData.WaterBlockData> waterMetadata = new HashMap<>();
        waterLayer.forEach((localX, y, localZ, value) -> {
            // Guard against racing the sim: only persist cells whose block
            // (in this atomic copy) is still water.
            if (blocksCopy.get(localX, y, localZ) == BlockType.WATER) {
                boolean falling = value == ChunkWaterLayer.FALLING;
                waterMetadata.put(localX + "," + y + "," + localZ,
                    new ChunkData.WaterBlockData(
                        falling ? 1 : value,
                        falling
                    ));
            }
        });
        return waterMetadata;
    }

    /**
     * Extracts entities in this chunk from the OWNING world's EntityManager. Saves run on the
     * authoritative server, whose headless world holds the real (non-shadow) mobs; the Game
     * singleton would resolve to the CLIENT manager (network shadows), which EntitySerializer
     * skips — persisting zero mobs. See the two-world "Game.* resolves to CLIENT" pitfall.
     */
    static List<EntityData> collectEntities(World world, int x, int z) {
        List<EntityData> entities = new ArrayList<>();
        if (world != null && world.getEntityManager() != null) {
            entities = world.getEntityManager().getEntitiesInChunk(x, z);
            logger.log(Level.FINE, String.format(
                "[ENTITY-SAVE] Chunk (%d,%d): Saving %d entities",
                x, z, entities.size()
            ));
        }
        return entities;
    }

    /**
     * Gathers this chunk's snow layer counts (sparse; 1-layer defaults are tracked only
     * if explicitly set). Persisted from v3 so stacked snow survives reloads.
     */
    static Map<Integer, Integer> collectSnowLayers(World world, int x, int z) {
        Map<Integer, Integer> snowLayers = new HashMap<>();
        if (world != null && world.getSnowLayerManager() != null) {
            world.getSnowLayerManager().forEachInChunk(x, z, (worldX, y, worldZ, layers) -> {
                int localX = worldX - x * 16;
                int localZ = worldZ - z * 16;
                snowLayers.put(LocalBlockKey.pack(localX, y, localZ), layers);
            });
        }
        return snowLayers;
    }

    // ===== Restore (load) =====

    /** Restores snow layer counts (v3+). Empty for older saves — snow reads as 1 layer. */
    static void restoreSnowLayers(CcoSerializableSnapshot snapshot, World world) {
        if (world != null && world.getSnowLayerManager() != null
                && !snapshot.getSnowLayers().isEmpty()) {
            var snow = world.getSnowLayerManager();
            int baseX = snapshot.getChunkX() * 16;
            int baseZ = snapshot.getChunkZ() * 16;
            for (var e : snapshot.getSnowLayers().entrySet()) {
                int key = e.getKey();
                snow.putRaw(
                    baseX + LocalBlockKey.x(key),
                    LocalBlockKey.y(key),
                    baseZ + LocalBlockKey.z(key),
                    e.getValue());
            }
        }
    }

    /**
     * Hydrates the chunk's water layer from the snapshot's water metadata,
     * replacing any existing flow state.
     */
    static void restoreWaterLayer(CcoSerializableSnapshot snapshot, ChunkWaterLayer waterLayer) {
        waterLayer.clear();
        for (var entry : snapshot.getWaterMetadata().entrySet()) {
            String[] coords = entry.getKey().split(",");
            int localX = Integer.parseInt(coords[0]);
            int y = Integer.parseInt(coords[1]);
            int localZ = Integer.parseInt(coords[2]);
            var data = entry.getValue();
            int value = data.falling()
                ? ChunkWaterLayer.FALLING
                : Math.min(ChunkWaterLayer.MAX_FLOW_LEVEL, Math.max(0, data.level()));
            if (value > 0) {
                waterLayer.set(localX, y, localZ, value);
            }
        }
    }

    /**
     * Loads entities from the snapshot into THIS world's entity manager. Critically, prefer the
     * world's own manager over the Game singleton: during server world-load the singleton
     * points at the client's manager (or, just after a world switch, the previous session's
     * terminated one), which would reject the deserialization task.
     */
    static void restoreEntities(CcoSerializableSnapshot snapshot, World world) {
        if (world != null && !snapshot.getEntities().isEmpty()) {
            EntityManager em = world.getEntityManager();
            if (em == null) {
                Game game = Game.getInstance();
                em = (game != null) ? game.getEntityManager() : null;
            }
            if (em != null) {
                logger.log(Level.FINE, String.format(
                    "[ENTITY-LOAD] Chunk (%d,%d): Loading %d entities",
                    snapshot.getChunkX(), snapshot.getChunkZ(), snapshot.getEntities().size()
                ));
                em.loadEntitiesForChunk(snapshot.getEntities(), snapshot.getChunkX(), snapshot.getChunkZ());
            }
        }
    }
}
