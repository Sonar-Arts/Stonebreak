package com.stonebreak.world.save.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.CRC32C;

/**
 * Read-only view of the region files of a world's native cube store ({@code <world>/cubes}, OpenMason #120
 * {@code cv_store}): for world statistics and save diagnostics, without opening the store (a running world holds
 * it). Parses only the COMMITTED header of each file - what a crash right now would keep - per the format
 * documented in {@code cenda/native/kernels/src/voxel_store/region_file.hpp}:
 * <ul>
 *   <li>{@code r.<rx>.<ry>.<rz>.cvr}: 8x8x8 cubes, index {@code (cx&7) | (cz&7)<<3 | (cy&7)<<6};</li>
 *   <li>{@code c.<rx>.<rz>.cvc}: 32x32 column records, index {@code (cx&31) | (cz&31)<<5};</li>
 *   <li>two header slots (4 / 7 sectors of 4 KiB), little-endian: magic 'CVRG', u16 version 1, u16 kind,
 *       u32 cube edge, u32 region edge, u32 entry count, u32 slot sectors, u64 generation, i64 rx/ry/rz,
 *       u32 sector size, u32 reserved, entries of 24 bytes (u64 offset, u32 stored, u32 raw, u32 crc32c,
 *       u32 flags: 1 present), then the crc32c of everything before it. The valid slot with the higher
 *       generation is the committed one.</li>
 * </ul>
 */
public final class CubeRegionFiles {

    public static final int KIND_CUBE = 1;
    public static final int KIND_COLUMN = 2;

    private static final int MAGIC = 0x47525643; // 'C' 'V' 'R' 'G' read little-endian
    private static final int VERSION = 1;
    private static final int SECTOR = 4096;
    private static final int HEADER_FIXED = 64;
    private static final int ENTRY_BYTES = 24;
    private static final int FLAG_PRESENT = 1;

    private CubeRegionFiles() {
    }

    /**
     * The committed header of one region file.
     *
     * @param presentIndexes entry indexes holding a record
     * @param storedBytes    sum of the records' stored (compressed) sizes
     * @param rawBytes       sum of the records' decompressed sizes
     * @param otherSlotValid whether the second header slot is valid too (normal after two commits)
     */
    public record Header(Path file, int kind, int cubeEdge, int regionEdge, long generation, long rx, long ry,
                         long rz, int[] presentIndexes, long storedBytes, long rawBytes, boolean otherSlotValid) {

        public int recordCount() {
            return presentIndexes.length;
        }

        /** (cx, cy, cz) of every present cube record of a cube region, as triples. */
        public long[] cubeKeys() {
            if (kind != KIND_CUBE) {
                throw new IllegalStateException("not a cube region: " + file);
            }
            long[] out = new long[presentIndexes.length * 3];
            for (int i = 0; i < presentIndexes.length; i++) {
                int idx = presentIndexes[i];
                out[i * 3] = rx * regionEdge + (idx & 7);
                out[i * 3 + 1] = ry * regionEdge + ((idx >> 6) & 7);
                out[i * 3 + 2] = rz * regionEdge + ((idx >> 3) & 7);
            }
            return out;
        }
    }

    /** Thrown for a file with no valid header slot (both torn / not a region file / other version). */
    public static final class UnreadableRegionException extends IOException {
        public UnreadableRegionException(Path file, String why) {
            super(file.getFileName() + ": " + why);
        }
    }

