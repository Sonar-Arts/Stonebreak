package com.stonebreak.world.save;

import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4SafeDecompressor;
import net.jpountz.lz4.LZ4Factory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Binary chunk codec for the new save system.
 * Handles encoding and decoding of chunks using palette compression and LZ4 compression.
 * 
 * Chunk Binary Format:
 * - ChunkHeader (32 bytes)
 * - BlockPalette (variable size)
 * - Bit-packed block data (variable size)
 * - Height map (512 bytes) - optional for future use
 * - Biome data (256 bytes) - optional for future use
 */
public class BinaryChunkCodec {
    
    private final LZ4Factory lz4Factory;
    private final LZ4Compressor compressor;
    private final LZ4SafeDecompressor decompressor;
    
    /** Compression threshold - only compress if savings > 10% */
    private static final double COMPRESSION_THRESHOLD = 0.9;
    
    public BinaryChunkCodec() {
        this.lz4Factory = LZ4Factory.fastestInstance();
        this.compressor = lz4Factory.fastCompressor();
        this.decompressor = lz4Factory.safeDecompressor();
    }
    
    /**
     * Encode a chunk to binary format with optional compression.
     * @param chunk Chunk to encode
     * @return Binary chunk data (header + palette + compressed data)
     * @throws IOException if encoding fails
     */
    public byte[] encodeChunk(Chunk chunk) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        
        // Step 1: Create palette from chunk
        BlockPalette palette = BlockPalette.fromChunk(chunk);
        
        // Step 2: Encode palette
        byte[] paletteData = palette.serialize();
        
        // Step 3: Encode bit-packed block data
        long[] packedBlocks = palette.encodeChunk(chunk);
        byte[] blockData = serializePackedBlocks(packedBlocks);
        
        // Step 4: Combine palette + block data
        ByteArrayOutputStream uncompressedOutput = new ByteArrayOutputStream();
        uncompressedOutput.write(paletteData);
        uncompressedOutput.write(blockData);
        byte[] uncompressedData = uncompressedOutput.toByteArray();
        
        // Step 5: Try compression
        byte[] finalData;
        byte compressionType;
        
        byte[] compressed = compressor.compress(uncompressedData);
        if (compressed.length < uncompressedData.length * COMPRESSION_THRESHOLD) {
            finalData = compressed;
            compressionType = ChunkHeader.COMPRESSION_LZ4;
        } else {
            finalData = uncompressedData;
            compressionType = ChunkHeader.COMPRESSION_NONE;
        }
        
        // Step 6: Create header
        ChunkHeader header = new ChunkHeader(
            chunk.getX(),
            chunk.getZ(),
            palette.size(),
            palette.getBitsPerBlock(),
            uncompressedData.length
        );
        
        header.setCompressionType(compressionType);
        header.setDirty(chunk.isDirty());
        header.setPlayerModified(chunk.isGeneratedByPlayer());
        header.setFeaturesPopulated(chunk.isFeaturesPopulated());
        
        if (chunk.getLastModified() != null) {
            header.setLastModified(chunk.getLastModified());
        }
        
        // Step 7: Write header + data
        output.write(header.serialize());
        output.write(finalData);
        
