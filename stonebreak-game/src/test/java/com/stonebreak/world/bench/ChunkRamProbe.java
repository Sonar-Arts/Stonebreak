package com.stonebreak.world.bench;

import com.openmason.engine.voxel.cco.data.palette.CcoPaletteSection;
import com.openmason.engine.voxel.cco.data.palette.CcoPalettedChunkStorage;
import com.stonebreak.world.chunk.Chunk;

/**
 * Estimates the heap bytes one chunk's persistent data occupies: the paletted
 * block storage (per section tier), the height map and the sparse water
 * layer. Numbers are a model of the JVM layout (compressed oops, 16-byte
 * array headers, 8-byte alignment) — deterministic, so two runs of the same
 * code compare exactly, and close enough to a heap dump to rank changes.
 */
final class ChunkRamProbe {

    /** Per-chunk breakdown. All fields in bytes unless named otherwise. */
    record Result(long blockStorageBytes, long heightMapBytes, long waterLayerBytes,
                  int uniformSections, int byteSections, int shortSections, int nibbleSections,
                  int maxPaletteSize, int waterCells) {
        long totalBytes() {
            return blockStorageBytes + heightMapBytes + waterLayerBytes;
        }
    }

    private static final int OBJ_HEADER = 16;          // mark + compressed klass, 8-aligned
    private static final int ARRAY_HEADER = 16;        // header + length, 8-aligned
    private static final int REF = 4;                  // compressed oop
    private static final int SECTION_OBJ = align(OBJ_HEADER + 4 + 4 + REF + 4);   // cellsPerLayer, volume, state, nonAirCount
    private static final int STATE_OBJ = align(OBJ_HEADER + REF * 3 + 1);         // palette, indices, wide, shared
    private static final int CELLS = 4096;

    private final short[] paletteIds = new short[256];
    private final byte[] indices = new byte[CELLS];

    Result probe(Chunk chunk) {
        CcoPalettedChunkStorage storage = (CcoPalettedChunkStorage) chunk.getBlockStorageView();
        long blockBytes = align(OBJ_HEADER + REF + 4 * 3)                    // storage object
            + ARRAY_HEADER + REF * storage.getSectionCount();                // sections[]
        int uniform = 0, byteTier = 0, shortTier = 0, nibbleTier = 0, maxPalette = 0;
        for (int s = 0; s < storage.getSectionCount(); s++) {
            CcoPaletteSection section = storage.getSection(s);
            int r = section.snapshotPaletteData(paletteIds, indices);
            blockBytes += SECTION_OBJ + STATE_OBJ + REF; // State gained the nibbles ref
            if (r == 0) {
                uniform++;
                blockBytes += align(ARRAY_HEADER + REF);                     // palette[1]
                maxPalette = Math.max(maxPalette, 1);
            } else if (r > 0 && section.isNibbleTier()) {
                nibbleTier++;
                blockBytes += align(ARRAY_HEADER + REF * r) + align(ARRAY_HEADER + CELLS / 2);
                maxPalette = Math.max(maxPalette, r);
            } else if (r > 0) {
                byteTier++;
                blockBytes += align(ARRAY_HEADER + REF * r) + align(ARRAY_HEADER + CELLS);
                maxPalette = Math.max(maxPalette, r);
            } else {
                shortTier++;
                int palette = paletteSizeOfWide(section);
                blockBytes += align(ARRAY_HEADER + REF * palette) + align(ARRAY_HEADER + CELLS * 2);
                maxPalette = Math.max(maxPalette, palette);
            }
        }
        long heightBytes = chunk.getHeightMap() == null ? 0 : chunk.getHeightMap().getMemoryUsageBytes();
        int waterCells = chunk.getWaterLayer() == null ? 0 : chunk.getWaterLayer().size();
        // ConcurrentHashMap<Integer,Byte>: node (32) + boxed Integer (16) per entry, Byte is cached;
        // table of ≥16 refs sized to the next pow2 over cells/0.75.
        long waterBytes = waterCells == 0 ? 0
            : align(OBJ_HEADER + 48) + ARRAY_HEADER + REF * tableSize(waterCells) + waterCells * (32L + 16L);
        return new Result(blockBytes, heightBytes, waterBytes, uniform, byteTier, shortTier, nibbleTier,
            maxPalette, waterCells);
    }

    private static int paletteSizeOfWide(CcoPaletteSection section) {
        // toString() is the only public view of the wide tier's palette length.
        String s = section.toString();
        int i = s.indexOf("palette=");
        if (i < 0) {
            return 257;
        }
        int j = s.indexOf(',', i);
        return Integer.parseInt(s.substring(i + 8, j < 0 ? s.length() - 1 : j));
    }

    private static int tableSize(int entries) {
        int n = 16;
        while (n * 0.75 < entries) {
            n <<= 1;
        }
        return n;
    }

    private static int align(int bytes) {
        return (bytes + 7) & ~7;
    }
}
