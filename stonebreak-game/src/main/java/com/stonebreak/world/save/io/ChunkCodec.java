package com.stonebreak.world.save.io;

import com.openmason.engine.cenda.CendaKernels;
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Encodes and decodes chunk payloads for persistence.
 * Format intentionally simple to minimise corruption vectors:
 *
 * <pre>
 * Header:
 *   magic (int)            = 'SBCK'
 *   version (short)
 *   chunkX (int)
 *   chunkZ (int)
 *   lastModified (long)    = epoch millis
 *   featuresPopulated (boolean)
 *   hasEntitiesGenerated (boolean)
 *
 * Body (v5+):
 *   compression (byte)     0 = DEFLATE, 1 = zstd (requires Cenda kernels)
 *   rawLength (int)        uncompressed section-stream length
 *   blockDataLength (int)  compressed section-stream length
 *   blockData (bytes)      compressed paletted section stream:
 *     sectionCount (unsigned byte)
 *     repeated per section (16 blocks tall, cells in y,z,x order):
 *       tier (byte) 0 = uniform:      blockId (unsigned short)
 *                   1 = byte-paletted: paletteSize-1 (unsigned byte),
 *                                      paletteIds (unsigned short × size),
 *                                      indices (byte × 4096)
 *                   2 = dense:         blockIds (unsigned short × 4096)
 *
 * Body (v1-v4):
 *   blockDataLength (int)  length of compressed block buffer
 *   blockData (bytes)      compressed big-endian short[65536] block ids
 *
 * Tail (all versions):
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
 *   <li>4 — identical raw block layout, zstd-compressed via the Cenda native
 *       kernels; read requires the kernels.</li>
 *   <li>5 — section-paletted block stream (this writer's format): uniform
 *       sections cost 3 bytes, mixed sections a palette + one byte per cell —
 *       the raw stream drops from a fixed 128 KB to typically &lt;25 KB before
 *       compression. Compression is a header flag (DEFLATE always available,
 *       zstd when the kernels are present) instead of a separate version.
 *       Encode/decode use per-thread reusable buffers; decoded sections are
 *       installed wholesale via {@code CcoPaletteSection.fromPaletteData}
 *       (no per-cell set calls). NOTE: unreadable by pre-v5 builds.</li>
 * </ul>
 */
public final class ChunkCodec {

    private static final int MAGIC = 0x5342434B; // 'SBCK'
    private static final int VERSION_PALETTED = 5;
    private static final int MAX_READ_VERSION = VERSION_PALETTED;
    /** Legacy zstd version (dense stream, requires kernels to read). */
    private static final int VERSION_ZSTD = 4;
    /** Earliest readable version. */
    private static final int MIN_READ_VERSION = 1;
    private static final int CHUNK_WIDTH = 16;
    private static final int CHUNK_HEIGHT = 256;
    private static final int BLOCK_COUNT = CHUNK_WIDTH * CHUNK_WIDTH * CHUNK_HEIGHT;
    private static final int SECTION_HEIGHT = CcoSectionIndexing.SECTION_HEIGHT;
    private static final int CELLS_PER_SECTION = CHUNK_WIDTH * CHUNK_WIDTH * SECTION_HEIGHT;
    private static final int SECTION_COUNT = CHUNK_HEIGHT / SECTION_HEIGHT;

    /** Fixed byte size of the header fields shared by every version. */
    private static final int HEADER_BYTES = 4 + 2 + 4 + 4 + 8 + 1 + 1;

    private static final byte COMPRESSION_DEFLATE = 0;
    private static final byte COMPRESSION_ZSTD = 1;
    private static final int ZSTD_LEVEL = 3;

    private static final byte TIER_UNIFORM = 0;
    private static final byte TIER_PALETTED = 1;
    private static final byte TIER_DENSE = 2;

    /** Worst case: every section dense (1 + 16 × (1 + 8192) bytes). */
    private static final int MAX_RAW_BYTES = 1 + SECTION_COUNT * (1 + CELLS_PER_SECTION * 2);

    /**
     * Per-thread reusable encode/decode buffers. Save/load run on small
     * dedicated thread pools, so a few of these exist at a time; before this,
     * every chunk save allocated a fresh 128 KB raw stream plus compression
     * buffers, and every load rebuilt storage with 65k per-cell set calls.
     */
    private static final class Scratch {
        final byte[] raw = new byte[MAX_RAW_BYTES];
        byte[] compressed = new byte[CendaKernels.zstdCompressBound(MAX_RAW_BYTES)];
        final short[] paletteIds = new short[256];
        final byte[] indices = new byte[CELLS_PER_SECTION];
        final short[] denseIds = new short[CELLS_PER_SECTION];
        final IBlockType[] paletteBlocks = new IBlockType[256];
        Deflater deflater;
        Inflater inflater;
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private ChunkCodec() {
    }

    public static byte[] encode(ChunkData chunk) throws IOException {
        Scratch s = SCRATCH.get();
        int rawLen = encodeBlockSections(chunk.getBlockStorage(), s);

        boolean zstd = CendaKernels.isAvailable();
        int compLen;
        if (zstd) {
            compLen = CendaKernels.zstdCompress(s.raw, rawLen, s.compressed, ZSTD_LEVEL);
            if (compLen <= 0) {
                throw new IOException("zstd block compression failed");
            }
        } else {
            compLen = deflateInto(s, rawLen);
        }

        ByteArrayOutputStream buffer = new ByteArrayOutputStream(HEADER_BYTES + 9 + compLen + 1024);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeInt(MAGIC);
            out.writeShort(VERSION_PALETTED);
            out.writeInt(chunk.getChunkX());
            out.writeInt(chunk.getChunkZ());
            out.writeLong(chunk.getLastModified()
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli());
            out.writeBoolean(chunk.isFeaturesPopulated());
            out.writeBoolean(chunk.hasEntitiesGenerated());

            out.writeByte(zstd ? COMPRESSION_ZSTD : COMPRESSION_DEFLATE);
            out.writeInt(rawLen);
            out.writeInt(compLen);
            out.write(s.compressed, 0, compLen);

            writeWaterMetadata(out, chunk.getWaterMetadata());
            writeEntities(out, chunk.getEntities());
            writeBlockStates(out, chunk.getBlockStates());
            writeSnowLayers(out, chunk.getSnowLayers());
        }
        return buffer.toByteArray();
    }

    public static ChunkData decode(byte[] payload) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {

            int magic = in.readInt();
            if (magic != MAGIC) {
                throw new IOException("Chunk payload missing SBCK header");
            }

            int version = in.readUnsignedShort();
            if (version < MIN_READ_VERSION || version > MAX_READ_VERSION) {
                throw new IOException("Unsupported chunk payload version: " + version);
            }

            int chunkX = in.readInt();
            int chunkZ = in.readInt();
            long lastModifiedMillis = in.readLong();
            boolean featuresPopulated = in.readBoolean();
            boolean hasEntitiesGenerated = in.readBoolean();

            CcoBlockStorage blocks;
            if (version >= VERSION_PALETTED) {
                int compression = in.readUnsignedByte();
                requireKernelsFor(compression == COMPRESSION_ZSTD, version);
                int rawLen = in.readInt();
                int compLen = in.readInt();
                if (rawLen <= 0 || rawLen > MAX_RAW_BYTES || compLen <= 0
                        || HEADER_BYTES + 9 + compLen > payload.length) {
                    throw new IOException("Block section stream lengths invalid: raw="
                        + rawLen + ", compressed=" + compLen);
                }
                in.skipNBytes(compLen);
                blocks = decodePalettedSections(payload, HEADER_BYTES + 9, compLen,
                    compression, rawLen);
            } else {
                boolean zstd = version >= VERSION_ZSTD;
                requireKernelsFor(zstd, version);
                int compLen = in.readInt();
                if (compLen <= 0 || HEADER_BYTES + 4 + compLen > payload.length) {
                    throw new IOException("Compressed block buffer length invalid: " + compLen);
                }
                in.skipNBytes(compLen);
                blocks = decodeDenseLegacy(payload, HEADER_BYTES + 4, compLen, zstd);
            }

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

    private static void requireKernelsFor(boolean zstd, int version) throws IOException {
        if (zstd && !CendaKernels.isAvailable()) {
            throw new IOException("Chunk payload v" + version
                + " is zstd-compressed and requires the Cenda native kernels "
                + "(build openmason-engine/cenda, release preset)");
        }
    }

    // ═══════════════════════ Block sections: encode ═══════════════════════

    /**
     * Serializes block storage as the paletted section stream into
     * {@code s.raw}, returning its length. Uniform sections cost 3 bytes;
     * byte-paletted sections dump the palette + index array directly out of
     * the storage snapshot; the practically-unreachable short-indexed tier
     * (and non-paletted storage) falls back to dense 2-bytes-per-cell.
     */
    private static int encodeBlockSections(CcoBlockStorage storage, Scratch s) {
        if (storage == null) {
            throw new IllegalArgumentException("Chunk blocks cannot be null");
        }
        byte[] raw = s.raw;
        int pos = 0;

        if (storage instanceof CcoPalettedChunkStorage paletted) {
            raw[pos++] = (byte) paletted.getSectionCount();
            for (int sec = 0; sec < paletted.getSectionCount(); sec++) {
                CcoPaletteSection section = paletted.getSection(sec);
                int paletteSize = section.snapshotPaletteData(s.paletteIds, s.indices);
                if (paletteSize == 0) {
                    raw[pos++] = TIER_UNIFORM;
                    pos = putShort(raw, pos, s.paletteIds[0]);
                } else if (paletteSize > 0) {
                    raw[pos++] = TIER_PALETTED;
                    raw[pos++] = (byte) (paletteSize - 1);
                    for (int i = 0; i < paletteSize; i++) {
                        pos = putShort(raw, pos, s.paletteIds[i]);
                    }
                    System.arraycopy(s.indices, 0, raw, pos, CELLS_PER_SECTION);
                    pos += CELLS_PER_SECTION;
                } else {
                    raw[pos++] = TIER_DENSE;
                    section.writeBlockIdsInto(s.denseIds, 0);
                    for (int i = 0; i < CELLS_PER_SECTION; i++) {
                        pos = putShort(raw, pos, s.denseIds[i]);
                    }
                }
            }
        } else {
            raw[pos++] = (byte) SECTION_COUNT;
            for (int sec = 0; sec < SECTION_COUNT; sec++) {
                raw[pos++] = TIER_DENSE;
                int yBase = sec * SECTION_HEIGHT;
                for (int ly = 0; ly < SECTION_HEIGHT; ly++) {
                    for (int z = 0; z < CHUNK_WIDTH; z++) {
                        for (int x = 0; x < CHUNK_WIDTH; x++) {
                            pos = putShort(raw, pos, blockId(storage.get(x, yBase + ly, z)));
                        }
                    }
                }
            }
        }
        return pos;
    }

    // ═══════════════════════ Block sections: decode ═══════════════════════

    /** Decodes the v5 paletted section stream into fresh paletted storage. */
    private static CcoBlockStorage decodePalettedSections(byte[] payload, int offset, int compLen,
                                                          int compression, int rawLen) throws IOException {
        Scratch s = SCRATCH.get();
        decompressInto(payload, offset, compLen, compression == COMPRESSION_ZSTD, s, rawLen);

        CcoPalettedChunkStorage storage =
            CcoPalettedChunkStorage.createEmpty(CHUNK_WIDTH, CHUNK_HEIGHT, CHUNK_WIDTH, BlockType.AIR);
        byte[] raw = s.raw;
        int pos = 0;
        int sectionCount = raw[pos++] & 0xFF;
        if (sectionCount != storage.getSectionCount()) {
            throw new IOException("Unexpected section count: " + sectionCount);
        }
        try {
            pos = parseSections(storage, raw, pos, sectionCount, s);
        } catch (RuntimeException e) {
            // Corrupt palette data surfaces as unchecked exceptions from the
            // section factories — load callers handle IOException only.
            throw new IOException("Corrupt section stream", e);
        }
        if (pos != rawLen) {
            throw new IOException("Section stream length mismatch: consumed " + pos
                + " of " + rawLen + " bytes");
        }
        return storage;
    }

    private static int parseSections(CcoPalettedChunkStorage storage, byte[] raw, int pos,
                                     int sectionCount, Scratch s) throws IOException {
        for (int sec = 0; sec < sectionCount; sec++) {
            int tier = raw[pos++];
            switch (tier) {
                case TIER_UNIFORM -> {
                    int id = getShort(raw, pos);
                    pos += 2;
                    BlockType block = blockOrAir(id);
                    if (block != BlockType.AIR) {
                        storage.replaceSection(sec,
                            new CcoPaletteSection(CHUNK_WIDTH * CHUNK_WIDTH, block));
                    }
                }
                case TIER_PALETTED -> {
                    int paletteSize = (raw[pos++] & 0xFF) + 1;
                    for (int i = 0; i < paletteSize; i++) {
                        s.paletteBlocks[i] = blockOrAir(getShort(raw, pos));
                        pos += 2;
                    }
                    // The index array becomes the section's resident storage —
                    // this is the only per-section allocation on the load path.
                    byte[] cellIndices = Arrays.copyOfRange(raw, pos, pos + CELLS_PER_SECTION);
                    pos += CELLS_PER_SECTION;
                    storage.replaceSection(sec, CcoPaletteSection.fromPaletteData(
                        CHUNK_WIDTH * CHUNK_WIDTH, s.paletteBlocks, paletteSize, cellIndices));
                }
                case TIER_DENSE -> {
                    for (int i = 0; i < CELLS_PER_SECTION; i++) {
                        s.denseIds[i] = (short) getShort(raw, pos);
                        pos += 2;
                    }
                    installDenseSection(storage, sec, s);
                }
                default -> throw new IOException("Unknown section tier: " + tier);
            }
        }
        return pos;
    }

    /**
     * Decodes the v1-v4 dense short stream, rebuilding sections wholesale
     * (uniform detection + palette build per 4096-cell slab) instead of the
     * old 65k per-cell {@code storage.set} calls.
     */
    private static CcoBlockStorage decodeDenseLegacy(byte[] payload, int offset, int compLen,
                                                     boolean zstd) throws IOException {
        Scratch s = SCRATCH.get();
        decompressInto(payload, offset, compLen, zstd, s, BLOCK_COUNT * Short.BYTES);

        CcoPalettedChunkStorage storage =
            CcoPalettedChunkStorage.createEmpty(CHUNK_WIDTH, CHUNK_HEIGHT, CHUNK_WIDTH, BlockType.AIR);
        // The legacy global y,z,x cell order is exactly section cell order,
        // section by section.
        for (int sec = 0; sec < SECTION_COUNT; sec++) {
            int base = sec * CELLS_PER_SECTION * 2;
            for (int i = 0; i < CELLS_PER_SECTION; i++) {
                s.denseIds[i] = (short) getShort(s.raw, base + i * 2);
            }
            installDenseSection(storage, sec, s);
        }
        return storage;
    }

    /**
     * Installs one dense-id section: uniform fast path, palette build with a
     * last-id memo (terrain runs), per-cell fallback only if a section somehow
     * exceeds 256 distinct blocks.
     */
    private static void installDenseSection(CcoPalettedChunkStorage storage, int sectionIndex,
                                            Scratch s) {
        short[] ids = s.denseIds;
        short first = ids[0];
        boolean uniform = true;
        for (int i = 1; i < CELLS_PER_SECTION; i++) {
            if (ids[i] != first) {
                uniform = false;
                break;
            }
        }
        if (uniform) {
            BlockType block = blockOrAir(Short.toUnsignedInt(first));
            if (block != BlockType.AIR) {
                storage.replaceSection(sectionIndex,
                    new CcoPaletteSection(CHUNK_WIDTH * CHUNK_WIDTH, block));
            }
            return;
        }

        byte[] cellIndices = new byte[CELLS_PER_SECTION];
        int paletteSize = 0;
        short lastId = Short.MIN_VALUE;
        int lastIdx = -1;
        for (int i = 0; i < CELLS_PER_SECTION; i++) {
            short id = ids[i];
            int idx;
            if (id == lastId) {
                idx = lastIdx;
            } else {
                idx = -1;
                for (int j = 0; j < paletteSize; j++) {
                    if (s.paletteIds[j] == id) {
                        idx = j;
                        break;
                    }
                }
                if (idx < 0) {
                    if (paletteSize == 256) {
                        installDenseSectionPerCell(storage, sectionIndex, ids);
                        return;
                    }
                    s.paletteIds[paletteSize] = id;
                    idx = paletteSize++;
                }
                lastId = id;
                lastIdx = idx;
            }
            cellIndices[i] = (byte) idx;
        }
        for (int j = 0; j < paletteSize; j++) {
            s.paletteBlocks[j] = blockOrAir(Short.toUnsignedInt(s.paletteIds[j]));
        }
        storage.replaceSection(sectionIndex, CcoPaletteSection.fromPaletteData(
            CHUNK_WIDTH * CHUNK_WIDTH, s.paletteBlocks, paletteSize, cellIndices));
    }

    /** Overflow fallback (>256 distinct ids in one section): per-cell writes. */
    private static void installDenseSectionPerCell(CcoPalettedChunkStorage storage,
                                                   int sectionIndex, short[] ids) {
        int yBase = sectionIndex * SECTION_HEIGHT;
        for (int i = 0; i < CELLS_PER_SECTION; i++) {
            BlockType block = blockOrAir(Short.toUnsignedInt(ids[i]));
            if (block == BlockType.AIR) {
                continue;
            }
            int ly = i >> 8;
            int z = (i >> 4) & 15;
            int x = i & 15;
            storage.set(x, yBase + ly, z, block);
        }
    }

    // ═══════════════════════ Compression helpers ═══════════════════════

    /** Deflates {@code s.raw[0..rawLen)} into {@code s.compressed}; returns length. */
    private static int deflateInto(Scratch s, int rawLen) {
        Deflater deflater = s.deflater;
        if (deflater == null) {
            deflater = new Deflater();
            s.deflater = deflater;
        }
        deflater.reset();
        deflater.setInput(s.raw, 0, rawLen);
        deflater.finish();
        int total = 0;
        while (!deflater.finished()) {
            if (total == s.compressed.length) {
                s.compressed = Arrays.copyOf(s.compressed, s.compressed.length * 2);
            }
            total += deflater.deflate(s.compressed, total, s.compressed.length - total);
        }
        return total;
    }

    /** Decompresses {@code payload[offset..offset+compLen)} into {@code s.raw}. */
    private static void decompressInto(byte[] payload, int offset, int compLen, boolean zstd,
                                       Scratch s, int expectedLen) throws IOException {
        if (expectedLen > s.raw.length) {
            throw new IOException("Decompressed block stream too large: " + expectedLen);
        }
        if (zstd) {
            if (!CendaKernels.zstdDecompress(payload, offset, compLen, s.raw, expectedLen)) {
                throw new IOException("zstd block decompression failed");
            }
            return;
        }
        Inflater inflater = s.inflater;
        if (inflater == null) {
            inflater = new Inflater();
            s.inflater = inflater;
        }
        inflater.reset();
        inflater.setInput(payload, offset, compLen);
        int total = 0;
        try {
            while (total < expectedLen && !inflater.finished()) {
                int n = inflater.inflate(s.raw, total, expectedLen - total);
                if (n == 0 && inflater.needsInput()) {
                    break;
                }
                total += n;
            }
            if (total == expectedLen && !inflater.finished()) {
                // Give the inflater a chance to consume the stream trailer;
                // any further output means the stream is longer than expected.
                byte[] probe = new byte[1];
                if (inflater.inflate(probe) > 0) {
                    total++;
                }
            }
        } catch (DataFormatException e) {
            throw new IOException("Corrupt deflate block stream", e);
        }
        if (total != expectedLen || !inflater.finished()) {
            throw new IOException("Unexpected decompressed block stream length: " + total
                + " (expected exactly " + expectedLen + ")");
        }
    }

    // ═══════════════════════ Primitive helpers ═══════════════════════

    private static int putShort(byte[] dst, int pos, int value) {
        dst[pos] = (byte) (value >>> 8);
        dst[pos + 1] = (byte) value;
        return pos + 2;
    }

    private static int getShort(byte[] src, int pos) {
        return ((src[pos] & 0xFF) << 8) | (src[pos + 1] & 0xFF);
    }

    private static int blockId(IBlockType block) {
        return block != null ? block.getId() : BlockType.AIR.getId();
    }

    private static BlockType blockOrAir(int id) {
        BlockType block = BlockType.getById(id);
        return block != null ? block : BlockType.AIR;
    }

    // ═══════════════════════ Tail sections ═══════════════════════

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
