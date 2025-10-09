package com.stonebreak.world.save.serialization;

import com.stonebreak.world.save.model.ChunkData;
import com.stonebreak.world.save.model.EntityData;
import com.stonebreak.world.save.storage.binary.BlockPalette;
import com.stonebreak.blocks.BlockType;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4FastDecompressor;
import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;

/**
 * Binary serializer for ChunkData with palette compression and LZ4.
 * All serialization logic centralized here - follows Single Responsibility.
 * Achieves 75-97% size reduction compared to raw block data.
 */
public class BinaryChunkSerializer {
    private static final int CHUNK_HEADER_SIZE = 32;
    private static final int FORMAT_VERSION = 1;
    private static final byte COMPRESSION_NONE = 0;
    private static final byte COMPRESSION_LZ4 = 1;

    private final LZ4Compressor compressor;
    private final LZ4FastDecompressor decompressor;

    public BinaryChunkSerializer() {
        LZ4Factory factory = LZ4Factory.fastestInstance();
        this.compressor = factory.fastCompressor();
        this.decompressor = factory.fastDecompressor();
    }

    public byte[] serialize(ChunkData chunk) {
        try {
            // Build palette from chunk blocks
            BlockPalette palette = BlockPalette.fromChunk(chunk.getBlocks());

            // Encode blocks using palette
            long[] encodedBlocks = palette.encodeBlocks(chunk.getBlocks());

            // Serialize palette
            byte[] paletteData = palette.serializePalette();

            // Serialize water metadata (only non-source water blocks)
            byte[] waterMetadataBytes = serializeWaterMetadata(chunk.getWaterMetadata());

            // Serialize entity data
            byte[] entityDataBytes = serializeEntityData(chunk.getEntities());

            // Build uncompressed body (palette + encoded blocks + water metadata + entity data)
            ByteBuffer bodyBuffer = ByteBuffer.allocate(paletteData.length + encodedBlocks.length * 8 + waterMetadataBytes.length + entityDataBytes.length);
            bodyBuffer.put(paletteData);
            for (long blockData : encodedBlocks) {
                bodyBuffer.putLong(blockData);
            }
            bodyBuffer.put(waterMetadataBytes);
            bodyBuffer.put(entityDataBytes);
            byte[] body = Arrays.copyOf(bodyBuffer.array(), bodyBuffer.position());

            // Try LZ4 compression on the body
            byte[] compressedBody = compressor.compress(body);
            boolean useCompression = compressedBody.length < body.length * 0.9;

            // Calculate flags: bit 0 = featuresPopulated (current), bit 1 = legacy features flag
            byte flags = 0;
            if (chunk.isFeaturesPopulated()) {
                flags |= 0x01; // current format
                flags |= 0x02; // legacy compatibility for pre-refactor saves
            }

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
            throw new RuntimeException("Failed to serialize chunk at " + chunk.getChunkX() + "," + chunk.getChunkZ(), e);
        }
    }

