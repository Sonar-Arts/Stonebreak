package com.stonebreak.world.save.io;

import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.cco.data.CcoBlockStorage;
import com.openmason.engine.voxel.cco.data.palette.CcoPaletteSection;
import com.openmason.engine.voxel.cco.data.palette.CcoPalettedChunkStorage;
import com.openmason.engine.voxel.cco.data.palette.CcoSectionIndexing;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.chunk.utils.LocalBlockKey;
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
 *
 *   v2+ only (SBO 1.3 block states):
 *   blockStateCount (int)
 *     repeated: localX (unsigned byte), y (unsigned short), localZ (unsigned byte),
 *               stateNameLen (unsigned short), stateNameBytes (utf-8)
 *
 *   v3+ only (snow layer counts):
 *   snowCount (int)
 *     repeated: localX (unsigned byte), y (unsigned short), localZ (unsigned byte),
 *               layers (unsigned byte, 1-8)
 * </pre>
 *
 * <p>Version history:
 * <ul>
 *   <li>1 — original format with blocks, water, entities</li>
 *   <li>2 — adds optional per-block SBO state map at the end of the body.
 *       v1 chunks load without states; v2 writers always emit the section
 *       (count=0 when no block carries a non-default state).</li>
 *   <li>3 — adds snow layer counts at the end of the body (previously in-memory
 *       only, so stacked snow reset on reload). v1/v2 chunks load with no
 *       tracked layers (every snow block reads as the 1-layer default).
 *       NOTE: v3 saves are unreadable by pre-v3 builds — one-way migration.</li>
 * </ul>
 */
public final class ChunkCodec {

    private static final int MAGIC = 0x5342434B; // 'SBCK'
    /** Current write version. */
    private static final int VERSION = 3;
    /** Earliest readable version. */
    private static final int MIN_READ_VERSION = 1;
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

            byte[] compressedBlocks = compressBlocks(chunk.getBlockStorage());
            out.writeInt(compressedBlocks.length);
            out.write(compressedBlocks);

