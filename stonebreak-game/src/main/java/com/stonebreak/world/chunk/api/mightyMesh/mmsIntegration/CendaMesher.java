package com.stonebreak.world.chunk.api.mightyMesh.mmsIntegration;

import com.openmason.engine.cenda.CendaKernels;
import com.openmason.engine.voxel.cco.core.CcoChunkData;
import com.openmason.engine.voxel.cco.data.CcoBlockStorage;
import com.openmason.engine.voxel.cco.data.palette.CcoPaletteSection;
import com.openmason.engine.voxel.cco.data.palette.CcoPalettedChunkStorage;
import com.openmason.engine.voxel.cco.data.palette.CcoSectionIndexing;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.lighting.BlockOpacity;
import com.stonebreak.world.lighting.WorldLightingContext;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Snapshot builder + kernel front-end for the native chunk mesher.
 *
 * One snapshot per mesh build: the chunk's blocks as a flat short[65536]
 * (index y*256 + z*16 + x == section*4096 + CCO cell index), the four
 * neighbor border planes + four diagonal corner columns for cross-chunk
 * culling/AO, and the 18x18 sky heightmap ring. Special (non-cube) cells are
 * collected during extraction so the Java pass only visits those instead of
 * re-walking all 65k cells.
 */
public final class CendaMesher {

    private static final int CS = WorldConfiguration.CHUNK_SIZE;
    private static final int WH = WorldConfiguration.WORLD_HEIGHT;
    private static final int CELLS = CS * CS * WH;

    /** Reusable per-thread quad output; grows on kernel overflow. */
    private static final ThreadLocal<float[]> QUAD_BUFFER =
        ThreadLocal.withInitial(() -> new float[9 * 16384]);
    private static final ThreadLocal<short[]> BLOCK_BUFFER =
        ThreadLocal.withInitial(() -> new short[CELLS]);

    private CendaMesher() {
    }

    public static boolean enabled() {
        return CendaKernels.isAvailable()
            && !"java".equalsIgnoreCase(System.getProperty("stonebreak.mesher.backend", "auto"));
    }

    /** Everything the kernel call needs, plus the special-cell worklist. */
    public static final class Snapshot {
        final short[] blocks;
        final short[] planeXn, planeXp, planeZn, planeZp;
        final short[] cornerNn, cornerPn, cornerNp, cornerPp;
        final short[] heights;
        final int[] specialCells;
        final int specialCount;
        final int maxY;

        Snapshot(short[] blocks, short[] planeXn, short[] planeXp, short[] planeZn, short[] planeZp,
                 short[] cornerNn, short[] cornerPn, short[] cornerNp, short[] cornerPp,
                 short[] heights, int[] specialCells, int specialCount, int maxY) {
            this.blocks = blocks;
            this.planeXn = planeXn;
            this.planeXp = planeXp;
            this.planeZn = planeZn;
            this.planeZp = planeZp;
            this.cornerNn = cornerNn;
            this.cornerPn = cornerPn;
            this.cornerNp = cornerNp;
            this.cornerPp = cornerPp;
            this.heights = heights;
            this.specialCells = specialCells;
            this.specialCount = specialCount;
            this.maxY = maxY;
        }

        public int specialCount() {
            return specialCount;
        }

        /** Packed flat index of the i-th special cell: y*256 + z*16 + x. */
        public int specialCell(int i) {
            return specialCells[i];
        }
    }

