package com.stonebreak.world.chunk.api.commonChunkOperations.data;

import com.openmason.engine.voxel.cco.data.CcoBlockStorage;
import com.stonebreak.world.save.model.ChunkData;
import com.stonebreak.world.save.model.EntityData;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable snapshot of chunk data for serialization.
 * Designed for efficient conversion to save system's ChunkData model.
 *
 * <p>Holds an already-copied paletted block storage (the snapshot creator copies
 * once, cheaply); no further deep copies happen anywhere in the save chain.
 * Thread-safe through immutability.
 *
 * WATER METADATA INTEGRATION:
 * Water flow levels are stored as part of the chunk snapshot to ensure they persist correctly.
 * This follows the CCO principle of keeping all chunk state together.
 *
 * ENTITY DATA INTEGRATION:
 * Entity data (block drops, cows, etc.) is stored as part of the chunk snapshot.
 * Entities are serialized when chunks are saved and restored when chunks are loaded.
 */
public final class CcoSerializableSnapshot {
    private final int chunkX;
    private final int chunkZ;
    private final CcoBlockStorage blocks;  // Snapshot copy owned exclusively by this object
    private final LocalDateTime lastModified;
    private final boolean featuresPopulated;
    private final boolean hasEntitiesGenerated;  // Whether entities were spawned for this chunk
    private final Map<String, ChunkData.WaterBlockData> waterMetadata;  // Water flow levels (non-source only)
    private final List<EntityData> entities;  // Entity data for this chunk
    /**
     * Sparse per-block SBO state map (1.3+), keyed by packed local coordinates
     * (LocalBlockKey); only blocks with non-default state appear here.
     */
    private final Map<Integer, String> blockStates;
    /** Sparse snow layer counts (save v3+), keyed by packed local coordinates, value 1-8. */
    private final Map<Integer, Integer> snowLayers;

    /**
     * Creates a serializable snapshot.
     *
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @param blocks Block storage snapshot — caller must pass a copy it no longer mutates
     * @param lastModified Last modification timestamp
     * @param featuresPopulated Whether features are populated
     * @param hasEntitiesGenerated Whether entities were spawned for this chunk
     * @param waterMetadata Water flow level metadata (defensive copy made)
     * @param entities Entity data for this chunk (defensive copy made)
     * @param blockStates Per-block SBO state map (defensive copy made)
     * @param snowLayers Snow layer counts, packed local keys (defensive copy made)
     */
    public CcoSerializableSnapshot(int chunkX, int chunkZ, CcoBlockStorage blocks,
                                   LocalDateTime lastModified, boolean featuresPopulated,
                                   boolean hasEntitiesGenerated,
                                   Map<String, ChunkData.WaterBlockData> waterMetadata,
                                   List<EntityData> entities,
                                   Map<Integer, String> blockStates,
                                   Map<Integer, Integer> snowLayers) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.blocks = Objects.requireNonNull(blocks, "blocks cannot be null");
        this.lastModified = Objects.requireNonNull(lastModified, "lastModified cannot be null");
        this.featuresPopulated = featuresPopulated;
        this.hasEntitiesGenerated = hasEntitiesGenerated;
        this.waterMetadata = waterMetadata != null ? new HashMap<>(waterMetadata) : new HashMap<>();
        this.entities = entities != null ? new ArrayList<>(entities) : new ArrayList<>();
        this.blockStates = blockStates != null ? new HashMap<>(blockStates) : new HashMap<>();
        this.snowLayers = snowLayers != null ? new HashMap<>(snowLayers) : new HashMap<>();
    }

    /**
     * Gets whether entities were spawned for this chunk.
     */
    public boolean hasEntitiesGenerated() {
        return hasEntitiesGenerated;
    }

    /**
     * Converts this snapshot to the save system's ChunkData model.
     * Zero-copy: ChunkData adopts this snapshot's storage handle.
     *
     * @return ChunkData ready for serialization
     */
    public ChunkData toChunkData() {
        return ChunkData.builder()
                .chunkX(chunkX)
                .chunkZ(chunkZ)
                .blocks(blocks)
                .lastModified(lastModified)
                .featuresPopulated(featuresPopulated)
                .hasEntitiesGenerated(hasEntitiesGenerated)  // Include entity generation flag
                .waterMetadata(waterMetadata)  // CCO-integrated water metadata
                .entities(entities)  // CCO-integrated entity data
                .blockStates(blockStates)  // SBO 1.3 per-block states
                .snowLayers(snowLayers)    // v3 snow layer counts
                .build();
    }

    public int getChunkX() {
        return chunkX;
    }

    public int getChunkZ() {
        return chunkZ;
    }

    /** Block storage snapshot. Treat as read-only. */
    public CcoBlockStorage getBlockStorage() {
        return blocks;
    }

    public LocalDateTime getLastModified() {
        return lastModified;
    }

    public boolean isFeaturesPopulated() {
        return featuresPopulated;
    }

    public Map<String, ChunkData.WaterBlockData> getWaterMetadata() {
        return Collections.unmodifiableMap(waterMetadata);
    }

    public List<EntityData> getEntities() {
        return Collections.unmodifiableList(entities);
    }

    /** Per-block SBO state map (1.3+), packed-coordinate keys. Unmodifiable view. */
    public Map<Integer, String> getBlockStates() {
        return Collections.unmodifiableMap(blockStates);
    }

    /** Snow layer counts (v3+), packed-coordinate keys. Unmodifiable view. */
    public Map<Integer, Integer> getSnowLayers() {
        return Collections.unmodifiableMap(snowLayers);
    }

    @Override
    public String toString() {
        return String.format("CcoSerializableSnapshot{pos=(%d,%d), features=%s, modified=%s, entities=%d}",
                chunkX, chunkZ, featuresPopulated, lastModified, entities.size());
    }
}
