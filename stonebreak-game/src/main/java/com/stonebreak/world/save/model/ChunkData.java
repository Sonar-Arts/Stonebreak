package com.stonebreak.world.save.model;

import com.openmason.engine.voxel.cco.data.CcoBlockStorage;
import com.stonebreak.world.operations.WorldConfiguration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;

/**
 * Pure data model for chunk state.
 * No serialization logic - follows SOLID principles.
 * Immutable for thread safety.
 *
 * <p>Block data is held as a {@link CcoBlockStorage} snapshot handle. The
 * snapshot chain (Chunk.createSnapshot → CcoSerializableSnapshot → here)
 * copies the paletted storage exactly once at the start; this class adopts
 * the handle without further copying.
 */
public final class ChunkData {
    private final int chunkX;
    private final int chunkZ;
    private final CcoBlockStorage blocks;
    private final LocalDateTime lastModified;
    private final boolean featuresPopulated;
    private final boolean hasEntitiesGenerated;
    private final Map<String, WaterBlockData> waterMetadata;
    private final List<EntityData> entities;
    /**
     * Sparse per-block SBO state map (1.3+). Keyed by packed local coordinates
     * ({@code LocalBlockKey}). Only blocks whose state is non-default appear
     * here — default-state blocks keep this empty for minimal save footprint.
     */
    private final Map<Integer, String> blockStates;
    /**
     * Sparse snow layer counts (save v3+). Keyed by packed local coordinates
     * ({@code LocalBlockKey}), value 1-8. Untracked snow blocks read as the
     * 1-layer default, so most snow never appears here.
     */
    private final Map<Integer, Integer> snowLayers;

    private ChunkData(Builder builder) {
        this.chunkX = builder.chunkX;
        this.chunkZ = builder.chunkZ;
        this.blocks = builder.blocks;
        this.lastModified = builder.lastModified;
        this.featuresPopulated = builder.featuresPopulated;
        this.hasEntitiesGenerated = builder.hasEntitiesGenerated;
        this.waterMetadata = builder.waterMetadata != null ? new HashMap<>(builder.waterMetadata) : new HashMap<>();
        this.entities = builder.entities != null ? new ArrayList<>(builder.entities) : new ArrayList<>();
        this.blockStates = builder.blockStates != null ? new HashMap<>(builder.blockStates) : new HashMap<>();
        this.snowLayers = builder.snowLayers != null ? new HashMap<>(builder.snowLayers) : new HashMap<>();
    }

    // Getters
    public int getChunkX() { return chunkX; }
    public int getChunkZ() { return chunkZ; }
    /** Block storage snapshot. Treat as read-only. */
    public CcoBlockStorage getBlockStorage() { return blocks; }
    public LocalDateTime getLastModified() { return lastModified; }
    public boolean isFeaturesPopulated() { return featuresPopulated; }
    public boolean hasEntitiesGenerated() { return hasEntitiesGenerated; }
    public Map<String, WaterBlockData> getWaterMetadata() { return new HashMap<>(waterMetadata); }
    public List<EntityData> getEntities() { return new ArrayList<>(entities); }
    public Map<Integer, String> getBlockStates() { return new HashMap<>(blockStates); }
    public Map<Integer, Integer> getSnowLayers() { return new HashMap<>(snowLayers); }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int chunkX;
        private int chunkZ;
        private CcoBlockStorage blocks;
        private LocalDateTime lastModified = LocalDateTime.now();
        private boolean featuresPopulated = false;
        private boolean hasEntitiesGenerated = false;
        private Map<String, WaterBlockData> waterMetadata = new HashMap<>();
        private List<EntityData> entities = new ArrayList<>();
        private Map<Integer, String> blockStates = new HashMap<>();
        private Map<Integer, Integer> snowLayers = new HashMap<>();

        public Builder chunkX(int chunkX) {
            this.chunkX = chunkX;
            return this;
        }

        public Builder chunkZ(int chunkZ) {
            this.chunkZ = chunkZ;
            return this;
        }

        /** Adopts the storage handle without copying — pass a snapshot copy. */
        public Builder blocks(CcoBlockStorage blocks) {
            this.blocks = blocks;
            return this;
        }

        public Builder lastModified(LocalDateTime lastModified) {
            this.lastModified = lastModified;
            return this;
        }

        public Builder featuresPopulated(boolean featuresPopulated) {
            this.featuresPopulated = featuresPopulated;
            return this;
        }

        public Builder hasEntitiesGenerated(boolean hasEntitiesGenerated) {
            this.hasEntitiesGenerated = hasEntitiesGenerated;
            return this;
        }

        public Builder waterMetadata(Map<String, WaterBlockData> waterMetadata) {
            this.waterMetadata = waterMetadata;
            return this;
        }

        public Builder entities(List<EntityData> entities) {
            this.entities = entities != null ? new ArrayList<>(entities) : new ArrayList<>();
            return this;
        }

        public Builder blockStates(Map<Integer, String> blockStates) {
            this.blockStates = blockStates != null ? new HashMap<>(blockStates) : new HashMap<>();
            return this;
        }

        public Builder snowLayers(Map<Integer, Integer> snowLayers) {
            this.snowLayers = snowLayers != null ? new HashMap<>(snowLayers) : new HashMap<>();
            return this;
        }

        public ChunkData build() {
            if (blocks == null || blocks.getSizeX() != 16 || blocks.getSizeY() != WorldConfiguration.WORLD_HEIGHT || blocks.getSizeZ() != 16) {
                throw new IllegalStateException("Invalid chunk block storage dimensions");
            }
            return new ChunkData(this);
        }
    }

    /**
     * Water block metadata for saving depth and falling state.
     */
    public record WaterBlockData(int level, boolean falling) {}
}
