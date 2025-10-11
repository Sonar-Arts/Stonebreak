package com.stonebreak.world.save.storage.binary;

import com.stonebreak.blocks.BlockType;
import java.nio.ByteBuffer;
import java.util.*;

/**
 * Optimized block storage system using palette compression.
 * Reduces memory usage from 32 bits per block to 1-8 bits per block.
 * Achieves 75-97% memory reduction for typical chunks.
 */
public class BlockPalette {

    private final List<BlockType> palette;
    private final Map<BlockType, Integer> blockToIndex;
    private final int bitsPerBlock;

    public BlockPalette(Set<BlockType> uniqueBlocks) {
        this.palette = new ArrayList<>(uniqueBlocks);
        this.blockToIndex = new HashMap<>();

        // Build reverse lookup map
        for (int i = 0; i < palette.size(); i++) {
            blockToIndex.put(palette.get(i), i);
        }

        // Calculate optimal bits per block
        this.bitsPerBlock = calculateBitsPerBlock(palette.size());
    }

    /**
     * Creates a palette from a chunk's blocks.
     */
    public static BlockPalette fromChunk(BlockType[][][] blocks) {
        Set<BlockType> uniqueBlocks = new HashSet<>();

        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 256; y++) {
                for (int z = 0; z < 16; z++) {
                    if (blocks[x][y][z] != null) {
                        uniqueBlocks.add(blocks[x][y][z]);
                    }
                }
            }
        }

        return new BlockPalette(uniqueBlocks);
    }

    /**
     * Encodes chunk blocks using palette indexes.
     */
    public long[] encodeBlocks(BlockType[][][] blocks) {
        int totalBlocks = 16 * 256 * 16; // 65,536 blocks
        int bitsNeeded = totalBlocks * bitsPerBlock;
        int longsNeeded = (bitsNeeded + 63) / 64; // Round up to nearest long

        long[] encoded = new long[longsNeeded];
        int bitIndex = 0;

        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 256; y++) {
                for (int z = 0; z < 16; z++) {
                    BlockType block = blocks[x][y][z];
                    if (block == null) block = BlockType.AIR;

                    int paletteIndex = blockToIndex.getOrDefault(block, 0);
                    writeBits(encoded, bitIndex, paletteIndex, bitsPerBlock);
                    bitIndex += bitsPerBlock;
                }
            }
        }

        return encoded;
    }

    /**
     * Decodes blocks from palette indexes.
     */
    public BlockType[][][] decodeBlocks(long[] encodedData) {
        BlockType[][][] blocks = new BlockType[16][256][16];
        int bitIndex = 0;

        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 256; y++) {
                for (int z = 0; z < 16; z++) {
                    int paletteIndex = readBits(encodedData, bitIndex, bitsPerBlock);
                    blocks[x][y][z] = palette.get(paletteIndex);
                    bitIndex += bitsPerBlock;
                }
            }
        }

        return blocks;
    }

    /**
     * Serializes palette to binary format.
     */
    public byte[] serializePalette() {
        ByteBuffer buffer = ByteBuffer.allocate(palette.size() * 4 + 8);

        buffer.putInt(palette.size());
        buffer.putInt(bitsPerBlock);

        for (BlockType block : palette) {
            buffer.putInt(block.getId());
        }

        return buffer.array();
    }

    /**
     * Deserializes palette from binary format.
     */
    public static BlockPalette deserializePalette(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);

        int paletteSize = buffer.getInt();
        int bitsPerBlock = buffer.getInt();

        Set<BlockType> blocks = new LinkedHashSet<>();
        for (int i = 0; i < paletteSize; i++) {
            int blockId = buffer.getInt();
            BlockType block = BlockType.getById(blockId);
            if (block != null) {
                blocks.add(block);
            }
        }

        return new BlockPalette(blocks);
    }

    private static int calculateBitsPerBlock(int uniqueBlocks) {
        if (uniqueBlocks <= 2) return 1;   // Air-only or simple chunks
        if (uniqueBlocks <= 4) return 2;   // Very simple terrain
        if (uniqueBlocks <= 8) return 3;   // Simple terrain
        if (uniqueBlocks <= 16) return 4;  // Typical chunks
        if (uniqueBlocks <= 32) return 5;  // Detailed chunks
        if (uniqueBlocks <= 64) return 6;  // Complex chunks
        if (uniqueBlocks <= 128) return 7; // Very complex chunks
        return 8; // Maximum complexity (256 unique blocks)
    }

    private void writeBits(long[] data, int bitOffset, int value, int bits) {
        int longIndex = bitOffset / 64;
        int bitInLong = bitOffset % 64;

        if (bitInLong + bits <= 64) {
            // Value fits entirely in one long
            long mask = (1L << bits) - 1;
            data[longIndex] |= ((long) value & mask) << bitInLong;
        } else {
            // Value spans two longs
            int bitsInFirstLong = 64 - bitInLong;
            int bitsInSecondLong = bits - bitsInFirstLong;

            long firstMask = (1L << bitsInFirstLong) - 1;
            long secondMask = (1L << bitsInSecondLong) - 1;

            data[longIndex] |= ((long) value & firstMask) << bitInLong;
            data[longIndex + 1] |= ((long) value >> bitsInFirstLong) & secondMask;
        }
    }

    private int readBits(long[] data, int bitOffset, int bits) {
        int longIndex = bitOffset / 64;
        int bitInLong = bitOffset % 64;

        if (bitInLong + bits <= 64) {
            // Value fits entirely in one long
            long mask = (1L << bits) - 1;
            return (int) ((data[longIndex] >> bitInLong) & mask);
        } else {
            // Value spans two longs
            int bitsInFirstLong = 64 - bitInLong;
            int bitsInSecondLong = bits - bitsInFirstLong;

            long firstMask = (1L << bitsInFirstLong) - 1;
            long secondMask = (1L << bitsInSecondLong) - 1;

            int firstPart = (int) ((data[longIndex] >> bitInLong) & firstMask);
            int secondPart = (int) (data[longIndex + 1] & secondMask);

            return firstPart | (secondPart << bitsInFirstLong);
        }
    }

    // Getters
    public int size() { return palette.size(); }
    public int getBitsPerBlock() { return bitsPerBlock; }
    public List<BlockType> getPalette() { return new ArrayList<>(palette); }

    /**
     * Calculates compression ratio achieved.
     */
    public double getCompressionRatio() {
        int originalBits = 65536 * 32; // 32 bits per block
        int compressedBits = 65536 * bitsPerBlock + (palette.size() * 32); // Plus palette overhead
        return 1.0 - ((double) compressedBits / originalBits);
    }
}