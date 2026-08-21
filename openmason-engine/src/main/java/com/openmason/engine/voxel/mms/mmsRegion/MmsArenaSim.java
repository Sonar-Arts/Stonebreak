package com.openmason.engine.voxel.mms.mmsRegion;

import com.openmason.engine.vram.VramArenaPolicy;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * A GL-free replica of {@link MmsChunkRegion}'s arena bookkeeping, for
 * planning and benchmarking: feed it the same sequence of mesh uploads and
 * frees a real region would see and it reports exactly the bytes the real
 * region would have reserved ({@link MmsChunkRegion#capacityBytes()}),
 * committed (sparse pages), used, and wasted — plus every growth, copy and
 * trim event along the way.
 *
 * <p>It lives in this package on purpose: it calls the SAME package-private
 * formulas the real region uses ({@link MmsChunkRegion#nextCapacity},
 * {@link MmsChunkRegion#sparseExtendTarget}, {@link MmsChunkRegion#trimTarget},
 * {@link MmsChunkRegion#virtualBytes}), so a change to the policy math moves
 * the plan and the simulation together. The GPU-cull metadata buffers are
 * NOT modelled (they scale with live mesh count, not bytes; see
 * {@link MmsChunkRegion#GPU_META_STRIDE}) — callers wanting that number add
 * {@code pow2ceil(max(64, liveMeshes)) * (48 + 20)} themselves.
 *
 * <p>Strides are parameters rather than constants so an experimental vertex
 * format (or an index-less region where {@code indexStride == 0}) can be
 * costed without touching the renderer.
 */
public final class MmsArenaSim {

    /** One notable arena event, in upload order. */
    public record Event(int uploadIndex, String arena, String kind,
                        long oldCapacityBytes, long newCapacityBytes, long bytesCopied) {
        @Override
        public String toString() {
            return String.format("#%d %s %s %d -> %d bytes%s", uploadIndex, arena, kind,
                oldCapacityBytes, newCapacityBytes,
                bytesCopied > 0 ? " (copied " + bytesCopied + ")" : "");
        }
    }

    /** A live mesh inside the simulated region. */
    public static final class Handle {
        final MmsArenaAllocator.Segment vertexSegment;
        final MmsArenaAllocator.Segment indexSegment; // null when index-less
        final int vertexCount;
        final int indexCount;

        Handle(MmsArenaAllocator.Segment vertexSegment, MmsArenaAllocator.Segment indexSegment,
               int vertexCount, int indexCount) {
            this.vertexSegment = vertexSegment;
            this.indexSegment = indexSegment;
            this.vertexCount = vertexCount;
            this.indexCount = indexCount;
        }
    }

    /** The simulated region's accounting at one instant. */
    public record Report(boolean sparse, int liveMeshes,
                         long vertexCapacityBytes, long indexCapacityBytes,
                         long vertexUsedBytes, long indexUsedBytes,
                         long trackedBytes, long committedBytes,
                         long vertexVirtualBytes, long indexVirtualBytes,
                         int growEvents, int trimEvents, long bytesCopied,
                         List<Event> events) {

        /** What the real region reports to {@code GpuMemoryTracker}. */
        public long reservedBytes() {
            return trackedBytes;
        }

        public long usedBytes() {
            return vertexUsedBytes + indexUsedBytes;
        }

        /** Reserved-but-unused bytes: the cost of growth reserve + fragmentation + page rounding. */
        public long slackBytes() {
            return trackedBytes - usedBytes();
        }

        public double utilization() {
            return trackedBytes == 0 ? 1.0 : (double) usedBytes() / trackedBytes;
        }
    }

    private final int vertexStride;
    private final int indexStride;
    private final boolean sparse;
    private final long pageSize;
    private final double growthFactor;
    private final double growthReserve;
    private final int alignElements;
    private final double trimFraction;
    private final int initialVertexCapacity;
    private final int initialIndexCapacity;

    private final MmsArenaAllocator vertexAlloc;
    private final MmsArenaAllocator indexAlloc; // null when index-less
    private final BitSet vertexPages;
    private final BitSet indexPages;
    private long vertexVirtualBytes;
    private long indexVirtualBytes;
    private long trackedBytes;

    private final List<Event> events = new ArrayList<>();
    private int liveMeshes;
    private int uploads;
    private int growEvents;
    private int trimEvents;
    private long bytesCopied;

    /**
     * Simulates a region under {@code policy} using the production strides
     * (40-byte vertices, u16 indices). Sparse mode follows the policy flag
     * with the common 64 KiB page size.
     */
    public MmsArenaSim(VramArenaPolicy policy) {
        this(policy, com.openmason.engine.voxel.mms.mmsCore.MmsVertexFormat.active().stride(),
            Short.BYTES, policy.sparseGrowth(), 65536);
    }

    /**
     * @param vertexStride bytes per vertex element
     * @param indexStride  bytes per index element, or 0 for an index-less region
     *                     (shared quad EBO) — the index arena then costs nothing
     * @param sparse       model ARB_sparse_buffer page-commitment growth
     * @param pageSize     sparse page size in bytes (ignored when !sparse)
     */
    public MmsArenaSim(VramArenaPolicy policy, int vertexStride, int indexStride,
                       boolean sparse, long pageSize) {
        if (vertexStride <= 0 || indexStride < 0) {
            throw new IllegalArgumentException("bad strides");
        }
        this.vertexStride = vertexStride;
        this.indexStride = indexStride;
        this.sparse = sparse;
        this.pageSize = sparse ? pageSize : 0;
        this.growthFactor = policy.growthFactor();
        this.growthReserve = policy.growthReserve();
        this.alignElements = policy.alignElements();
        this.trimFraction = policy.trimFraction();
        // Mirrors MmsChunkRegion(VramArenaPolicy): element counts floor at 1024.
        this.initialVertexCapacity = elementCount(policy.vertexInitialBytes(), vertexStride);
        this.initialIndexCapacity = indexStride == 0 ? 0
            : elementCount(policy.indexInitialBytes(), indexStride);
        this.vertexAlloc = new MmsArenaAllocator(initialVertexCapacity);
        this.indexAlloc = indexStride == 0 ? null : new MmsArenaAllocator(initialIndexCapacity);
        if (sparse) {
            this.vertexPages = new BitSet();
            this.indexPages = new BitSet();
            this.vertexVirtualBytes = MmsChunkRegion.virtualBytes(
                (long) initialVertexCapacity * vertexStride, pageSize);
            this.indexVirtualBytes = indexStride == 0 ? 0
                : MmsChunkRegion.virtualBytes((long) initialIndexCapacity * indexStride, pageSize);
            this.trackedBytes = 0;
        } else {
            this.vertexPages = null;
            this.indexPages = null;
            this.trackedBytes = (long) initialVertexCapacity * vertexStride
                + (long) initialIndexCapacity * indexStride;
        }
    }

    private static int elementCount(long bytes, int elementBytes) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1024, bytes / elementBytes));
    }

    public boolean isSparse() {
        return sparse;
    }

    public int vertexStride() {
        return vertexStride;
    }

    public int indexStride() {
        return indexStride;
    }

    /** Mirrors {@link MmsChunkRegion#upload}: allocate (growing as needed), commit pages. */
    public Handle upload(int vertexCount, int indexCount) {
        int uploadIndex = uploads++;
        MmsArenaAllocator.Segment vertexSeg = allocOrGrow(vertexAlloc, vertexCount, true, uploadIndex);
        MmsArenaAllocator.Segment indexSeg = null;
        if (indexAlloc != null) {
            try {
                indexSeg = allocOrGrow(indexAlloc, indexCount, false, uploadIndex);
            } catch (RuntimeException e) {
                vertexAlloc.free(vertexSeg);
                throw e;
            }
        }
        if (sparse) {
            ensureCommitted(true, (long) vertexSeg.offset() * vertexStride,
                (long) (vertexSeg.offset() + vertexSeg.length()) * vertexStride);
            if (indexSeg != null) {
                ensureCommitted(false, (long) indexSeg.offset() * indexStride,
                    (long) (indexSeg.offset() + indexSeg.length()) * indexStride);
            }
        }
        liveMeshes++;
        return new Handle(vertexSeg, indexSeg, vertexCount, indexCount);
    }

    /** Mirrors {@link MmsChunkRegion#free}. */
    public void free(Handle handle) {
        vertexAlloc.free(handle.vertexSegment);
        if (indexAlloc != null && handle.indexSegment != null) {
            indexAlloc.free(handle.indexSegment);
        }
        liveMeshes--;
    }

    /** Mirrors {@link MmsChunkRegion#maybeTrim} minus the GPU-cull buffer shrink. */
    public boolean maybeTrim() {
        boolean acted;
        if (sparse) {
            acted = sweepDecommit(true) | (indexAlloc != null && sweepDecommit(false));
        } else {
            acted = false;
            long vertexTarget = MmsChunkRegion.trimTarget(vertexAlloc.used(), vertexAlloc.capacity(),
                initialVertexCapacity, trimFraction, growthReserve, alignElements);
            if (vertexTarget > 0) {
                resizeArena(vertexAlloc, vertexTarget, true, uploads - 1, "trim");
                acted = true;
            } else if (indexAlloc != null) {
                long indexTarget = MmsChunkRegion.trimTarget(indexAlloc.used(), indexAlloc.capacity(),
                    initialIndexCapacity, trimFraction, growthReserve, alignElements);
                if (indexTarget > 0) {
                    resizeArena(indexAlloc, indexTarget, false, uploads - 1, "trim");
                    acted = true;
                }
            }
        }
        if (acted) {
            trimEvents++;
        }
        return acted;
    }

    /** Runs {@link #maybeTrim()} until it stops acting (a region gets one call per frame). */
    public int trimToRest() {
        int n = 0;
        while (maybeTrim()) {
            n++;
        }
        return n;
    }

    public Report report() {
        long committed = sparse
            ? ((long) vertexPages.cardinality() + (indexPages == null ? 0 : indexPages.cardinality()))
                * pageSize
            : trackedBytes;
        return new Report(sparse, liveMeshes,
            vertexAlloc.capacity() * vertexStride,
            indexAlloc == null ? 0 : indexAlloc.capacity() * indexStride,
            vertexAlloc.used() * vertexStride,
            indexAlloc == null ? 0 : indexAlloc.used() * indexStride,
            trackedBytes, committed, vertexVirtualBytes, indexVirtualBytes,
            growEvents, trimEvents, bytesCopied, List.copyOf(events));
    }

    // ─── Mirrors of the private region internals ────────────────────────

    private MmsArenaAllocator.Segment allocOrGrow(MmsArenaAllocator alloc, int length,
                                                  boolean vertex, int uploadIndex) {
        MmsArenaAllocator.Segment segment = alloc.alloc(length);
        if (segment != null) {
            return segment;
        }
        long grown = MmsChunkRegion.nextCapacity(alloc.used() + length, alloc.capacity(),
            growthFactor, growthReserve, alignElements);
        long elementBytes = vertex ? vertexStride : indexStride;
        if (sparse) {
            long target = MmsChunkRegion.sparseExtendTarget(grown, alloc.capacity(), length,
                growthReserve, alignElements);
            long virtualLimit = vertex ? vertexVirtualBytes : indexVirtualBytes;
            if (target * elementBytes <= virtualLimit) {
                long before = alloc.capacity() * elementBytes;
                alloc.extendTo(target);
                growEvents++;
                events.add(new Event(uploadIndex, vertex ? "vertex" : "index", "extend-sparse",
                    before, target * elementBytes, 0));
            } else {
                resizeArena(alloc, grown, vertex, uploadIndex, "grow-reserve-sparse");
            }
        } else {
            resizeArena(alloc, grown, vertex, uploadIndex, "grow-copy");
        }
        segment = alloc.alloc(length);
        if (segment == null) {
            resizeArena(alloc, MmsChunkRegion.nextCapacity(alloc.used() + length, alloc.capacity(),
                growthFactor, growthReserve, alignElements), vertex, uploadIndex, "grow-retry");
            segment = alloc.alloc(length);
        }
        if (segment == null) {
            throw new IllegalStateException("Simulated arena grow failed to fit " + length);
        }
        return segment;
    }

    private void ensureCommitted(boolean vertex, long fromByte, long toByte) {
        BitSet pages = vertex ? vertexPages : indexPages;
        int firstPage = (int) (fromByte / pageSize);
        int endPage = (int) ((toByte + pageSize - 1) / pageSize);
        int p = firstPage;
        while (p < endPage) {
            if (pages.get(p)) {
                p++;
                continue;
            }
            int runStart = p;
            while (p < endPage && !pages.get(p)) {
                p++;
            }
            long bytes = (long) (p - runStart) * pageSize;
            pages.set(runStart, p);
            trackedBytes += bytes;
        }
    }

    private boolean sweepDecommit(boolean vertex) {
        if (trimFraction <= 0) {
            return false;
        }
        BitSet pages = vertex ? vertexPages : indexPages;
        MmsArenaAllocator alloc = vertex ? vertexAlloc : indexAlloc;
        long elementBytes = vertex ? vertexStride : indexStride;
        long committed = (long) pages.cardinality() * pageSize;
        if (committed == 0) {
            return false;
        }
        long usedBytes = alloc.used() * elementBytes;
        if (usedBytes > (long) (committed * trimFraction)) {
            return false;
        }
        BitSet live = new BitSet();
        alloc.forEachLive((offset, length) -> {
            int from = (int) ((long) offset * elementBytes / pageSize);
            int to = (int) ((((long) offset + length) * elementBytes - 1) / pageSize);
            live.set(from, to + 1);
        });
        BitSet dead = (BitSet) pages.clone();
        dead.andNot(live);
        long deadBytes = (long) dead.cardinality() * pageSize;
        if (deadBytes == 0 || (usedBytes > 0 && deadBytes * 3 < committed)) {
            return false;
        }
        pages.andNot(dead);
        trackedBytes -= deadBytes;
        events.add(new Event(uploads - 1, vertex ? "vertex" : "index", "decommit",
            committed, committed - deadBytes, 0));
        return true;
    }

    private void resizeArena(MmsArenaAllocator alloc, long newCapacity, boolean vertex,
                             int uploadIndex, String kind) {
        long elementBytes = vertex ? vertexStride : indexStride;
        long newBytes = newCapacity * elementBytes;
        long oldBytes = sparse
            ? (long) (vertex ? vertexPages : indexPages).cardinality() * pageSize
            : alloc.capacity() * elementBytes;

        List<MmsArenaAllocator.Move> moves = alloc.compactTo(newCapacity);
        long copied = 0;
        for (MmsArenaAllocator.Move move : moves) {
            copied += (long) move.length() * elementBytes;
        }
        bytesCopied += copied;

        if (sparse) {
            BitSet pages = vertex ? vertexPages : indexPages;
            long newVirtual = MmsChunkRegion.alignUp(
                Math.max((vertex ? vertexVirtualBytes : indexVirtualBytes) * 2, newBytes * 2),
                pageSize);
            long commitBytes = MmsChunkRegion.alignUp(newBytes, pageSize);
            pages.clear();
            pages.set(0, (int) (commitBytes / pageSize));
            if (vertex) {
                vertexVirtualBytes = newVirtual;
            } else {
                indexVirtualBytes = newVirtual;
            }
            newBytes = commitBytes;
        }
        trackedBytes += newBytes - oldBytes;
        if (!"trim".equals(kind)) {
            growEvents++;
        }
        events.add(new Event(uploadIndex, vertex ? "vertex" : "index", kind, oldBytes, newBytes,
            copied));
    }
}
