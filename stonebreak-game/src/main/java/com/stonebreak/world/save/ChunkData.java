package com.stonebreak.world.save;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.operations.WorldConfiguration;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Serializable representation of chunk data for world saves.
 * Contains block data, modification tracking, and chunk metadata.
 */
public class ChunkData {
    
    // Chunk position
    @JsonProperty("chunkX")
    private int chunkX;
    
    @JsonProperty("chunkZ")
    private int chunkZ;
    
    // Block data - using compressed format for efficiency
    @JsonProperty("blocks")
    private List<BlockEntry> blocks;
    
    // Modification tracking
    @JsonProperty("isDirty")
    private boolean isDirty;
    
    @JsonProperty("lastModified")
    private LocalDateTime lastModified;
    
    @JsonProperty("generatedByPlayer")
    private boolean generatedByPlayer;
    
    // Features populated status
    @JsonProperty("featuresPopulated")
    private boolean featuresPopulated;
    
    // Chunk metadata
    @JsonProperty("version")
    private int version;
    
    @JsonProperty("compressionType")
    private String compressionType;
    
    /**
     * Default constructor for Jackson deserialization.
     */
    @SuppressWarnings("unused") // Used by Jackson via reflection
    public ChunkData() {
        this.blocks = new ArrayList<>();
        this.isDirty = false;
        this.generatedByPlayer = false;
        this.featuresPopulated = false;
        this.version = 1;
        this.compressionType = "RLE"; // Run-Length Encoding for sparse data
    }
    
    /**
     * Creates ChunkData from a Chunk instance.
     */
    public ChunkData(Chunk chunk) {
        this.chunkX = chunk.getChunkX();
        this.chunkZ = chunk.getChunkZ();
        this.blocks = new ArrayList<>();
        this.isDirty = chunk.isDirty();
        this.lastModified = chunk.getLastModified();
        this.generatedByPlayer = chunk.isGeneratedByPlayer();
        this.featuresPopulated = chunk.isFeaturesPopulated();
        this.version = 1;
        this.compressionType = "RLE";
        
        // Convert block data using Run-Length Encoding for efficiency
        compressBlockData(chunk);
    }
    
    /**
     * Compresses block data using Run-Length Encoding.
     * This significantly reduces file size for chunks with large areas of the same block.
     */
    private void compressBlockData(Chunk chunk) {
        BlockType currentBlock = null;
        int runLength = 0;
        
        for (int y = 0; y < WorldConfiguration.WORLD_HEIGHT; y++) {
            for (int x = 0; x < WorldConfiguration.CHUNK_SIZE; x++) {
                for (int z = 0; z < WorldConfiguration.CHUNK_SIZE; z++) {
                    BlockType block = chunk.getBlock(x, y, z);
                    
                    if (block == currentBlock) {
                        // Continue current run
                        runLength++;
                    } else {
                        // End current run (if any) and start new one
                        if (currentBlock != null) {
                            blocks.add(new BlockEntry(currentBlock.getId(), runLength));
                        }
                        currentBlock = block;
                        runLength = 1;
                    }
                }
            }
        }
        
        // Add the final run
        if (currentBlock != null) {
            blocks.add(new BlockEntry(currentBlock.getId(), runLength));
        }
    }
    
    /**
     * Applies this chunk data to a Chunk instance.
     */
    public void applyToChunk(Chunk chunk) {
        if (chunk.getChunkX() != chunkX || chunk.getChunkZ() != chunkZ) {
            throw new IllegalArgumentException("Chunk position mismatch: expected (" + chunkX + "," + chunkZ + 
                                             ") but got (" + chunk.getChunkX() + "," + chunk.getChunkZ() + ")");
        }
        
        // Decompress and apply block data
        decompressBlockData(chunk);
        
        // Apply metadata
        chunk.setDirty(isDirty);
        chunk.setLastModified(lastModified);
        chunk.setGeneratedByPlayer(generatedByPlayer);
        chunk.setFeaturesPopulated(featuresPopulated);
    }
    
