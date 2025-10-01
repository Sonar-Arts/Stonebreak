package com.stonebreak.world.save.storage.binary;

import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.blocks.BlockType;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4FastDecompressor;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.Instant;

/**
 * Binary chunk encoder/decoder with palette compression.
 * Achieves 75-97% size reduction compared to raw block data.
 * Single responsibility: encode/decode chunks only.
 */
public class BinaryChunkCodec {

    private static final int CHUNK_HEADER_SIZE = 32;
    private static final int FORMAT_VERSION = 1;
    private static final byte COMPRESSION_NONE = 0;
    private static final byte COMPRESSION_LZ4 = 1;

    private final LZ4Compressor compressor;
    private final LZ4FastDecompressor decompressor;

    public BinaryChunkCodec() {
        LZ4Factory factory = LZ4Factory.fastestInstance();
        this.compressor = factory.fastCompressor();
        this.decompressor = factory.fastDecompressor();
    }

    /**
     * Encodes a chunk to binary format with palette compression.
     */
    public byte[] encodeChunk(Chunk chunk) {
        try {
            // Build palette from chunk blocks
            BlockPalette palette = BlockPalette.fromChunk(chunk.getBlocks());

            // Encode blocks using palette
            long[] encodedBlocks = palette.encodeBlocks(chunk.getBlocks());

            // Serialize palette
            byte[] paletteData = palette.serializePalette();

            // Build uncompressed body (palette + encoded blocks)
            ByteBuffer bodyBuffer = ByteBuffer.allocate(paletteData.length + encodedBlocks.length * 8);
            bodyBuffer.put(paletteData);
            for (long blockData : encodedBlocks) {
                bodyBuffer.putLong(blockData);
            }
            byte[] body = Arrays.copyOf(bodyBuffer.array(), bodyBuffer.position());

            // Try LZ4 compression on the body
            byte[] compressedBody = compressor.compress(body);
            boolean useCompression = compressedBody.length < body.length * 0.9;

            // Calculate flags: bit 0 = dirty, bit 1 = featuresPopulated
            byte flags = 0;
            if (chunk.isDirty()) flags |= 0x01;
            if (chunk.areFeaturesPopulated()) flags |= 0x02;

            if (useCompression) {
                ByteBuffer finalBuffer = ByteBuffer.allocate(CHUNK_HEADER_SIZE + compressedBody.length);
                writeChunkHeader(finalBuffer, chunk, palette, body.length, COMPRESSION_LZ4, flags);
                finalBuffer.put(compressedBody);
                return finalBuffer.array();
            } else {
                ByteBuffer finalBuffer = ByteBuffer.allocate(CHUNK_HEADER_SIZE + body.length);
                writeChunkHeader(finalBuffer, chunk, palette, 0, COMPRESSION_NONE, flags);
                finalBuffer.put(body);
                return finalBuffer.array();
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to encode chunk at " + chunk.getX() + "," + chunk.getZ(), e);
        }
    }

    /**
     * Decodes a chunk from binary format.
     */
    public Chunk decodeChunk(byte[] data) {
        try {
            // Read header
            ByteBuffer headerBuffer = ByteBuffer.wrap(data, 0, CHUNK_HEADER_SIZE);
            ChunkHeader header = readChunkHeader(headerBuffer);

            // Extract body based on compression
            int bodyOffset = CHUNK_HEADER_SIZE;
            byte[] bodyBytes;
            if (header.compressionType == COMPRESSION_LZ4) {
                int uncompressedSize = header.uncompressedSize;
                byte[] compressed = Arrays.copyOfRange(data, bodyOffset, data.length);
                bodyBytes = decompressor.decompress(compressed, uncompressedSize);
            } else {
                bodyBytes = Arrays.copyOfRange(data, bodyOffset, data.length);
            }
            ByteBuffer buffer = ByteBuffer.wrap(bodyBytes);

            // Read palette
            byte[] paletteData = new byte[header.paletteSize * 4 + 8];
            buffer.get(paletteData);
            BlockPalette palette = BlockPalette.deserializePalette(paletteData);

            // Read encoded block data
            int encodedDataSize = (buffer.remaining()) / 8;
            long[] encodedBlocks = new long[encodedDataSize];
            for (int i = 0; i < encodedDataSize; i++) {
                encodedBlocks[i] = buffer.getLong();
            }

            // Decode blocks using palette
            BlockType[][][] blocks = palette.decodeBlocks(encodedBlocks);

            // Create and populate chunk
            Chunk chunk = new Chunk(header.chunkX, header.chunkZ);
            chunk.setBlocks(blocks);
            chunk.setLastModified(Instant.ofEpochMilli(header.lastModified)
                    .atZone(ZoneId.systemDefault()).toLocalDateTime());

            // Set dirty flag based on header
            if ((header.flags & 0x01) != 0) {
                chunk.markDirty();
            }

            // Set features populated flag based on header (bit 1)
            if ((header.flags & 0x02) != 0) {
                chunk.setFeaturesPopulated(true);
            }

            return chunk;

        } catch (Exception e) {
            throw new RuntimeException("Failed to decode chunk data", e);
        }
    }

    private void writeChunkHeader(ByteBuffer buffer, Chunk chunk, BlockPalette palette, int uncompressedSize, byte compressionType, byte flags) {
        buffer.putInt(chunk.getX());                    // chunkX
        buffer.putInt(chunk.getZ());                    // chunkZ
        buffer.putInt(FORMAT_VERSION);                  // version
        buffer.putInt(uncompressedSize);                // uncompressed size (0 if not compressed)
        buffer.putLong(chunk.getLastModified()
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()); // lastModified
        buffer.putInt(palette.size());                  // paletteSize
        buffer.put((byte) palette.getBitsPerBlock());   // bitsPerBlock
        buffer.put(compressionType);                    // compressionType
        buffer.put(flags);                              // flags (bit 0: dirty)
        buffer.put((byte) 0);                           // reserved
    }

    private ChunkHeader readChunkHeader(ByteBuffer buffer) {
        ChunkHeader header = new ChunkHeader();
        header.chunkX = buffer.getInt();
        header.chunkZ = buffer.getInt();
        header.version = buffer.getInt();
        header.uncompressedSize = buffer.getInt();
        header.lastModified = buffer.getLong();
        header.paletteSize = buffer.getInt();
        header.bitsPerBlock = buffer.get();
        header.compressionType = buffer.get();
        header.flags = buffer.get();
        buffer.get(); // reserved byte
        return header;
    }

    /**
     * Estimates the encoded size of a chunk for memory allocation.
     */
    public int estimateEncodedSize(Chunk chunk) {
        BlockPalette palette = BlockPalette.fromChunk(chunk.getBlocks());
        int paletteSize = palette.size() * 4 + 8;
        int blocksSize = (65536 * palette.getBitsPerBlock() + 63) / 64 * 8; // Round up to nearest long
        return CHUNK_HEADER_SIZE + paletteSize + blocksSize;
    }

    /**
     * Chunk header structure for binary format.
     */
    private static class ChunkHeader {
        int chunkX;
        int chunkZ;
        int version;
        int uncompressedSize;
        long lastModified;
        int paletteSize;
        byte bitsPerBlock;
        byte compressionType;
        byte flags;
    }
}