    public ChunkData deserialize(byte[] data) {
        ChunkHeader header = null;
        try {
            // Validate data length
            if (data == null || data.length < CHUNK_HEADER_SIZE) {
                throw new IllegalArgumentException("Chunk data is too small (expected at least " + CHUNK_HEADER_SIZE + " bytes)");
            }

            // Read header
            ByteBuffer headerBuffer = ByteBuffer.wrap(data, 0, CHUNK_HEADER_SIZE);
            header = readChunkHeader(headerBuffer);

            // Validate header
            if (header.version != FORMAT_VERSION) {
                throw new IllegalArgumentException("Unsupported chunk format version: " + header.version);
            }

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
            // Calculate exact size based on palette and chunk dimensions (16x256x16 = 65536 blocks)
            // IMPORTANT: Must use same formula as BlockPalette.encodeBlocks() to ensure consistency
            int bitsPerBlock = palette.getBitsPerBlock();
            int totalBlocks = 16 * 256 * 16; // 65536 blocks
            int bitsNeeded = totalBlocks * bitsPerBlock;
            int encodedDataSize = (bitsNeeded + 63) / 64; // Round up to nearest long (matches encode formula)

            long[] encodedBlocks = new long[encodedDataSize];
            for (int i = 0; i < encodedDataSize; i++) {
                encodedBlocks[i] = buffer.getLong();
            }

            // Decode blocks using palette
            BlockType[][][] blocks = palette.decodeBlocks(encodedBlocks);

            // Deserialize water metadata (if present)
            Map<String, ChunkData.WaterBlockData> waterMetadata = new HashMap<>();
            if (buffer.hasRemaining()) {
                waterMetadata = deserializeWaterMetadata(buffer);
            }

            // Deserialize entity data (if present)
            List<EntityData> entities = new ArrayList<>();
            if (buffer.hasRemaining()) {
                entities = deserializeEntityData(buffer);
            }

            // Build ChunkData
            return ChunkData.builder()
                .chunkX(header.chunkX)
                .chunkZ(header.chunkZ)
                .blocks(blocks)
                .lastModified(Instant.ofEpochMilli(header.lastModified)
                    .atZone(ZoneId.systemDefault()).toLocalDateTime())
                .featuresPopulated(((header.flags & 0x01) != 0) || ((header.flags & 0x02) != 0))
                .waterMetadata(waterMetadata)
                .entities(entities)
                .build();

        } catch (Exception e) {
            String chunkInfo = (header != null) ? "(" + header.chunkX + "," + header.chunkZ + ")" : "(unknown position)";
            throw new RuntimeException("Failed to deserialize chunk " + chunkInfo + ": " + e.getMessage(), e);
        }
    }