    /**
     * Decompresses Run-Length Encoded block data back to chunk.
     */
    private void decompressBlockData(Chunk chunk) {
        int position = 0;
        
        for (BlockEntry entry : blocks) {
            BlockType blockType = BlockType.getById(entry.getBlockId());
            if (blockType == null) {
                blockType = BlockType.AIR; // Fallback for unknown block types
            }
            
            // Place blocks for the run length
            for (int i = 0; i < entry.getRunLength(); i++) {
                if (position >= WorldConfiguration.CHUNK_SIZE * WorldConfiguration.WORLD_HEIGHT * WorldConfiguration.CHUNK_SIZE) {
                    System.err.println("Warning: Chunk data overflow at position " + position + 
                                     " for chunk (" + chunkX + "," + chunkZ + ")");
                    return;
                }
                
                // Convert 1D position back to 3D coordinates (Y-major order)
                int y = position / (WorldConfiguration.CHUNK_SIZE * WorldConfiguration.CHUNK_SIZE);
                int temp = position % (WorldConfiguration.CHUNK_SIZE * WorldConfiguration.CHUNK_SIZE);
                int x = temp / WorldConfiguration.CHUNK_SIZE;
                int z = temp % WorldConfiguration.CHUNK_SIZE;
                
                chunk.setBlock(x, y, z, blockType);
                position++;
            }
        }
    }
    
    /**
     * Checks if this chunk data represents a mostly empty chunk (mostly air).
     * Used to determine if compression is beneficial.
     */
    public boolean isSparse() {
        int airBlocks = 0;
        int totalBlocks = 0;
        
        for (BlockEntry entry : blocks) {
            if (entry.getBlockId() == BlockType.AIR.getId()) {
                airBlocks += entry.getRunLength();
            }
            totalBlocks += entry.getRunLength();
        }
        
        return airBlocks > (totalBlocks * 0.8); // More than 80% air
    }
    
    /**
     * Returns an estimate of the compression ratio achieved.
     */
    public double getCompressionRatio() {
        int uncompressedSize = WorldConfiguration.CHUNK_SIZE * WorldConfiguration.WORLD_HEIGHT * WorldConfiguration.CHUNK_SIZE;
        int compressedSize = blocks.size();
        return (double) compressedSize / uncompressedSize;
    }
    
    // Getters and setters
    public int getChunkX() { return chunkX; }
    public void setChunkX(int chunkX) { this.chunkX = chunkX; }
    
    public int getChunkZ() { return chunkZ; }
    public void setChunkZ(int chunkZ) { this.chunkZ = chunkZ; }
    
    public List<BlockEntry> getBlocks() { return blocks; }
    public void setBlocks(List<BlockEntry> blocks) { this.blocks = blocks; }
    
    @SuppressWarnings("unused") // Used by Jackson via reflection
    public boolean isDirty() { return isDirty; }
    @SuppressWarnings("unused") // Used by Jackson via reflection
    public void setDirty(boolean isDirty) { this.isDirty = isDirty; }
    
    @SuppressWarnings("unused") // Used by Jackson via reflection
    public LocalDateTime getLastModified() { return lastModified; }
    @SuppressWarnings("unused") // Used by Jackson via reflection
    public void setLastModified(LocalDateTime lastModified) { this.lastModified = lastModified; }
    
    @SuppressWarnings("unused") // Used by Jackson via reflection
    public boolean isGeneratedByPlayer() { return generatedByPlayer; }
    @SuppressWarnings("unused") // Used by Jackson via reflection
    public void setGeneratedByPlayer(boolean generatedByPlayer) { this.generatedByPlayer = generatedByPlayer; }
    
    @SuppressWarnings("unused") // Used by Jackson via reflection
    public boolean isFeaturesPopulated() { return featuresPopulated; }
    @SuppressWarnings("unused") // Used by Jackson via reflection
    public void setFeaturesPopulated(boolean featuresPopulated) { this.featuresPopulated = featuresPopulated; }
    
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    
    @SuppressWarnings("unused") // Used by Jackson via reflection
    public String getCompressionType() { return compressionType; }
    @SuppressWarnings("unused") // Used by Jackson via reflection
    public void setCompressionType(String compressionType) { this.compressionType = compressionType; }
    
    /**
     * Represents a run-length encoded block entry.
     */
    public static class BlockEntry {
        @JsonProperty("blockId")
        private int blockId;
        
        @JsonProperty("runLength")
        private int runLength;
        
        @SuppressWarnings("unused") // Used by Jackson via reflection
        public BlockEntry() {}
        
        public BlockEntry(int blockId, int runLength) {
            this.blockId = blockId;
            this.runLength = runLength;
        }
        
        public int getBlockId() { return blockId; }
        @SuppressWarnings("unused") // Used by Jackson via reflection
        public void setBlockId(int blockId) { this.blockId = blockId; }
        
        public int getRunLength() { return runLength; }
        @SuppressWarnings("unused") // Used by Jackson via reflection
        public void setRunLength(int runLength) { this.runLength = runLength; }
        
        @Override
        public String toString() {
            return "BlockEntry{blockId=" + blockId + ", runLength=" + runLength + "}";
        }
    }
}