    /**
     * Builds the snapshot, or returns null when the chunk has no paletted
     * backing storage (callers then use the classic per-cell path).
     * The returned blocks/quads arrays are thread-local scratch — consume the
     * snapshot fully before the next build on the same thread.
     */
    public static Snapshot snapshot(CcoChunkData chunkData, World world,
                                    WorldLightingContext lightingContext,
                                    byte[] classTable, int maxY) {
        CcoBlockStorage storage = chunkData.backingStorage();
        if (!(storage instanceof CcoPalettedChunkStorage paletted)) {
            return null;
        }

        short[] blocks = BLOCK_BUFFER.get();
        for (int s = 0; s < paletted.getSectionCount(); s++) {
            paletted.getSection(s).writeBlockIdsInto(blocks, s * CS * CS * CcoSectionIndexing.SECTION_HEIGHT);
        }

        // Collect the cells the Java pass owns (water/cross/SBO/animated):
        // non-air ids whose class lacks the CUBE bit.
        int airId = BlockType.AIR.getId();
        int[] special = new int[256];
        int specialCount = 0;
        int scanTop = Math.min(maxY, WH - 1);
        for (int y = 0; y <= scanTop; y++) {
            int base = y * CS * CS;
            for (int i = 0; i < CS * CS; i++) {
                int id = blocks[base + i];
                if (id == airId) {
                    continue;
                }
                byte cls = id >= 0 && id < classTable.length ? classTable[id] : 0;
                if ((cls & CendaKernels.CLASS_CUBE) == 0) {
                    if (specialCount == special.length) {
                        special = java.util.Arrays.copyOf(special, special.length * 2);
                    }
                    special[specialCount++] = base + i;
                }
            }
        }

        int chunkX = chunkData.getChunkX();
        int chunkZ = chunkData.getChunkZ();

        short[] planeXn = borderPlaneX(world, chunkX - 1, chunkZ, CS - 1);
        short[] planeXp = borderPlaneX(world, chunkX + 1, chunkZ, 0);
        short[] planeZn = borderPlaneZ(world, chunkX, chunkZ - 1, CS - 1);
        short[] planeZp = borderPlaneZ(world, chunkX, chunkZ + 1, 0);
        short[] cornerNn = cornerColumn(world, chunkX - 1, chunkZ - 1, CS - 1, CS - 1);
        short[] cornerPn = cornerColumn(world, chunkX + 1, chunkZ - 1, 0, CS - 1);
        short[] cornerNp = cornerColumn(world, chunkX - 1, chunkZ + 1, CS - 1, 0);
        short[] cornerPp = cornerColumn(world, chunkX + 1, chunkZ + 1, 0, 0);

        short[] heights = new short[18 * 18];
        int baseX = chunkX * CS;
        int baseZ = chunkZ * CS;
        for (int lz = -1; lz <= CS; lz++) {
            for (int lx = -1; lx <= CS; lx++) {
                int h = lightingContext != null
                    ? lightingContext.getColumnHeight(baseX + lx, baseZ + lz)
                    : -1;
                heights[(lz + 1) * 18 + (lx + 1)] = (short) h;
            }
        }

        return new Snapshot(blocks, planeXn, planeXp, planeZn, planeZp,
            cornerNn, cornerPn, cornerNp, cornerPp,
            heights, special, specialCount, scanTop);
    }

    /**
     * Runs the kernel; returns the quad buffer via {@code holder[0]} and the
     * quad count, or -1 when the kernel declined (caller falls back to Java).
     */
    public static int mesh(Snapshot snap, boolean smooth, float[][] holder) {
        float[] out = QUAD_BUFFER.get();
        while (true) {
            int result = CendaKernels.meshChunk(snap.blocks, classTable(), BlockType.AIR.getId(),
                snap.planeXn, snap.planeXp, snap.planeZn, snap.planeZp,
                snap.cornerNn, snap.cornerPn, snap.cornerNp, snap.cornerPp,
                snap.heights, snap.maxY, smooth, out);
            if (result == Integer.MIN_VALUE) {
                return -1;
            }
            if (result >= 0) {
                holder[0] = out;
                return result;
            }
            out = new float[(-result) * 9];
            QUAD_BUFFER.set(out);
        }
    }

    // ─── Class table ──────────────────────────────────────────────────────

    private static volatile byte[] cachedClassTable;
    private static volatile java.util.function.Predicate<BlockType> cachedSboPredicate;

    /**
     * (Re)builds the per-block-id class table. Must be refreshed whenever the
     * SBO stamp emitter's block set changes (adapter calls this on wiring).
     */
    public static void rebuildClassTable(java.util.function.Predicate<BlockType> isSboStampBlock) {
        cachedSboPredicate = isSboStampBlock;
        int maxId = 0;
        for (BlockType type : BlockType.values()) {
            maxId = Math.max(maxId, type.getId());
        }
        byte[] table = new byte[maxId + 1];
        for (BlockType type : BlockType.values()) {
            table[type.getId()] = classify(type, isSboStampBlock);
        }
        cachedClassTable = table;
    }

    public static byte[] classTable() {
        byte[] table = cachedClassTable;
        if (table == null) {
            rebuildClassTable(cachedSboPredicate != null ? cachedSboPredicate : t -> false);
            table = cachedClassTable;
        }
        return table;
    }