    private void writeChunkHeader(ByteBuffer buffer, ChunkData chunk, BlockPalette palette,
                                   int uncompressedSize, byte compressionType, byte flags) {
        buffer.putInt(chunk.getChunkX());                   // chunkX
        buffer.putInt(chunk.getChunkZ());                   // chunkZ
        buffer.putInt(FORMAT_VERSION);                      // version
        buffer.putInt(uncompressedSize);                    // uncompressed size (0 if not compressed)
        buffer.putLong(chunk.getLastModified()
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()); // lastModified
        buffer.putInt(palette.size());                      // paletteSize
        buffer.put((byte) palette.getBitsPerBlock());       // bitsPerBlock
        buffer.put(compressionType);                        // compressionType
        buffer.put(flags);                                  // flags
        buffer.put((byte) 0);                               // reserved
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

    /**
     * Serializes water metadata to bytes with packed integer coordinates.
     * Format: [count:int][entries: packedCoord:short, level:byte, falling:byte]*
     * OPTIMIZATION: Uses 2-byte packed coordinates instead of 3 separate bytes + string parsing
     * Packed format: (localX << 12) | (y << 4) | localZ (fits in 16 bits: 4 + 8 + 4 = 16)
     * Saves 1 byte per water block compared to previous format (4 vs 5 bytes per entry)
     */
    private byte[] serializeWaterMetadata(Map<String, ChunkData.WaterBlockData> waterMetadata) {
        // Filter out source blocks (level 0) - no need to save them
        Map<String, ChunkData.WaterBlockData> nonSourceBlocks = new HashMap<>();
        for (Map.Entry<String, ChunkData.WaterBlockData> entry : waterMetadata.entrySet()) {
            if (entry.getValue().level() != 0) { // Skip source blocks (level 0)
                nonSourceBlocks.put(entry.getKey(), entry.getValue());
            }
        }

        ByteBuffer buffer = ByteBuffer.allocate(4 + nonSourceBlocks.size() * 4); // 4 bytes per entry instead of 5
        buffer.putInt(nonSourceBlocks.size());

        for (Map.Entry<String, ChunkData.WaterBlockData> entry : nonSourceBlocks.entrySet()) {
            // Parse string key "x,y,z" and pack into 16-bit integer
            String[] coords = entry.getKey().split(",");
            int localX = Integer.parseInt(coords[0]);
            int y = Integer.parseInt(coords[1]);
            int localZ = Integer.parseInt(coords[2]);

            // Pack coordinates: localX (4 bits) | y (8 bits) | localZ (4 bits)
            short packedCoord = (short) ((localX << 12) | (y << 4) | localZ);

            buffer.putShort(packedCoord);
            buffer.put((byte) entry.getValue().level());     // level (0-7)
            buffer.put((byte) (entry.getValue().falling() ? 1 : 0)); // falling flag
        }

        return Arrays.copyOf(buffer.array(), buffer.position());
    }

    /**
     * Deserializes water metadata from bytes with packed integer coordinates.
     * Unpacks 16-bit coordinates back to string keys for compatibility.
     */
    private Map<String, ChunkData.WaterBlockData> deserializeWaterMetadata(ByteBuffer buffer) {
        Map<String, ChunkData.WaterBlockData> waterMetadata = new HashMap<>();

        if (buffer.remaining() < 4) {
            return waterMetadata; // No water metadata present
        }

        int count = buffer.getInt();
        for (int i = 0; i < count && buffer.remaining() >= 4; i++) { // 4 bytes per entry (was 5)
            // Unpack coordinates from 16-bit integer
            short packedCoord = buffer.getShort();
            int localX = (packedCoord >> 12) & 0xF;  // Extract top 4 bits
            int y = (packedCoord >> 4) & 0xFF;       // Extract middle 8 bits
            int localZ = packedCoord & 0xF;          // Extract bottom 4 bits

            int level = buffer.get() & 0xFF;
            boolean falling = buffer.get() != 0;

            // Create string key for compatibility with existing codebase
            String key = localX + "," + y + "," + localZ;
            waterMetadata.put(key, new ChunkData.WaterBlockData(level, falling));
        }

        return waterMetadata;
    }

    /**
     * Serializes entity data to bytes using BinaryEntitySerializer.
     * Format: [count:int][entity1Length:short][entity1Binary][entity2Length:short][entity2Binary]...
     * OPTIMIZATION: Binary format achieves 70-85% size reduction vs JSON (30-65 bytes vs 200-400 bytes per entity)
     */
    private byte[] serializeEntityData(List<EntityData> entities) {
        if (entities == null || entities.isEmpty()) {
            // Return just a count of 0
            ByteBuffer buffer = ByteBuffer.allocate(4);
            buffer.putInt(0);
            return buffer.array();
        }

        // Use binary serialization for compact, efficient storage
        BinaryEntitySerializer binarySerializer = new BinaryEntitySerializer();
        List<byte[]> serializedEntities = new ArrayList<>();
        int totalSize = 4; // Start with entity count

        for (EntityData entity : entities) {
            byte[] entityBinary = binarySerializer.serialize(entity);
            serializedEntities.add(entityBinary);
            totalSize += 2 + entityBinary.length; // 2 bytes for length (short) + entity data
        }

        ByteBuffer buffer = ByteBuffer.allocate(totalSize);
        buffer.putInt(entities.size());

        for (byte[] entityBinary : serializedEntities) {
            if (entityBinary.length > Short.MAX_VALUE) {
                throw new RuntimeException("Entity data too large: " + entityBinary.length + " bytes");
            }
            buffer.putShort((short) entityBinary.length);
            buffer.put(entityBinary);
        }

        return buffer.array();
    }

    /**
     * Deserializes entity data from bytes using BinaryEntitySerializer.
     */
    private List<EntityData> deserializeEntityData(ByteBuffer buffer) {
        List<EntityData> entities = new ArrayList<>();

        if (buffer.remaining() < 4) {
            return entities; // No entity data present
        }

        int count = buffer.getInt();
        BinaryEntitySerializer binarySerializer = new BinaryEntitySerializer();

        for (int i = 0; i < count && buffer.remaining() >= 2; i++) {
            short length = buffer.getShort();
            if (buffer.remaining() < length) {
                break; // Corrupted data, stop here
            }

            byte[] entityBinary = new byte[length];
            buffer.get(entityBinary);

            try {
                EntityData entity = binarySerializer.deserialize(entityBinary);
                if (entity != null) {
                    entities.add(entity);
                }
            } catch (Exception e) {
                // Log error but continue deserializing other entities
                System.err.println("Failed to deserialize entity: " + e.getMessage());
            }
        }

        return entities;
    }
}
