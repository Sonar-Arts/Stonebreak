package com.stonebreak.world.chunk.api.commonChunkOperations.serialization;

import com.stonebreak.world.chunk.api.commonChunkOperations.data.CcoBlockArray;

/**
 * Provides hints to the save system for palette compression optimization.
 * Analyzes block data to estimate optimal compression parameters.
 *
 * Thread-safe static methods.
 */
public final class CcoCompressionHints {

    private CcoCompressionHints() {
        // Utility class
    }

    /**
     * Analyzes block array and provides compression hints.
     *
     * @param blockArray Block array to analyze
     * @return Compression hints
     */
    public static CompressionHint analyze(CcoBlockArray blockArray) {
        if (blockArray == null) {
            return new CompressionHint(0, 0, 0.0, CompressionStrategy.NONE);
        }

        int totalBlocks = blockArray.getSizeX() * blockArray.getSizeY() * blockArray.getSizeZ();
        int nonAirBlocks = blockArray.countNonAirBlocks();
        int uniqueTypes = blockArray.countUniqueBlockTypes();

        double density = (double) nonAirBlocks / totalBlocks;

        CompressionStrategy strategy = selectStrategy(density, uniqueTypes);

        return new CompressionHint(nonAirBlocks, uniqueTypes, density, strategy);
    }

    /**
     * Selects optimal compression strategy based on block data characteristics.
     */
    private static CompressionStrategy selectStrategy(double density, int uniqueTypes) {
        // Empty or nearly empty chunks
        if (density < 0.01) {
            return CompressionStrategy.NONE;
        }

        // Very low unique types - excellent palette compression
        if (uniqueTypes <= 4) {
            return CompressionStrategy.PALETTE_2BIT;
        }

        if (uniqueTypes <= 16) {
            return CompressionStrategy.PALETTE_4BIT;
        }

        if (uniqueTypes <= 256) {
            return CompressionStrategy.PALETTE_8BIT;
        }

        // Many unique types - palette + LZ4
        return CompressionStrategy.PALETTE_LZ4;
    }

    /**
     * Estimates bits per block needed for palette encoding.
     *
     * @param uniqueTypes Number of unique block types
     * @return Bits per block (2, 4, 8, or 16)
     */
    public static int estimateBitsPerBlock(int uniqueTypes) {
        if (uniqueTypes <= 4) return 2;
        if (uniqueTypes <= 16) return 4;
        if (uniqueTypes <= 256) return 8;
        return 16;
    }

    /**
     * Estimates compressed size in bytes.
     *
     * @param hint Compression hint
     * @param totalBlocks Total number of blocks
     * @return Estimated compressed size
     */
    public static long estimateCompressedSize(CompressionHint hint, int totalBlocks) {
        int bitsPerBlock = estimateBitsPerBlock(hint.uniqueTypes);
        int paletteSize = hint.uniqueTypes * 4; // 4 bytes per palette entry

        long blockDataSize = (long) ((totalBlocks * bitsPerBlock) / 8);

        // Add palette size and header overhead
        long estimatedSize = paletteSize + blockDataSize + 64; // 64 bytes header overhead

        // LZ4 typically achieves 50-70% of original size
        if (hint.strategy == CompressionStrategy.PALETTE_LZ4) {
            estimatedSize = (long) (estimatedSize * 0.6);
        }

        return estimatedSize;
    }

    /**
     * Compression hint result containing analysis data.
     */
    public static final class CompressionHint {
        public final int nonAirBlocks;
        public final int uniqueTypes;
        public final double density;
        public final CompressionStrategy strategy;

        public CompressionHint(int nonAirBlocks, int uniqueTypes,
                               double density, CompressionStrategy strategy) {
            this.nonAirBlocks = nonAirBlocks;
            this.uniqueTypes = uniqueTypes;
            this.density = density;
            this.strategy = strategy;
        }

        @Override
        public String toString() {
            return String.format("CompressionHint{nonAir=%d, unique=%d, density=%.2f%%, strategy=%s}",
                    nonAirBlocks, uniqueTypes, density * 100, strategy);
        }
    }

    /**
     * Recommended compression strategy.
     */
    public enum CompressionStrategy {
        /** No compression - mostly empty */
        NONE,

        /** 2-bit palette (up to 4 types) */
        PALETTE_2BIT,

        /** 4-bit palette (up to 16 types) */
        PALETTE_4BIT,

        /** 8-bit palette (up to 256 types) */
        PALETTE_8BIT,

        /** Palette + LZ4 compression (many types) */
        PALETTE_LZ4
    }
}