    /** Every region file directly in {@code cubesDir} (cube {@code .cvr} and column {@code .cvc}). */
    public static List<Path> regionFiles(Path cubesDir) throws IOException {
        if (!Files.isDirectory(cubesDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(cubesDir)) {
            return files.filter(p -> {
                String n = p.getFileName().toString();
                return (n.endsWith(".cvr") || n.endsWith(".cvc")) && Files.isRegularFile(p);
            }).sorted().toList();
        }
    }

    /** Reads the committed header of a region file. */
    public static Header read(Path file) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = ch.size();
            Slot a = readSlot(ch, 0, size);
            int slotSectors = a != null ? a.slotSectors : (file.getFileName().toString().endsWith(".cvc") ? 7 : 4);
            Slot b = readSlot(ch, (long) slotSectors * SECTOR, size);
            if (a == null && b == null) {
                throw new UnreadableRegionException(file, "no valid header slot");
            }
            Slot best = a == null ? b : b == null ? a : (b.generation > a.generation ? b : a);
            return new Header(file, best.kind, best.cubeEdge, best.regionEdge, best.generation, best.rx, best.ry,
                best.rz, best.present, best.stored, best.raw, a != null && b != null);
        }
    }

    /** Number of cube records in the committed headers of every cube region (unreadable files count 0). */
    public static long countCubes(Path cubesDir) {
        long n = 0;
        try {
            for (Path f : regionFiles(cubesDir)) {
                if (!f.getFileName().toString().endsWith(".cvr")) {
                    continue;
                }
                try {
                    n += read(f).recordCount();
                } catch (IOException | RuntimeException e) {
                    // damaged file: the store sets it aside on its next write; not counted
                }
            }
        } catch (IOException e) {
            return n;
        }
        return n;
    }

    private record Slot(int kind, int cubeEdge, int regionEdge, int slotSectors, long generation, long rx, long ry,
                        long rz, int[] present, long stored, long raw) {
    }

    private static Slot readSlot(FileChannel ch, long offset, long size) throws IOException {
        if (offset + HEADER_FIXED > size) {
            return null;
        }
        ByteBuffer fixed = ByteBuffer.allocate(HEADER_FIXED).order(ByteOrder.LITTLE_ENDIAN);
        readFully(ch, fixed, offset);
        fixed.flip();
        if (fixed.getInt(0) != MAGIC || (fixed.getShort(4) & 0xFFFF) != VERSION) {
            return null;
        }
        int kind = fixed.getShort(6) & 0xFFFF;
        int cubeEdge = fixed.getInt(8);
        int regionEdge = fixed.getInt(12);
        int count = fixed.getInt(16);
        int slotSectors = fixed.getInt(20);
        if ((kind != KIND_CUBE && kind != KIND_COLUMN) || count <= 0 || count > 4096 || slotSectors <= 0
                || slotSectors > 64) {
            return null;
        }
        int bodyLen = HEADER_FIXED + ENTRY_BYTES * count;
        if (bodyLen + 4 > (long) slotSectors * SECTOR || offset + bodyLen + 4 > size) {
            return null;
        }
        ByteBuffer body = ByteBuffer.allocate(bodyLen + 4).order(ByteOrder.LITTLE_ENDIAN);
        readFully(ch, body, offset);
        body.flip();
        CRC32C crc = new CRC32C();
        crc.update(body.array(), 0, bodyLen);
        if ((int) crc.getValue() != body.getInt(bodyLen)) {
            return null;
        }
        long generation = body.getLong(24);
        long rx = body.getLong(32);
        long ry = body.getLong(40);
        long rz = body.getLong(48);
        List<Integer> present = new ArrayList<>();
        long stored = 0;
        long raw = 0;
        for (int i = 0; i < count; i++) {
            int e = HEADER_FIXED + i * ENTRY_BYTES;
            int flags = body.getInt(e + 20);
            if ((flags & FLAG_PRESENT) != 0) {
                present.add(i);
                stored += Integer.toUnsignedLong(body.getInt(e + 8));
                raw += Integer.toUnsignedLong(body.getInt(e + 12));
            }
        }
        return new Slot(kind, cubeEdge, regionEdge, slotSectors, generation, rx, ry, rz,
            present.stream().mapToInt(Integer::intValue).toArray(), stored, raw);
    }

    private static void readFully(FileChannel ch, ByteBuffer buf, long offset) throws IOException {
        long pos = offset;
        while (buf.hasRemaining()) {
            int n = ch.read(buf, pos);
            if (n < 0) {
                throw new IOException("unexpected end of file");
            }
            pos += n;
        }
    }
}
