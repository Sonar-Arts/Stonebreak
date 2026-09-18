package com.openmason.engine.rendering.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A contiguous element-buffer range with one material. Offsets and counts are
 * in indices, not bytes; vertices, UVs and triangle order remain untouched.
 */
public record IndexedDrawBatch(int materialId, int indexStart, int indexCount) {

    public IndexedDrawBatch {
        if (indexStart < 0 || indexCount <= 0 || (long) indexStart + indexCount > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid index range: " + indexStart + " + " + indexCount);
        }
    }

    public long byteOffset() {
        return (long) indexStart * Integer.BYTES;
    }

    /**
     * Coalesces consecutive submissions only when both the material and the
     * index range are contiguous. Use a separate builder per transform/draw
     * state. Never sorts, fills gaps, or merges overlapping ranges: this keeps
     * authored draw order (including coplanar and transparent faces) intact.
     * Build once when uploading a mesh, then reuse the immutable result.
     */
    public static final class Builder {
        private final List<IndexedDrawBatch> batches = new ArrayList<>();
        private int materialId;
        private int indexStart;
        private int indexCount;

        public void add(int material, int start, int count) {
            if (start < 0 || count < 0 || (long) start + count > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Invalid index range: " + start + " + " + count);
            }
            if (count == 0) return;
            if (indexCount != 0 && material == materialId && (long) indexStart + indexCount == start) {
                indexCount += count;
            } else {
                flush();
                materialId = material;
                indexStart = start;
                indexCount = count;
            }
        }

        public List<IndexedDrawBatch> build() {
            flush();
            return List.copyOf(batches);
        }

        private void flush() {
            if (indexCount == 0) return;
            batches.add(new IndexedDrawBatch(materialId, indexStart, indexCount));
            indexCount = 0;
        }
    }
}