            writeWaterMetadata(out, chunk.getWaterMetadata());
            writeEntities(out, chunk.getEntities());
            writeBlockStates(out, chunk.getBlockStates());
            writeSnowLayers(out, chunk.getSnowLayers());
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
            if (version < MIN_READ_VERSION || version > VERSION) {
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
            CcoBlockStorage blocks = decompressBlocks(compressedBlocks);

            Map<String, ChunkData.WaterBlockData> waterMeta = readWaterMetadata(in);
            List<EntityData> entities = readEntities(in);

            // v2+: per-block SBO state map. Older saves omit this section
            // and load with an empty map (everything renders as default).
            Map<Integer, String> blockStates = (version >= 2) ? readBlockStates(in) : new HashMap<>();

            // v3+: snow layer counts. Older saves load with none tracked (1-layer default).
            Map<Integer, Integer> snowLayers = (version >= 3) ? readSnowLayers(in) : new HashMap<>();

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
                .blockStates(blockStates)
                .snowLayers(snowLayers)
                .build();
        }
    }

    private static void writeBlockStates(DataOutputStream out, Map<Integer, String> blockStates) throws IOException {
        out.writeInt(blockStates.size());
        for (Map.Entry<Integer, String> entry : blockStates.entrySet()) {
            int key = entry.getKey();
            out.writeByte(LocalBlockKey.x(key));
            out.writeShort(LocalBlockKey.y(key));
            out.writeByte(LocalBlockKey.z(key));
            byte[] nameBytes = entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.writeShort(nameBytes.length);
            out.write(nameBytes);
        }
    }

    private static Map<Integer, String> readBlockStates(DataInputStream in) throws IOException {
        int count = in.readInt();
        Map<Integer, String> result = new HashMap<>(Math.max(count, 0));
        for (int i = 0; i < count; i++) {
            int localX = Byte.toUnsignedInt(in.readByte());
            int y = Short.toUnsignedInt(in.readShort());
            int localZ = Byte.toUnsignedInt(in.readByte());
            int len = Short.toUnsignedInt(in.readShort());
            byte[] nameBytes = in.readNBytes(len);
            if (nameBytes.length != len) {
                throw new IOException("Incomplete block-state name: expected " + len + " bytes");
            }
            String state = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8);
            result.put(LocalBlockKey.pack(localX, y, localZ), state);
        }
        return result;
    }

    private static void writeSnowLayers(DataOutputStream out, Map<Integer, Integer> snowLayers) throws IOException {
        out.writeInt(snowLayers.size());
        for (Map.Entry<Integer, Integer> entry : snowLayers.entrySet()) {
            int key = entry.getKey();
            out.writeByte(LocalBlockKey.x(key));
            out.writeShort(LocalBlockKey.y(key));
            out.writeByte(LocalBlockKey.z(key));
            out.writeByte(Math.max(1, Math.min(8, entry.getValue())));
        }
    }

    private static Map<Integer, Integer> readSnowLayers(DataInputStream in) throws IOException {
        int count = in.readInt();
        Map<Integer, Integer> result = new HashMap<>(Math.max(count, 0));
        for (int i = 0; i < count; i++) {
            int localX = Byte.toUnsignedInt(in.readByte());
            int y = Short.toUnsignedInt(in.readShort());
            int localZ = Byte.toUnsignedInt(in.readByte());
            int layers = Byte.toUnsignedInt(in.readByte());
            result.put(LocalBlockKey.pack(localX, y, localZ), Math.max(1, Math.min(8, layers)));
        }
        return result;
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

    /**
     * Serializes block storage to the deflated short-id stream.
     * Byte output is identical to the legacy dense-array encoder (y,z,x order),
     * so saves stay interchangeable across versions.
     *
     * <p>Paletted sections take a fast path: cells within a section are laid
     * out in the same y,z,x order, and uniform sections emit one id 4096 times
     * with zero per-cell lookups.
     */
    private static byte[] compressBlocks(CcoBlockStorage storage) throws IOException {
        if (storage == null) {
            throw new IllegalArgumentException("Chunk blocks cannot be null");
        }

        ByteArrayOutputStream raw = new ByteArrayOutputStream(BLOCK_COUNT * Short.BYTES);
        try (DataOutputStream rawOut = new DataOutputStream(raw)) {
            if (storage instanceof CcoPalettedChunkStorage paletted) {
                int cellsPerSection = CHUNK_WIDTH * CHUNK_WIDTH * CcoSectionIndexing.SECTION_HEIGHT;
                for (int s = 0; s < paletted.getSectionCount(); s++) {
                    CcoPaletteSection section = paletted.getSection(s);
                    if (section.isUniform()) {
                        int id = blockId(section.uniformBlock());
                        for (int i = 0; i < cellsPerSection; i++) {
                            rawOut.writeShort(id);
                        }
                    } else {
                        for (int i = 0; i < cellsPerSection; i++) {
                            rawOut.writeShort(blockId(section.get(i)));
                        }
                    }
                }
            } else {
                for (int y = 0; y < CHUNK_HEIGHT; y++) {
                    for (int z = 0; z < CHUNK_WIDTH; z++) {
                        for (int x = 0; x < CHUNK_WIDTH; x++) {
                            rawOut.writeShort(blockId(storage.get(x, y, z)));
                        }
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

    private static int blockId(IBlockType block) {
        return block != null ? block.getId() : BlockType.AIR.getId();
    }

    private static CcoBlockStorage decompressBlocks(byte[] compressed) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream(BLOCK_COUNT * Short.BYTES);
        try (InflaterInputStream inflater = new InflaterInputStream(
            new BufferedInputStream(new ByteArrayInputStream(compressed)))) {
            inflater.transferTo(raw);
        }

        byte[] rawBytes = raw.toByteArray();
        if (rawBytes.length != BLOCK_COUNT * Short.BYTES) {
            throw new IOException("Unexpected decompressed block array length: " + rawBytes.length);
        }

        // Build paletted storage directly — skipping AIR writes keeps the
        // above-terrain sections in their near-free uniform tier.
        CcoPalettedChunkStorage storage =
            CcoPalettedChunkStorage.createEmpty(CHUNK_WIDTH, CHUNK_HEIGHT, CHUNK_WIDTH, BlockType.AIR);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(rawBytes))) {
            for (int y = 0; y < CHUNK_HEIGHT; y++) {
                for (int z = 0; z < CHUNK_WIDTH; z++) {
                    for (int x = 0; x < CHUNK_WIDTH; x++) {
                        int id = Short.toUnsignedInt(in.readShort());
                        BlockType block = BlockType.getById(id);
                        if (block == null || block == BlockType.AIR) {
                            continue;
                        }
                        storage.set(x, y, z, block);
                    }
                }
            }
        }
        return storage;
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
