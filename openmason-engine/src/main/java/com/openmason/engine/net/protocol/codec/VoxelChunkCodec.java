package com.openmason.engine.net.protocol.codec;

import com.openmason.engine.net.replication.BlockSetter;
import com.openmason.engine.net.replication.IBlockTypeResolver;
import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.IVoxelChunkData;

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
 * <p>A chunk (16×1024×16) is split into 64 vertical sections of 16×16×16. Each section is
 * encoded as one of:
 * <ul>
 *   <li><b>SINGLE (tag 0)</b> + short blockId — all 4096 blocks identical (the typical
 *       case for sky and bedrock layers; encodes in 3 bytes).</li>
 *   <li><b>PALETTED (tag 1)</b> + palette + bit-packed indices — palette holds 2-256
 *       distinct ids, indices use {@code ceil(log2(paletteSize))} bits packed in
 *       {@code long[]} without crossing long boundaries (MC 1.16+ style).</li>
 *   <li><b>DIRECT (tag 2)</b> + short[4096] — fallback when a section needs more than 256
 *       distinct ids. Realistically never triggered.</li>
 * </ul>
 *
 * <p>Index order is YZX (Y outermost) so terrain layers compress well and the bit-pack
 * walk is cache-friendly. Typical real chunks ship in 1-3 KB.
 *
 * <p>This is the engine-side re-derivation of the game's legacy {@code NetworkChunkCodec}:
 * it reads through {@link IVoxelChunkData} and writes through a {@link BlockSetter} sink
 * plus an {@link IBlockTypeResolver}, so it carries no dependency on a concrete block enum.
 * The encoded form is a self-contained {@code byte[]} blob (carried as a packet field).
 */
public final class VoxelChunkCodec {

    public static final int CHUNK_W = 16;
    public static final int CHUNK_H = 1024;
    public static final int SECTION_H = 16;
    public static final int SECTIONS_PER_CHUNK = CHUNK_H / SECTION_H;
    public static final int BLOCKS_PER_SECTION = CHUNK_W * SECTION_H * CHUNK_W;

    private static final byte TAG_SINGLE   = 0;
    private static final byte TAG_PALETTED = 1;
    private static final byte TAG_DIRECT   = 2;

    private static final int MAX_PALETTE_ENTRIES = 256;

    /** Hard cap on encoded payload size — protects decode against malicious payloads. */
    private static final int MAX_ENCODED_BYTES = 200 * 1024;

    private VoxelChunkCodec() {}

    public static byte[] encode(IVoxelChunkData chunk) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(4 * 1024);
        try (DataOutputStream out = new DataOutputStream(buf)) {
            for (int sy = 0; sy < SECTIONS_PER_CHUNK; sy++) {
                encodeSection(chunk, sy, out);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Chunk encode failed", e);
        }
        return buf.toByteArray();
    }

    public static void decodeInto(byte[] payload, BlockSetter sink, IBlockTypeResolver resolver) {
        if (payload.length > MAX_ENCODED_BYTES) {
            throw new IllegalArgumentException("Chunk payload too large: " + payload.length);
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            for (int sy = 0; sy < SECTIONS_PER_CHUNK; sy++) {
                decodeSection(sink, resolver, sy, in);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Malformed chunk payload", e);
        }
    }

    // ─── Section encode ─────────────────────────────────────────────────────

    private static void encodeSection(IVoxelChunkData chunk, int sectionY, DataOutputStream out) throws IOException {
        int yBase = sectionY * SECTION_H;

        // Build palette + index array. Bail to DIRECT if we exceed 256 entries.
        short[] indices = new short[BLOCKS_PER_SECTION];
        short[] palette = new short[MAX_PALETTE_ENTRIES];
        Map<Short, Integer> paletteMap = new HashMap<>();
        int paletteSize = 0;
        boolean direct = false;

        for (int i = 0; i < BLOCKS_PER_SECTION; i++) {
            int x = i & 15;
            int z = (i >> 4) & 15;
            int y = (i >> 8) & 15;
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
            for (int i = 0; i < BLOCKS_PER_SECTION; i++) {
                int x = i & 15;
                int z = (i >> 4) & 15;
                int y = (i >> 8) & 15;
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
        int longCount = (BLOCKS_PER_SECTION + blocksPerLong - 1) / blocksPerLong;
        long mask = (1L << bitsPerBlock) - 1L;
        long[] data = new long[longCount];
        for (int i = 0; i < BLOCKS_PER_SECTION; i++) {
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

    private static void decodeSection(BlockSetter sink, IBlockTypeResolver resolver, int sectionY, DataInputStream in) throws IOException {
        int yBase = sectionY * SECTION_H;
        byte tag = in.readByte();
        switch (tag) {
            case TAG_SINGLE -> {
                IBlockType b = resolver.byId(in.readShort() & 0xFFFF);
                for (int i = 0; i < BLOCKS_PER_SECTION; i++) {
                    sink.setBlock(i & 15, yBase + ((i >> 8) & 15), (i >> 4) & 15, b);
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
                if (bitsPerBlock < 1 || bitsPerBlock > 16) {
                    throw new IOException("Invalid bitsPerBlock: " + bitsPerBlock);
                }
                int blocksPerLong = 64 / bitsPerBlock;
                int expectedLongs = (BLOCKS_PER_SECTION + blocksPerLong - 1) / blocksPerLong;
                int longCount = in.readInt();
                if (longCount != expectedLongs) {
                    throw new IOException("Bad long count: got " + longCount + ", expected " + expectedLongs);
                }
                long mask = (1L << bitsPerBlock) - 1L;
                long[] data = new long[longCount];
                for (int i = 0; i < longCount; i++) data[i] = in.readLong();
                for (int i = 0; i < BLOCKS_PER_SECTION; i++) {
                    int li = i / blocksPerLong;
                    int slot = i % blocksPerLong;
                    int p = (int) ((data[li] >>> (slot * bitsPerBlock)) & mask);
                    if (p >= paletteSize) {
                        throw new IOException("Palette index out of range: " + p);
                    }
                    sink.setBlock(i & 15, yBase + ((i >> 8) & 15), (i >> 4) & 15, palette[p]);
                }
            }
            case TAG_DIRECT -> {
                for (int i = 0; i < BLOCKS_PER_SECTION; i++) {
                    IBlockType b = resolver.byId(in.readShort() & 0xFFFF);
                    sink.setBlock(i & 15, yBase + ((i >> 8) & 15), (i >> 4) & 15, b);
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
