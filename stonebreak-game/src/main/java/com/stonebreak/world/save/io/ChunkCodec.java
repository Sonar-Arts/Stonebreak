package com.stonebreak.world.save.io;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.save.model.ChunkData;
import com.stonebreak.world.save.model.EntityData;
import com.stonebreak.world.save.serialization.JsonEntitySerializer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Encodes and decodes chunk payloads for persistence.
 * Format intentionally simple to minimise corruption vectors:
 *
 * <pre>
 * Header:
 *   magic (int)            = 'SBCK'
 *   version (short)        = 1
 *   chunkX (int)
 *   chunkZ (int)
 *   lastModified (long)    = epoch millis
 *   featuresPopulated (boolean)
 *   hasEntitiesGenerated (boolean)
 *
 * Body:
 *   blockDataLength (int)  length of compressed block buffer
 *   blockData (bytes)      deflated little-endian short[65536] block ids
 *   waterCount (int)
 *     repeated: localX (unsigned byte), y (unsigned short), localZ (unsigned byte),
 *               level (unsigned byte), falling (boolean)
 *   entityCount (int)
 *     repeated: payloadLength (int), jsonPayload (utf-8 bytes)
 * </pre>
 */
public final class ChunkCodec {

    private static final int MAGIC = 0x5342434B; // 'SBCK'
    private static final int VERSION = 1;
    private static final int CHUNK_WIDTH = 16;
    private static final int CHUNK_HEIGHT = 256;
    private static final int BLOCK_COUNT = CHUNK_WIDTH * CHUNK_WIDTH * CHUNK_HEIGHT;

    private ChunkCodec() {
    }

