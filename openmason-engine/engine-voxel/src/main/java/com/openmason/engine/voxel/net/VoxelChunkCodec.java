package com.openmason.engine.voxel.net;

import com.openmason.engine.net.replication.BlockSetter;
import com.openmason.engine.net.replication.IBlockTypeResolver;
import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.IVoxelChunkData;
import com.openmason.engine.voxel.VoxelWorldConfig;
import com.openmason.engine.voxel.cco.coordinates.CcoBounds;
import com.openmason.engine.voxel.cco.data.palette.CcoSectionIndexing;
import com.openmason.engine.voxel.cco.data.palette.CcoPaletteSection;
import com.openmason.engine.voxel.cco.data.palette.CcoPalettedChunkStorage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Section-paletted chunk serialization, modelled on Minecraft's chunk format.
 *
 * <p>A chunk ({@code chunkSize × worldHeight × chunkSize}, from the world's
 * {@link VoxelWorldConfig}, #385) is split into vertical sections {@link #SECTION_H} blocks tall.
 * Each section is encoded as one of:
 * <ul>
 *   <li><b>SINGLE (tag 0)</b> + short blockId — every block of the section identical (the
 *       typical case for sky and bedrock layers; encodes in 3 bytes).</li>
 *   <li><b>PALETTED (tag 1)</b> + palette + bit-packed indices — palette holds 2-256
 *       distinct ids, indices use {@code ceil(log2(paletteSize))} bits packed in
 *       {@code long[]} without crossing long boundaries (MC 1.16+ style).</li>
 *   <li><b>DIRECT (tag 2)</b> + one short per block — fallback when a section needs more than 256
 *       distinct ids. Realistically never triggered.</li>
 * </ul>
 *
 * <p>Index order is YZX (Y outermost) so terrain layers compress well and the bit-pack
 * walk is cache-friendly. Typical real chunks ship in 1-3 KB.
 *
 * <p>This is the engine-side re-derivation of the game's legacy {@code NetworkChunkCodec}:
 * it reads through {@link IVoxelChunkData} and writes through a {@link BlockSetter} sink
 * plus an {@link IBlockTypeResolver}, so it carries no dependency on a concrete block enum.
 * The encoded form is a self-contained {@code byte[]} blob (carried as a packet field). It carries
 * no dimensions: both ends decode with the same {@link Layout} (the installed world config).
 */
public final class VoxelChunkCodec {

    /** Section height in blocks: the paletted storage's ({@link CcoSectionIndexing#SECTION_HEIGHT}). */
    public static final int SECTION_H = CcoSectionIndexing.SECTION_HEIGHT;

    /**
     * The chunk geometry a payload is written and read with.
     *
     * @param chunkSize   blocks per chunk side (x and z)
     * @param worldHeight blocks per column; a multiple of {@link #SECTION_H}
     */
    public record Layout(int chunkSize, int worldHeight) {

        public Layout {
            if (chunkSize <= 0 || worldHeight <= 0 || worldHeight % SECTION_H != 0) {
                throw new IllegalArgumentException("chunk layout " + chunkSize + "x" + worldHeight + "x" + chunkSize
                    + " (height must be a positive multiple of " + SECTION_H + ")");
            }
        }

        /** The layout of a world. */
        public static Layout of(VoxelWorldConfig config) {
            return new Layout(config.chunkSize(), config.worldHeight());
        }

        /** The installed world's layout ({@link CcoBounds#getConfig()}). */
        public static Layout current() {
            return of(CcoBounds.getConfig());
        }

        public int sections() {
            return worldHeight / SECTION_H;
        }

        public int blocksPerSection() {
            return chunkSize * chunkSize * SECTION_H;
        }

        /**
         * Decode refuses larger payloads (a malicious-payload guard): an all-DIRECT chunk plus
         * slack, never below the {@link #MIN_ENCODED_CAP} the 16×256 world always allowed.
         */
        public int maxEncodedBytes() {
            long worstCase = (long) sections() * (1 + 2L * blocksPerSection()) + 4096;
            return (int) Math.min(Integer.MAX_VALUE, Math.max(MIN_ENCODED_CAP, worstCase));
        }
    }

    private static final byte TAG_SINGLE   = 0;
    private static final byte TAG_PALETTED = 1;
    private static final byte TAG_DIRECT   = 2;

    /** Palette entries a PALETTED section can hold: its size is one wire byte (0 = the maximum). */
    private static final int MAX_PALETTE_ENTRIES = 1 << Byte.SIZE;

    /** Smallest decode cap on an encoded payload (see {@link Layout#maxEncodedBytes()}). */
    private static final int MIN_ENCODED_CAP = 200 * 1024;

    /**
     * Bulk-encode scratch, grown to the largest section seen on the thread. Wire output is
     * byte-identical to the per-cell path.
     */
    private static final class Scratch {
        short[] ids = new short[0];
        short[] indices = new short[0];
        byte[] byteIndices = new byte[0];
        final short[] paletteIds = new short[MAX_PALETTE_ENTRIES];

        Scratch fit(int blocksPerSection) {
            if (ids.length < blocksPerSection) {
                ids = new short[blocksPerSection];
                indices = new short[blocksPerSection];
                byteIndices = new byte[blocksPerSection];
            }
            return this;
        }
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);
    // The id→palette-index lookup table is reset entry-by-entry after each section (never
    // bulk-cleared — only touched slots are dirtied). Sized by the 16-bit block id space.
    private static final ThreadLocal<int[]> PALETTE_LOOKUP = ThreadLocal.withInitial(() -> {
        int[] lookup = new int[65536];
        java.util.Arrays.fill(lookup, -1);
        return lookup;
    });

    private VoxelChunkCodec() {}

    /** {@link #encode(IVoxelChunkData, Layout)} with the installed world's layout. */
    public static byte[] encode(IVoxelChunkData chunk) {
        return encode(chunk, Layout.current());
    }

    public static byte[] encode(IVoxelChunkData chunk, Layout layout) {
        com.openmason.engine.voxel.IVoxelChunkSections sections =
            chunk instanceof com.openmason.engine.voxel.IVoxelChunkSections s ? s : null;
        Scratch scratch = SCRATCH.get().fit(layout.blocksPerSection());
        short[] ids = sections != null ? scratch.ids : null;
        ByteArrayOutputStream buf = new ByteArrayOutputStream(4 * 1024);
        try (DataOutputStream out = new DataOutputStream(buf)) {
            for (int sy = 0; sy < layout.sections(); sy++) {
                if (sections != null && sections.copySectionBlockIds(sy, ids)) {
                    encodeSectionFromIds(ids, layout, scratch, out);
                } else {
                    encodeSection(chunk, sy, layout, out);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Chunk encode failed", e);
        }
        return buf.toByteArray();
    }

    /**
     * Encodes paletted storage straight from its sections: a uniform section is written as
     * SINGLE without touching its cells, and an indexed section ships its own palette and
     * indices instead of being expanded to per-cell ids and re-paletted. Decodes to the same blocks
     * as {@link #encode(IVoxelChunkData)}; the bytes may differ (palette order, and a palette
     * may carry an entry no cell uses any more — palettes never shrink).
     *
     * <p>Reads each section's current state once, so it is safe on live storage; on a
     * copy-on-write snapshot ({@code storage.copy()}) it can run on any thread. The layout is the
     * storage's own shape (its width and section count).
     */
    public static byte[] encode(CcoPalettedChunkStorage storage) {
        return encode(storage, new Layout(storage.getSizeX(), storage.getSectionCount() * SECTION_H));
    }

    public static byte[] encode(CcoPalettedChunkStorage storage, Layout layout) {
        if (storage.getSectionCount() != layout.sections()
                || storage.getSizeX() != layout.chunkSize() || storage.getSizeZ() != layout.chunkSize()) {
            throw new IllegalArgumentException("Storage is not a " + layout.chunkSize() + "x"
                + layout.worldHeight() + "x" + layout.chunkSize() + " chunk: " + storage);
        }
        Scratch scratch = SCRATCH.get().fit(layout.blocksPerSection());
        short[] paletteIds = scratch.paletteIds;
        byte[] indices = scratch.byteIndices;
        ByteArrayOutputStream buf = new ByteArrayOutputStream(4 * 1024);
        try (DataOutputStream out = new DataOutputStream(buf)) {
            for (int sy = 0; sy < layout.sections(); sy++) {
                CcoPaletteSection section = storage.getSection(sy);
                int paletteSize = section.snapshotPaletteData(paletteIds, indices);
                if (paletteSize == 0 || paletteSize == 1) {
                    out.writeByte(TAG_SINGLE);
                    out.writeShort(paletteIds[0]);
                } else if (paletteSize > 0) {
                    writePaletted(paletteIds, paletteSize, indices, layout.blocksPerSection(), out);
                } else {
                    // Short-indexed overflow tier (> MAX_PALETTE_ENTRIES entries): dense ids.
                    short[] ids = scratch.ids;
                    section.writeBlockIdsInto(ids, 0);
                    encodeSectionFromIds(ids, layout, scratch, out);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Chunk encode failed", e);
        }
        return buf.toByteArray();
    }

    private static void writePaletted(short[] palette, int paletteSize, byte[] indices, int blocks,
                                      DataOutputStream out) throws IOException {
        int bitsPerBlock = bitsForPalette(paletteSize);
        int blocksPerLong = 64 / bitsPerBlock;
        int longCount = (blocks + blocksPerLong - 1) / blocksPerLong;
        out.writeByte(TAG_PALETTED);
        out.writeByte(paletteSize == MAX_PALETTE_ENTRIES ? 0 : paletteSize);
        for (int i = 0; i < paletteSize; i++) out.writeShort(palette[i]);
        out.writeByte(bitsPerBlock);
        out.writeInt(longCount);
        int i = 0;
        for (int li = 0; li < longCount; li++) {
            long v = 0L;
            for (int slot = 0; slot < blocksPerLong && i < blocks; slot++, i++) {
                v |= (long) (indices[i] & 0xFF) << (slot * bitsPerBlock);
            }
            out.writeLong(v);
        }
    }

    /** {@link #decodeInto(byte[], BlockSetter, IBlockTypeResolver, Layout)} with the installed world's layout. */
    public static void decodeInto(byte[] payload, BlockSetter sink, IBlockTypeResolver resolver) {
        decodeInto(payload, sink, resolver, Layout.current());
    }

    public static void decodeInto(byte[] payload, BlockSetter sink, IBlockTypeResolver resolver, Layout layout) {
        if (payload.length > layout.maxEncodedBytes()) {
            throw new IllegalArgumentException("Chunk payload too large: " + payload.length);
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            for (int sy = 0; sy < layout.sections(); sy++) {
                decodeSection(sink, resolver, sy, layout, in);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Malformed chunk payload", e);
        }
    }

    // ─── Section encode ─────────────────────────────────────────────────────

    /**
     * Bulk section encode over a flat id array (section cell order == wire
     * order). Produces byte-identical output to the per-cell path: same
     * first-encounter palette order, same packing.
     */
    private static void encodeSectionFromIds(short[] ids, Layout layout, Scratch scratch, DataOutputStream out)
            throws IOException {
        final int blocks = layout.blocksPerSection();
        int[] lookup = PALETTE_LOOKUP.get();
        short[] indices = scratch.indices;
        short[] palette = new short[MAX_PALETTE_ENTRIES];
        int paletteSize = 0;
        boolean direct = false;

        for (int i = 0; i < blocks; i++) {
            int id = ids[i] & 0xFFFF;
            int pi = lookup[id];
            if (pi < 0) {
                if (paletteSize >= MAX_PALETTE_ENTRIES) { direct = true; break; }
                pi = paletteSize;
                lookup[id] = pi;
                palette[paletteSize++] = (short) id;
            }
            indices[i] = (short) pi;
        }
        for (int p = 0; p < paletteSize; p++) {
            lookup[palette[p] & 0xFFFF] = -1;
        }

        if (direct) {
            out.writeByte(TAG_DIRECT);
            for (int i = 0; i < blocks; i++) {
                out.writeShort(ids[i]);
            }
            return;
        }

        if (paletteSize == 1) {
            out.writeByte(TAG_SINGLE);
            out.writeShort(palette[0]);
            return;
        }

        int bitsPerBlock = bitsForPalette(paletteSize);
        int blocksPerLong = 64 / bitsPerBlock;
        int longCount = (blocks + blocksPerLong - 1) / blocksPerLong;
        long mask = (1L << bitsPerBlock) - 1L;
        long[] data = new long[longCount];
        for (int i = 0; i < blocks; i++) {
            int li = i / blocksPerLong;
            int slot = i % blocksPerLong;
            data[li] |= ((long) (indices[i] & 0xFFFF) & mask) << (slot * bitsPerBlock);
        }

        out.writeByte(TAG_PALETTED);
        out.writeByte(paletteSize == MAX_PALETTE_ENTRIES ? 0 : paletteSize);
        for (int i = 0; i < paletteSize; i++) out.writeShort(palette[i]);
        out.writeByte(bitsPerBlock);
        out.writeInt(longCount);
        for (long v : data) out.writeLong(v);
    }

    private static void encodeSection(IVoxelChunkData chunk, int sectionY, Layout layout, DataOutputStream out)
            throws IOException {
        final int blocks = layout.blocksPerSection();
        final int cs = layout.chunkSize();
        int yBase = sectionY * SECTION_H;

        // Build palette + index array. Bail to DIRECT past MAX_PALETTE_ENTRIES.
        short[] indices = new short[blocks];
        short[] palette = new short[MAX_PALETTE_ENTRIES];
        Map<Short, Integer> paletteMap = new HashMap<>();
        int paletteSize = 0;
        boolean direct = false;

        for (int i = 0; i < blocks; i++) {
            int x = i % cs;
            int z = (i / cs) % cs;
            int y = i / (cs * cs);
            short id = blockId(chunk.getBlock(x, yBase + y, z));
            Integer pi = paletteMap.get(id);
            if (pi == null) {
                if (paletteSize >= MAX_PALETTE_ENTRIES) { direct = true; break; }
                pi = paletteSize;
                paletteMap.put(id, pi);
                palette[paletteSize++] = id;
            }
            indices[i] = pi.shortValue();
        }

        if (direct) {
            out.writeByte(TAG_DIRECT);
            for (int i = 0; i < blocks; i++) {
                int x = i % cs;
                int z = (i / cs) % cs;
                int y = i / (cs * cs);
                out.writeShort(blockId(chunk.getBlock(x, yBase + y, z)));
            }
            return;
        }

        if (paletteSize == 1) {
            out.writeByte(TAG_SINGLE);
            out.writeShort(palette[0]);
            return;
        }

        int bitsPerBlock = bitsForPalette(paletteSize);
        int blocksPerLong = 64 / bitsPerBlock;
        int longCount = (blocks + blocksPerLong - 1) / blocksPerLong;
        long mask = (1L << bitsPerBlock) - 1L;
        long[] data = new long[longCount];
        for (int i = 0; i < blocks; i++) {
            int li = i / blocksPerLong;
            int slot = i % blocksPerLong;
            data[li] |= ((long) (indices[i] & 0xFFFF) & mask) << (slot * bitsPerBlock);
        }

        out.writeByte(TAG_PALETTED);
        // paletteSize encoded as unsigned byte; 0 means 256 (max).
        out.writeByte(paletteSize == MAX_PALETTE_ENTRIES ? 0 : paletteSize);
        for (int i = 0; i < paletteSize; i++) out.writeShort(palette[i]);
        out.writeByte(bitsPerBlock);
        out.writeInt(longCount);
        for (long v : data) out.writeLong(v);
    }

    // ─── Section decode ─────────────────────────────────────────────────────

    private static void decodeSection(BlockSetter sink, IBlockTypeResolver resolver, int sectionY, Layout layout,
                                      DataInputStream in) throws IOException {
        final int blocks = layout.blocksPerSection();
        final int cs = layout.chunkSize();
        final int layer = cs * cs;
        int yBase = sectionY * SECTION_H;
        byte tag = in.readByte();
        switch (tag) {
            case TAG_SINGLE -> {
                IBlockType b = resolver.byId(in.readShort() & 0xFFFF);
                if (sink.setSectionUniform(sectionY, b)) {
                    return;
                }
                for (int i = 0; i < blocks; i++) {
                    sink.setBlock(i % cs, yBase + i / layer, (i / cs) % cs, b);
                }
            }
            case TAG_PALETTED -> {
                int paletteSize = in.readByte() & 0xFF;
                if (paletteSize == 0) paletteSize = MAX_PALETTE_ENTRIES;
                IBlockType[] palette = new IBlockType[paletteSize];
                for (int i = 0; i < paletteSize; i++) {
                    palette[i] = resolver.byId(in.readShort() & 0xFFFF);
                }
                int bitsPerBlock = in.readByte() & 0xFF;
                if (bitsPerBlock < 1 || bitsPerBlock > Short.SIZE) { // ids are 16-bit
                    throw new IOException("Invalid bitsPerBlock: " + bitsPerBlock);
                }
                int blocksPerLong = 64 / bitsPerBlock;
                int expectedLongs = (blocks + blocksPerLong - 1) / blocksPerLong;
                int longCount = in.readInt();
                if (longCount != expectedLongs) {
                    throw new IOException("Bad long count: got " + longCount + ", expected " + expectedLongs);
                }
                long mask = (1L << bitsPerBlock) - 1L;
                long[] data = new long[longCount];
                for (int i = 0; i < longCount; i++) data[i] = in.readLong();
                // Unpack once into a byte index array (palette ≤ 256 always fits),
                // then hand the whole section to the sink's bulk path when it
                // has one — per-cell setBlock only as fallback.
                byte[] cellIndices = new byte[blocks];
                for (int i = 0; i < blocks; i++) {
                    int li = i / blocksPerLong;
                    int slot = i % blocksPerLong;
                    int p = (int) ((data[li] >>> (slot * bitsPerBlock)) & mask);
                    if (p >= paletteSize) {
                        throw new IOException("Palette index out of range: " + p);
                    }
                    cellIndices[i] = (byte) p;
                }
                if (sink.setSectionPaletted(sectionY, palette, cellIndices)) {
                    return;
                }
                for (int i = 0; i < blocks; i++) {
                    sink.setBlock(i % cs, yBase + i / layer, (i / cs) % cs,
                        palette[cellIndices[i] & 0xFF]);
                }
            }
            case TAG_DIRECT -> {
                for (int i = 0; i < blocks; i++) {
                    IBlockType b = resolver.byId(in.readShort() & 0xFFFF);
                    sink.setBlock(i % cs, yBase + i / layer, (i / cs) % cs, b);
                }
            }
            default -> throw new IOException("Unknown section tag: " + tag);
        }
    }

    private static short blockId(IBlockType b) {
        return (short) (b == null ? 0 : b.getId());
    }

    /** Minimum bits to address {@code paletteSize} entries, capped at 1. */
    private static int bitsForPalette(int paletteSize) {
        if (paletteSize <= 2) return 1;
        return 32 - Integer.numberOfLeadingZeros(paletteSize - 1);
    }
}
