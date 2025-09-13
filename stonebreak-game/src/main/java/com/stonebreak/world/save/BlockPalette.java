package com.stonebreak.world.save;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;

import java.nio.ByteBuffer;
import java.util.*;

/**
 * Block palette system for efficient chunk storage.
 * Maps unique block types in a chunk to palette indexes, enabling bit-packed storage.
 * 
 * This system can achieve 75-97% memory reduction depending on block diversity:
 * - Air-only chunks: 1 bit/block (97% reduction)
 * - Simple chunks: 2-4 bits/block (87-94% reduction)
 * - Complex chunks: 5-8 bits/block (50-75% reduction)
 */
public class BlockPalette {
    
    /** Maximum bits per block supported (8 bits = 256 palette entries) */
    public static final byte MAX_BITS_PER_BLOCK = 8;
    
    /** Minimum bits per block (1 bit = 2 palette entries) */
    public static final byte MIN_BITS_PER_BLOCK = 1;
    
    private final BlockType[] palette;
    private final Map<BlockType, Integer> blockToIndex;
    private final byte bitsPerBlock;
    private final int maxPaletteSize;
    
    /**
     * Create a block palette with the specified bit depth.
     * @param bitsPerBlock Number of bits per block (1-8)
     */
    public BlockPalette(byte bitsPerBlock) {
        if (bitsPerBlock < MIN_BITS_PER_BLOCK || bitsPerBlock > MAX_BITS_PER_BLOCK) {
            throw new IllegalArgumentException("Bits per block must be between " + MIN_BITS_PER_BLOCK + " and " + MAX_BITS_PER_BLOCK);
        }
        
        this.bitsPerBlock = bitsPerBlock;
        this.maxPaletteSize = 1 << bitsPerBlock; // 2^bitsPerBlock
        this.palette = new BlockType[maxPaletteSize];
        this.blockToIndex = new HashMap<>();
    }
    
    /**
     * Create a palette from a chunk, automatically determining optimal bit depth.
     * @param chunk Chunk to analyze
     * @return Optimized block palette
     */
    public static BlockPalette fromChunk(Chunk chunk) {
        Set<BlockType> uniqueBlocks = new HashSet<>();
        
        // Collect all unique block types in the chunk
        for (int x = 0; x < WorldConfiguration.CHUNK_SIZE; x++) {
            for (int y = 0; y < WorldConfiguration.WORLD_HEIGHT; y++) {
                for (int z = 0; z < WorldConfiguration.CHUNK_SIZE; z++) {
                    uniqueBlocks.add(chunk.getBlock(x, y, z));
                }
            }
        }
        
        // Determine optimal bits per block
        byte optimalBits = calculateOptimalBits(uniqueBlocks.size());
        
        // Create palette and populate it
        BlockPalette palette = new BlockPalette(optimalBits);
        for (BlockType blockType : uniqueBlocks) {
            palette.addBlock(blockType);
        }
        
        return palette;
    }
    
    /**
     * Calculate optimal bits per block for a given number of unique blocks.
     * @param uniqueBlockCount Number of unique block types
     * @return Optimal bits per block (1-8)
     */
    public static byte calculateOptimalBits(int uniqueBlockCount) {
        if (uniqueBlockCount <= 1) return 1;   // Air only or single block
        if (uniqueBlockCount <= 2) return 1;   // 2 blocks (e.g., air + stone)
        if (uniqueBlockCount <= 4) return 2;   // Simple chunks
        if (uniqueBlockCount <= 8) return 3;   // Basic chunks
        if (uniqueBlockCount <= 16) return 4;  // Typical chunks
        if (uniqueBlockCount <= 32) return 5;  // Complex chunks
        if (uniqueBlockCount <= 64) return 6;  // Very complex chunks
        if (uniqueBlockCount <= 128) return 7; // Extremely complex chunks
        return 8;                               // Maximum complexity
    }
    
    /**
     * Add a block type to the palette.
     * @param blockType Block type to add
     * @return Palette index for this block type
     * @throws IllegalStateException if palette is full
     */
    public int addBlock(BlockType blockType) {
        Integer existingIndex = blockToIndex.get(blockType);
        if (existingIndex != null) {
            return existingIndex;
        }
        
        int index = blockToIndex.size();
        if (index >= maxPaletteSize) {
            throw new IllegalStateException("Palette is full (max " + maxPaletteSize + " entries)");
        }
        
        palette[index] = blockType;
        blockToIndex.put(blockType, index);
        return index;
    }
    
    /**
     * Get the palette index for a block type.
     * @param blockType Block type to look up
     * @return Palette index, or -1 if not in palette
     */
    public int getIndex(BlockType blockType) {
        return blockToIndex.getOrDefault(blockType, -1);
    }
    
    /**
     * Get the block type at a palette index.
     * @param index Palette index
     * @return Block type at the index
     * @throws IndexOutOfBoundsException if index is invalid
     */
    public BlockType getBlock(int index) {
        if (index < 0 || index >= palette.length || palette[index] == null) {
            throw new IndexOutOfBoundsException("Invalid palette index: " + index);
        }
        return palette[index];
    }
    
    /**
     * Get the number of unique block types in this palette.
     * @return Number of palette entries
     */
    public int size() {
        return blockToIndex.size();
    }
    
    /**
     * Get the bits per block for this palette.
     * @return Bits per block
     */
    public byte getBitsPerBlock() {
        return bitsPerBlock;
    }
    
    /**
     * Get the maximum palette size.
     * @return Maximum number of entries this palette can hold
     */
    public int getMaxPaletteSize() {
        return maxPaletteSize;
    }
    
