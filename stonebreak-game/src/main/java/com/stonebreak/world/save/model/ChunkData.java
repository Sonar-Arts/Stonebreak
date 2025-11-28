package com.stonebreak.world.save.model;

import com.stonebreak.blocks.BlockType;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;

/**
 * Pure data model for chunk state.
 * No serialization logic - follows SOLID principles.
 * Immutable for thread safety.
 */
public final class ChunkData {
    private final int chunkX;
    private final int chunkZ;
    private final BlockType[][][] blocks;
    private final LocalDateTime lastModified;
    private final boolean featuresPopulated;
    private final boolean hasEntitiesGenerated;
    private final Map<Long, WaterBlockData> waterMetadata;  // Long keys for performance (packed coords)
    private final List<EntityData> entities;

    private ChunkData(Builder builder) {
        this.chunkX = builder.chunkX;
        this.chunkZ = builder.chunkZ;
        this.blocks = deepCopyBlocks(builder.blocks);
        this.lastModified = builder.lastModified;
        this.featuresPopulated = builder.featuresPopulated;
        this.hasEntitiesGenerated = builder.hasEntitiesGenerated;
        this.waterMetadata = builder.waterMetadata != null ? new HashMap<>(builder.waterMetadata) : new HashMap<>();
        this.entities = builder.entities != null ? new ArrayList<>(builder.entities) : new ArrayList<>();
    }

    // Getters - return defensive copies for mutable objects
    public int getChunkX() { return chunkX; }
    public int getChunkZ() { return chunkZ; }
    public BlockType[][][] getBlocks() { return deepCopyBlocks(blocks); }
    public LocalDateTime getLastModified() { return lastModified; }
    public boolean isFeaturesPopulated() { return featuresPopulated; }
    public boolean hasEntitiesGenerated() { return hasEntitiesGenerated; }
    public Map<Long, WaterBlockData> getWaterMetadata() { return new HashMap<>(waterMetadata); }
    public List<EntityData> getEntities() { return new ArrayList<>(entities); }

    /**
     * Deep copies block array for immutability.
     */
    private static BlockType[][][] deepCopyBlocks(BlockType[][][] source) {
        if (source == null) return null;
        BlockType[][][] copy = new BlockType[16][256][16];
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 256; y++) {
                System.arraycopy(source[x][y], 0, copy[x][y], 0, 16);
            }
        }
        return copy;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int chunkX;
        private int chunkZ;
        private BlockType[][][] blocks;
        private LocalDateTime lastModified = LocalDateTime.now();
        private boolean featuresPopulated = false;
        private boolean hasEntitiesGenerated = false;
        private Map<Long, WaterBlockData> waterMetadata = new HashMap<>();  // Long keys for performance
        private List<EntityData> entities = new ArrayList<>();

        public Builder chunkX(int chunkX) {
            this.chunkX = chunkX;
            return this;
        }

        public Builder chunkZ(int chunkZ) {
            this.chunkZ = chunkZ;
            return this;
        }

        public Builder blocks(BlockType[][][] blocks) {
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

        public Builder waterMetadata(Map<Long, WaterBlockData> waterMetadata) {
            this.waterMetadata = waterMetadata;
            return this;
        }

        public Builder entities(List<EntityData> entities) {
            this.entities = entities != null ? new ArrayList<>(entities) : new ArrayList<>();
            return this;
        }

        public ChunkData build() {
            if (blocks == null || blocks.length != 16 || blocks[0].length != 256 || blocks[0][0].length != 16) {
                throw new IllegalStateException("Invalid chunk block array dimensions");
            }
            return new ChunkData(this);
        }
    }

    /**
     * Water block metadata for saving depth and falling state.
     */
    public record WaterBlockData(int level, boolean falling) {}
}