        return output.toByteArray();
    }
    
    /**
     * Decode binary chunk data back to a Chunk object.
     * @param data Binary chunk data
     * @return Decoded chunk
     * @throws IOException if decoding fails
     */
    public Chunk decodeChunk(byte[] data) throws IOException {
        if (data.length < ChunkHeader.HEADER_SIZE) {
            throw new IOException("Chunk data too small for header");
        }
        
        // Step 1: Read header
        byte[] headerData = new byte[ChunkHeader.HEADER_SIZE];
        System.arraycopy(data, 0, headerData, 0, ChunkHeader.HEADER_SIZE);
        ChunkHeader header = ChunkHeader.deserialize(headerData);
        
        // Step 2: Read and decompress payload
        byte[] payloadData = new byte[data.length - ChunkHeader.HEADER_SIZE];
        System.arraycopy(data, ChunkHeader.HEADER_SIZE, payloadData, 0, payloadData.length);
        
        byte[] uncompressedPayload;
        if (header.isCompressed()) {
            uncompressedPayload = new byte[header.getUncompressedSize()];
            int decompressedBytes = decompressor.decompress(payloadData, 0, payloadData.length, uncompressedPayload, 0);
            if (decompressedBytes != header.getUncompressedSize()) {
                throw new IOException("Decompression failed: expected " + header.getUncompressedSize() + " bytes, got " + decompressedBytes);
            }
        } else {
            uncompressedPayload = payloadData;
        }
        
        // Step 3: Parse palette
        ByteBuffer buffer = ByteBuffer.wrap(uncompressedPayload);
        int paletteSize = buffer.getInt();
        
        // Calculate palette data size: 4 bytes (size) + 4 bytes per entry
        int paletteDataSize = 4 + (paletteSize * 4);
        byte[] paletteData = new byte[paletteDataSize];
        buffer.rewind();
        buffer.get(paletteData);
        
        BlockPalette palette = BlockPalette.deserialize(paletteData, header.getBitsPerBlock());
        
        // Step 4: Parse block data
        int blockDataSize = uncompressedPayload.length - paletteDataSize;
        byte[] blockData = new byte[blockDataSize];
        buffer.get(blockData);
        
        long[] packedBlocks = deserializePackedBlocks(blockData);
        
        // Step 5: Create chunk and populate it
        Chunk chunk = new Chunk(header.getChunkX(), header.getChunkZ());
        palette.decodeChunk(packedBlocks, chunk);
        
        // Step 6: Set chunk metadata
        if (header.isDirty()) {
            chunk.setDirty(header.isPlayerModified());
        }
        chunk.setFeaturesPopulated(header.isFeaturesPopulated());
        chunk.setLastModified(header.getLastModifiedAsDateTime());
        
        return chunk;
    }
    
    /**
     * Serialize packed block data to byte array.
     * @param packedBlocks Bit-packed block indexes
     * @return Serialized block data
     */
    private byte[] serializePackedBlocks(long[] packedBlocks) {
        ByteBuffer buffer = ByteBuffer.allocate(4 + (packedBlocks.length * 8));
        buffer.putInt(packedBlocks.length);
        for (long packedBlock : packedBlocks) {
            buffer.putLong(packedBlock);
        }
        return buffer.array();
    }
    
    /**
     * Deserialize packed block data from byte array.
     * @param blockData Serialized block data
     * @return Bit-packed block indexes
     */
    private long[] deserializePackedBlocks(byte[] blockData) {
        ByteBuffer buffer = ByteBuffer.wrap(blockData);
        int length = buffer.getInt();
        long[] packedBlocks = new long[length];
        for (int i = 0; i < length; i++) {
            packedBlocks[i] = buffer.getLong();
        }
        return packedBlocks;
    }
    
    /**
     * Estimate the encoded size of a chunk without actually encoding it.
     * Useful for buffer allocation and progress reporting.
     * @param chunk Chunk to estimate
     * @return Estimated encoded size in bytes
     */
    public int estimateEncodedSize(Chunk chunk) {
        // Create a minimal palette to estimate size
        BlockPalette palette = BlockPalette.fromChunk(chunk);
        
        // Header size
        int headerSize = ChunkHeader.HEADER_SIZE;
        
        // Palette size: 4 bytes (count) + 4 bytes per unique block
        int paletteSize = 4 + (palette.size() * 4);
        
        // Block data size: 4 bytes (length) + bit-packed data
        int totalBlocks = WorldConfiguration.CHUNK_SIZE * WorldConfiguration.WORLD_HEIGHT * WorldConfiguration.CHUNK_SIZE;
        int bitsNeeded = totalBlocks * palette.getBitsPerBlock();
        int longsNeeded = (bitsNeeded + 63) / 64; // Round up
        int blockDataSize = 4 + (longsNeeded * 8);
        
        // Uncompressed size
        int uncompressedSize = paletteSize + blockDataSize;
        
        // Assume 70% compression ratio for estimation
        int estimatedCompressedSize = (int) (uncompressedSize * 0.7);
        
        return headerSize + Math.min(uncompressedSize, estimatedCompressedSize);
    }
    
    /**
     * Calculate compression statistics for a chunk.
     * @param chunk Chunk to analyze
     * @return Compression statistics
     */
    public CompressionStats calculateStats(Chunk chunk) {
        try {
            // Current system size estimation (BlockType enum = 4 bytes per block)
            int currentSystemSize = WorldConfiguration.CHUNK_SIZE * WorldConfiguration.WORLD_HEIGHT * WorldConfiguration.CHUNK_SIZE * 4;
            
            // New system size
            byte[] encoded = encodeChunk(chunk);
            int newSystemSize = encoded.length;
            
            // Palette analysis
            BlockPalette palette = BlockPalette.fromChunk(chunk);
            double paletteEfficiency = 100.0 * palette.size() / palette.getMaxPaletteSize();
            
            double compressionRatio = 100.0 * (currentSystemSize - newSystemSize) / currentSystemSize;
            
            return new CompressionStats(
                currentSystemSize,
                newSystemSize,
                compressionRatio,
                palette.size(),
                palette.getBitsPerBlock(),
                paletteEfficiency
            );
        } catch (IOException e) {
            return new CompressionStats(0, 0, 0, 0, (byte) 0, 0);
        }
    }
    
    /**
     * Compression statistics for analysis and debugging.
     */
    public static class CompressionStats {
        public final int originalSize;
        public final int compressedSize;
        public final double compressionRatio;
        public final int paletteSize;
        public final byte bitsPerBlock;
        public final double paletteEfficiency;
        
        public CompressionStats(int originalSize, int compressedSize, double compressionRatio, 
                              int paletteSize, byte bitsPerBlock, double paletteEfficiency) {
            this.originalSize = originalSize;
            this.compressedSize = compressedSize;
            this.compressionRatio = compressionRatio;
            this.paletteSize = paletteSize;
            this.bitsPerBlock = bitsPerBlock;
            this.paletteEfficiency = paletteEfficiency;
        }
        
        @Override
        public String toString() {
            return String.format("Compression: %d->%d bytes (%.1f%%), Palette: %d blocks @ %d bits (%.1f%% efficient)",
                    originalSize, compressedSize, compressionRatio, paletteSize, bitsPerBlock, paletteEfficiency);
        }
    }
}