    /**
     * Serialize the palette to binary format.
     * @return Binary representation of the palette
     */
    public byte[] serialize() {
        // Calculate size: 4 bytes (palette size) + 4 bytes per palette entry (block ID)
        int size = 4 + (blockToIndex.size() * 4);
        ByteBuffer buffer = ByteBuffer.allocate(size);
        
        buffer.putInt(blockToIndex.size());
        
        // Write palette entries in order
        for (int i = 0; i < blockToIndex.size(); i++) {
            buffer.putInt(palette[i].getId());
        }
        
        return buffer.array();
    }
    
    /**
     * Deserialize a palette from binary format.
     * @param data Binary palette data
     * @param bitsPerBlock Bits per block for this palette
     * @return Deserialized palette
     */
    public static BlockPalette deserialize(byte[] data, byte bitsPerBlock) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        
        int paletteSize = buffer.getInt();
        BlockPalette palette = new BlockPalette(bitsPerBlock);
        
        for (int i = 0; i < paletteSize; i++) {
            int blockId = buffer.getInt();
            BlockType blockType = BlockType.getById(blockId);
            palette.addBlock(blockType);
        }
        
        return palette;
    }
    
    /**
     * Encode chunk data using this palette.
     * @param chunk Chunk to encode
     * @return Bit-packed block data
     */
    public long[] encodeChunk(Chunk chunk) {
        int totalBlocks = WorldConfiguration.CHUNK_SIZE * WorldConfiguration.WORLD_HEIGHT * WorldConfiguration.CHUNK_SIZE;
        int bitsNeeded = totalBlocks * bitsPerBlock;
        int longsNeeded = (bitsNeeded + 63) / 64; // Round up to nearest long
        
        long[] packedData = new long[longsNeeded];
        int currentLong = 0;
        int currentBit = 0;
        
        for (int x = 0; x < WorldConfiguration.CHUNK_SIZE; x++) {
            for (int y = 0; y < WorldConfiguration.WORLD_HEIGHT; y++) {
                for (int z = 0; z < WorldConfiguration.CHUNK_SIZE; z++) {
                    BlockType block = chunk.getBlock(x, y, z);
                    int paletteIndex = getIndex(block);
                    
                    if (paletteIndex == -1) {
                        throw new IllegalStateException("Block " + block + " not in palette");
                    }
                    
                    // Pack bits into long array
                    if (currentBit + bitsPerBlock > 64) {
                        // Split across two longs
                        int bitsInCurrentLong = 64 - currentBit;
                        int bitsInNextLong = bitsPerBlock - bitsInCurrentLong;
                        
                        // Store lower bits in current long
                        long lowerBits = paletteIndex & ((1L << bitsInCurrentLong) - 1);
                        packedData[currentLong] |= lowerBits << currentBit;
                        
                        // Move to next long and store upper bits
                        currentLong++;
                        currentBit = 0;
                        long upperBits = paletteIndex >> bitsInCurrentLong;
                        packedData[currentLong] |= upperBits << currentBit;
                        currentBit = bitsInNextLong;
                    } else {
                        // Fits entirely in current long
                        long mask = (1L << bitsPerBlock) - 1;
                        packedData[currentLong] |= (paletteIndex & mask) << currentBit;
                        currentBit += bitsPerBlock;
                        
                        if (currentBit >= 64) {
                            currentLong++;
                            currentBit = 0;
                        }
                    }
                }
            }
        }
        
        return packedData;
    }
    
    /**
     * Decode bit-packed data into a chunk using this palette.
     * @param packedData Bit-packed block data
     * @param chunk Chunk to populate
     */
    public void decodeChunk(long[] packedData, Chunk chunk) {
        int currentLong = 0;
        int currentBit = 0;
        long mask = (1L << bitsPerBlock) - 1;
        
        for (int x = 0; x < WorldConfiguration.CHUNK_SIZE; x++) {
            for (int y = 0; y < WorldConfiguration.WORLD_HEIGHT; y++) {
                for (int z = 0; z < WorldConfiguration.CHUNK_SIZE; z++) {
                    int paletteIndex;
                    
                    if (currentBit + bitsPerBlock > 64) {
                        // Split across two longs
                        int bitsInCurrentLong = 64 - currentBit;
                        int bitsInNextLong = bitsPerBlock - bitsInCurrentLong;
                        
                        // Get lower bits from current long
                        long lowerBits = (packedData[currentLong] >> currentBit) & ((1L << bitsInCurrentLong) - 1);
                        
                        // Move to next long and get upper bits
                        currentLong++;
                        currentBit = 0;
                        long upperBits = packedData[currentLong] & ((1L << bitsInNextLong) - 1);
                        
                        paletteIndex = (int) (lowerBits | (upperBits << bitsInCurrentLong));
                        currentBit = bitsInNextLong;
                    } else {
                        // Fits entirely in current long
                        paletteIndex = (int) ((packedData[currentLong] >> currentBit) & mask);
                        currentBit += bitsPerBlock;
                        
                        if (currentBit >= 64) {
                            currentLong++;
                            currentBit = 0;
                        }
                    }
                    
                    BlockType blockType = getBlock(paletteIndex);
                    chunk.setBlock(x, y, z, blockType);
                }
            }
        }
    }
    
    @Override
    public String toString() {
        return String.format("BlockPalette[%d entries, %d bits/block, %d%% efficiency]", 
                size(), bitsPerBlock, (int)(100.0 * size() / maxPaletteSize));
    }
}