    private static byte classify(BlockType type, java.util.function.Predicate<BlockType> isSboStampBlock) {
        byte cls = 0;
        if (type.isTransparent()) {
            cls |= CendaKernels.CLASS_TRANSPARENT;
        }
        if (BlockOpacity.isOpaque(type)) {
            cls |= CendaKernels.CLASS_OPAQUE_LIGHT;
        }
        boolean cube = type != BlockType.AIR
            && type != BlockType.WATER
            && !isCrossBlock(type)
            && !com.stonebreak.blocks.anim.AnimatedBlockRegistry.isAnimatedType(type)
            && !isSboStampBlock.test(type);
        if (cube) {
            cls |= CendaKernels.CLASS_CUBE;
        }
        return cls;
    }

    /** Mirrors MmsCcoAdapter.isCrossBlock — keep in lockstep. */
    static boolean isCrossBlock(BlockType type) {
        return type == BlockType.ROSE || type == BlockType.DANDELION || type == BlockType.WILDGRASS;
    }

    // ─── Neighbor extraction ──────────────────────────────────────────────

    private static CcoPalettedChunkStorage palettedStorage(World world, int cx, int cz) {
        if (world == null) {
            return null;
        }
        Chunk chunk = world.getChunkIfLoaded(cx, cz);
        if (chunk == null) {
            return null;
        }
        return chunk.getBlockStorageView() instanceof CcoPalettedChunkStorage p ? p : null;
    }

    /** Column plane at fixed local x of the neighbor chunk; dst[y*16 + z]. */
    private static short[] borderPlaneX(World world, int cx, int cz, int localX) {
        CcoPalettedChunkStorage storage = palettedStorage(world, cx, cz);
        if (storage == null) {
            return null;
        }
        short[] plane = new short[CS * WH];
        for (int s = 0; s < storage.getSectionCount(); s++) {
            CcoPaletteSection section = storage.getSection(s);
            int yBase = s * CcoSectionIndexing.SECTION_HEIGHT;
            if (section.isUniform()) {
                short id = (short) section.uniformBlock().getId();
                java.util.Arrays.fill(plane, yBase * CS, (yBase + CcoSectionIndexing.SECTION_HEIGHT) * CS, id);
            } else {
                for (int sy = 0; sy < CcoSectionIndexing.SECTION_HEIGHT; sy++) {
                    for (int z = 0; z < CS; z++) {
                        plane[(yBase + sy) * CS + z] = (short) section.get((sy * CS + z) * CS + localX).getId();
                    }
                }
            }
        }
        return plane;
    }

    /** Column plane at fixed local z of the neighbor chunk; dst[y*16 + x]. */
    private static short[] borderPlaneZ(World world, int cx, int cz, int localZ) {
        CcoPalettedChunkStorage storage = palettedStorage(world, cx, cz);
        if (storage == null) {
            return null;
        }
        short[] plane = new short[CS * WH];
        for (int s = 0; s < storage.getSectionCount(); s++) {
            CcoPaletteSection section = storage.getSection(s);
            int yBase = s * CcoSectionIndexing.SECTION_HEIGHT;
            if (section.isUniform()) {
                short id = (short) section.uniformBlock().getId();
                java.util.Arrays.fill(plane, yBase * CS, (yBase + CcoSectionIndexing.SECTION_HEIGHT) * CS, id);
            } else {
                for (int sy = 0; sy < CcoSectionIndexing.SECTION_HEIGHT; sy++) {
                    for (int x = 0; x < CS; x++) {
                        plane[(yBase + sy) * CS + x] = (short) section.get((sy * CS + localZ) * CS + x).getId();
                    }
                }
            }
        }
        return plane;
    }

    private static short[] cornerColumn(World world, int cx, int cz, int localX, int localZ) {
        CcoPalettedChunkStorage storage = palettedStorage(world, cx, cz);
        if (storage == null) {
            return null;
        }
        short[] column = new short[WH];
        for (int s = 0; s < storage.getSectionCount(); s++) {
            CcoPaletteSection section = storage.getSection(s);
            int yBase = s * CcoSectionIndexing.SECTION_HEIGHT;
            if (section.isUniform()) {
                short id = (short) section.uniformBlock().getId();
                java.util.Arrays.fill(column, yBase, yBase + CcoSectionIndexing.SECTION_HEIGHT, id);
            } else {
                for (int sy = 0; sy < CcoSectionIndexing.SECTION_HEIGHT; sy++) {
                    column[yBase + sy] = (short) section.get((sy * CS + localZ) * CS + localX).getId();
                }
            }
        }
        return column;
    }
}