    public static byte[] encode(ChunkData chunk) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(buffer))) {
            out.writeInt(MAGIC);
            out.writeShort(VERSION);
            out.writeInt(chunk.getChunkX());
            out.writeInt(chunk.getChunkZ());
            out.writeLong(chunk.getLastModified()
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli());
            out.writeBoolean(chunk.isFeaturesPopulated());
            out.writeBoolean(chunk.hasEntitiesGenerated());

            byte[] compressedBlocks = compressBlocks(chunk.getBlocks());
            out.writeInt(compressedBlocks.length);
            out.write(compressedBlocks);

            writeWaterMetadata(out, chunk.getWaterMetadata());
            writeEntities(out, chunk.getEntities());
        }
        return buffer.toByteArray();
    }

    public static ChunkData decode(byte[] payload) throws IOException {
        try (DataInputStream in = new DataInputStream(
            new BufferedInputStream(new ByteArrayInputStream(payload)))) {

            int magic = in.readInt();
            if (magic != MAGIC) {
                throw new IOException("Chunk payload missing SBCK header");
            }

            int version = in.readUnsignedShort();
            if (version != VERSION) {
                throw new IOException("Unsupported chunk payload version: " + version);
            }

            int chunkX = in.readInt();
            int chunkZ = in.readInt();
            long lastModifiedMillis = in.readLong();
            boolean featuresPopulated = in.readBoolean();
            boolean hasEntitiesGenerated = in.readBoolean();

            int compressedLength = in.readInt();
            if (compressedLength <= 0) {
                throw new IOException("Compressed block buffer length invalid: " + compressedLength);
            }
            byte[] compressedBlocks = in.readNBytes(compressedLength);
            if (compressedBlocks.length != compressedLength) {
                throw new IOException("Incomplete block buffer: expected " + compressedLength
                    + " bytes, got " + compressedBlocks.length);
            }
            BlockType[][][] blocks = decompressBlocks(compressedBlocks);

            Map<String, ChunkData.WaterBlockData> waterMeta = readWaterMetadata(in);
            List<EntityData> entities = readEntities(in);

            return ChunkData.builder()
                .chunkX(chunkX)
                .chunkZ(chunkZ)
                .blocks(blocks)
                .lastModified(Instant.ofEpochMilli(lastModifiedMillis)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime())
                .featuresPopulated(featuresPopulated)
                .hasEntitiesGenerated(hasEntitiesGenerated)
                .waterMetadata(waterMeta)
                .entities(entities)
                .build();
        }
    }

    private static void writeWaterMetadata(DataOutputStream out,
                                           Map<String, ChunkData.WaterBlockData> metadata) throws IOException {
        out.writeInt(metadata.size());
        for (Map.Entry<String, ChunkData.WaterBlockData> entry : metadata.entrySet()) {
            int[] coords = parseKey(entry.getKey());
            out.writeByte(coords[0]); // localX 0-15
            out.writeShort(coords[1]); // y 0-255
            out.writeByte(coords[2]); // localZ 0-15
            out.writeByte(entry.getValue().level());
            out.writeBoolean(entry.getValue().falling());
        }
    }

    private static Map<String, ChunkData.WaterBlockData> readWaterMetadata(DataInputStream in) throws IOException {
        int count = in.readInt();
        Map<String, ChunkData.WaterBlockData> result = new HashMap<>(Math.max(count, 0));
        for (int i = 0; i < count; i++) {
            int localX = Byte.toUnsignedInt(in.readByte());
            int y = Short.toUnsignedInt(in.readShort());
            int localZ = Byte.toUnsignedInt(in.readByte());
            int level = Byte.toUnsignedInt(in.readByte());
            boolean falling = in.readBoolean();
            String key = localX + "," + y + "," + localZ;
            result.put(key, new ChunkData.WaterBlockData(level, falling));
        }
        return result;
    }

    private static void writeEntities(DataOutputStream out, List<EntityData> entities) throws IOException {
        JsonEntitySerializer serializer = new JsonEntitySerializer();
        out.writeInt(entities.size());
        for (EntityData entity : entities) {
            byte[] payload = serializer.serialize(entity);
            out.writeInt(payload.length);
            out.write(payload);
        }
    }

    private static List<EntityData> readEntities(DataInputStream in) throws IOException {
        JsonEntitySerializer serializer = new JsonEntitySerializer();
        int count = in.readInt();
        List<EntityData> entities = new ArrayList<>(Math.max(count, 0));
        for (int i = 0; i < count; i++) {
            int length = in.readInt();
            if (length < 0) {
                throw new IOException("Negative entity payload length");
            }
            byte[] payload = in.readNBytes(length);
            if (payload.length != length) {
                throw new IOException("Incomplete entity payload: expected " + length + " bytes");
            }
            entities.add(serializer.deserialize(payload));
        }
        return entities;
    }

    private static byte[] compressBlocks(BlockType[][][] blocks) throws IOException {
        if (blocks == null) {
            throw new IllegalArgumentException("Chunk blocks cannot be null");
        }

        ByteArrayOutputStream raw = new ByteArrayOutputStream(BLOCK_COUNT * Short.BYTES);
        try (DataOutputStream rawOut = new DataOutputStream(raw)) {
            for (int y = 0; y < CHUNK_HEIGHT; y++) {
                for (int z = 0; z < CHUNK_WIDTH; z++) {
                    for (int x = 0; x < CHUNK_WIDTH; x++) {
                        BlockType block = blocks[x][y][z];
                        int id = block != null ? block.getId() : BlockType.AIR.getId();
                        rawOut.writeShort(id);
                    }
                }
            }
        }

        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(
            new BufferedOutputStream(compressed))) {
            deflater.write(raw.toByteArray());
        }
        return compressed.toByteArray();
    }

    private static BlockType[][][] decompressBlocks(byte[] compressed) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream(BLOCK_COUNT * Short.BYTES);
        try (InflaterInputStream inflater = new InflaterInputStream(
            new BufferedInputStream(new ByteArrayInputStream(compressed)))) {
            inflater.transferTo(raw);
        }

        byte[] rawBytes = raw.toByteArray();
        if (rawBytes.length != BLOCK_COUNT * Short.BYTES) {
            throw new IOException("Unexpected decompressed block array length: " + rawBytes.length);
        }

        BlockType[][][] blocks = new BlockType[CHUNK_WIDTH][CHUNK_HEIGHT][CHUNK_WIDTH];
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(rawBytes))) {
            for (int y = 0; y < CHUNK_HEIGHT; y++) {
                for (int z = 0; z < CHUNK_WIDTH; z++) {
                    for (int x = 0; x < CHUNK_WIDTH; x++) {
                        int id = Short.toUnsignedInt(in.readShort());
                        BlockType block = BlockType.getById(id);
                        if (block == null) {
                            block = BlockType.AIR;
                        }
                        blocks[x][y][z] = block;
                    }
                }
            }
        }
        return blocks;
    }

    private static int[] parseKey(String key) {
        String[] parts = key.split(",");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid water metadata key: " + key);
        }
        return new int[]{
            Integer.parseInt(parts[0]),
            Integer.parseInt(parts[1]),
            Integer.parseInt(parts[2])
        };
    }
}
