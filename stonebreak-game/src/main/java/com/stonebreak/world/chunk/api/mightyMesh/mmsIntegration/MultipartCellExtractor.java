package com.stonebreak.world.chunk.api.mightyMesh.mmsIntegration;

import java.util.Arrays;

import com.openmason.engine.voxel.mms.mmsGeometry.MmsGreedyMesher;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.multipart.MultipartRegistry;
import com.stonebreak.blocks.multipart.MultipartShape;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Neighbour-driven shapes for cube blocks (rounded leaves) on the mesher's cube-face stream.
 *
 * <p>Such a block meshes as a cube by default, through the kernel, greedy merging and pulled
 * quads. {@link #extract} runs over the kernel-format records ({@code [x, y, z, face, id,
 * l0..l3]}, local coordinates) before merging. It resolves each such cell's neighbour combination
 * and, when that combination is not a plain cube ({@link SboCubeFaces#isCubeMask}), drops the
 * cell's records and hands the cell to {@link CellEmitter} under the combination's stamp key.
 * Every other record, including cells buried inside a canopy, stays in the stream.
 *
 * <p>A cell with every face culled has no records, so it is never visited: it is enclosed on all
 * six sides and keeps drawing nothing.
 *
 * <p>One instance per meshing thread; it reuses its scratch between chunks.
 */
final class MultipartCellExtractor {

    /** Block at local chunk coordinates (may step outside the chunk). */
    @FunctionalInterface
    interface Neighbours {
        BlockType at(int lx, int ly, int lz);
    }

    /** Draws one cell from the stamp registered under {@code key}. */
    @FunctionalInterface
    interface CellEmitter {
        void emit(BlockType type, int lx, int ly, int lz, String key);
    }

    private static final int STRIDE = MmsGreedyMesher.IN_STRIDE;
    private static final int CHUNK = WorldConfiguration.CHUNK_SIZE;
    private static final byte KEEP = 1;
    private static final byte EXTRACTED = 2;

    // Open-addressed table: packed cell index → KEEP / EXTRACTED.
    private int[] keys = new int[256];
    private byte[] verdicts = new byte[256];
    private int size;

    /**
     * Pulls the cells whose shape isn't a cube out of {@code quads} (compacted in place) and draws
     * them through {@code emitter}.
     *
     * @return the number of records left in the stream
     */
    int extract(float[] quads, int count, SboCubeFaces cubes, Neighbours neighbours, CellEmitter emitter) {
        Arrays.fill(keys, -1);
        size = 0;
        boolean anyExtracted = false;
        for (int q = 0; q < count; q++) {
            int base = q * STRIDE;
            BlockType type = BlockType.getById((int) quads[base + 4]);
            if (type == null || !cubes.hasCubeMasks(type)) {
                continue;
            }
            int lx = (int) quads[base];
            int ly = (int) quads[base + 1];
            int lz = (int) quads[base + 2];
            int key = cellKey(lx, ly, lz);
            if (verdict(key) != 0) {
                continue;
            }
            MultipartShape shape = MultipartRegistry.of(type);
            int mask = shape.evaluate(neighbours::at, lx, ly, lz);
            if (cubes.isCubeMask(type, mask)) {
                put(key, KEEP);
            } else {
                put(key, EXTRACTED);
                emitter.emit(type, lx, ly, lz, shape.key(mask));
                anyExtracted = true;
            }
        }
        return anyExtracted ? compact(quads, count) : count;
    }

    private static int cellKey(int lx, int ly, int lz) {
        return (ly * CHUNK + lz) * CHUNK + lx;
    }

    private static int slot(int key, int mask) {
        return (key * 0x9E3779B1) >>> 1 & mask;
    }

    private byte verdict(int key) {
        int mask = keys.length - 1;
        for (int h = slot(key, mask); keys[h] != -1; h = (h + 1) & mask) {
            if (keys[h] == key) {
                return verdicts[h];
            }
        }
        return 0;
    }

    private void put(int key, byte verdict) {
        if ((size + 1) * 2 > keys.length) {
            rehash(keys.length * 2);
        }
        int mask = keys.length - 1;
        int h = slot(key, mask);
        while (keys[h] != -1) {
            h = (h + 1) & mask;
        }
        keys[h] = key;
        verdicts[h] = verdict;
        size++;
    }

    private void rehash(int capacity) {
        int[] oldKeys = keys;
        byte[] oldVerdicts = verdicts;
        keys = new int[capacity];
        verdicts = new byte[capacity];
        Arrays.fill(keys, -1);
        int mask = capacity - 1;
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldKeys[i] == -1) {
                continue;
            }
            int h = slot(oldKeys[i], mask);
            while (keys[h] != -1) {
                h = (h + 1) & mask;
            }
            keys[h] = oldKeys[i];
            verdicts[h] = oldVerdicts[i];
        }
    }

    private int compact(float[] quads, int count) {
        int out = 0;
        for (int q = 0; q < count; q++) {
            int base = q * STRIDE;
            int key = cellKey((int) quads[base], (int) quads[base + 1], (int) quads[base + 2]);
            if (verdict(key) == EXTRACTED) {
                continue;
            }
            if (out != q) {
                System.arraycopy(quads, base, quads, out * STRIDE, STRIDE);
            }
            out++;
        }
        return out;
    }
}
