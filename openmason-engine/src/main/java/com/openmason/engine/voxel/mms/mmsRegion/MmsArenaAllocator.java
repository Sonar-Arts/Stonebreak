package com.openmason.engine.voxel.mms.mmsRegion;

import java.util.ArrayList;
import java.util.List;

/**
 * Free-list sub-allocator for a region GPU buffer — pure bookkeeping, no GL.
 *
 * <p>The buffer is modeled as a doubly-linked chain of {@link Segment}s
 * covering [0, capacity) in element units (the caller decides what one
 * element is — a 40-byte vertex, a 2-byte index). Allocation is best-fit
 * with split; free coalesces with both neighbours. When an allocation
 * cannot fit, the caller grows the buffer via {@link #compactTo}, which
 * produces the GPU copy commands that pack every live segment to the tail
 * of the new capacity and rewrites segment offsets in place — outstanding
 * {@link Segment} references stay valid, only their offsets move.
 *
 * <p>Not thread-safe: the owning region confines it to the GL thread.
 */
public final class MmsArenaAllocator {

    /** One contiguous range of the arena. Offset/length are in elements. */
    public static final class Segment {
        private int offset;
        private int length;
        private boolean free;
        private Segment prev;
        private Segment next;

        private Segment(int offset, int length, boolean free) {
            this.offset = offset;
            this.length = length;
            this.free = free;
        }

        public int offset() {
            return offset;
        }

        public int length() {
            return length;
        }

        boolean isFree() {
            return free;
        }
    }

    /** A pending GPU copy produced by compaction (element units). */
    public record Move(int from, int to, int length) {}

    private Segment head;
    private long capacity;
    private long used;

    public MmsArenaAllocator(int initialCapacity) {
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = initialCapacity;
        this.head = new Segment(0, initialCapacity, true);
    }

    public long capacity() {
        return capacity;
    }

    public long used() {
        return used;
    }

    public boolean isEmpty() {
        return used == 0;
    }

    /**
     * Allocates {@code length} elements. Returns null when no free span is
     * large enough (caller should {@link #compactTo} a larger capacity and
     * retry).
     */
    public Segment alloc(int length) {
        if (length <= 0) {
            throw new IllegalArgumentException("Allocation length must be positive: " + length);
        }
        Segment best = findFree(length);
        if (best == null) {
            return null;
        }
        Segment result;
        if (best.length == length) {
            best.free = false;
            result = best;
        } else {
            // Carve the allocation off the END of the free span, like Sodium —
            // the remaining free space stays a single node at the same offset.
            Segment carved = new Segment(best.offset + best.length - length, length, false);
            carved.next = best.next;
            carved.prev = best;
            if (carved.next != null) {
                carved.next.prev = carved;
            }
            best.length -= length;
            best.next = carved;
            result = carved;
        }
        used += length;
        return result;
    }

    /** Frees a segment and coalesces it with free neighbours. */
    public void free(Segment segment) {
        if (segment.free) {
            throw new IllegalStateException("Segment already freed");
        }
        segment.free = true;
        used -= segment.length;

        Segment next = segment.next;
        if (next != null && next.free) {
            mergeInto(segment, next);
        }
        Segment prev = segment.prev;
        if (prev != null && prev.free) {
            mergeInto(prev, segment);
        }
    }

    /**
     * Repacks every live segment contiguously at the TAIL of a new capacity,
     * leaving one free span at the head, and returns the GPU copies the
     * caller must perform ({@code from} offsets refer to the OLD layout,
     * {@code to} offsets to the new one). Live segments' offsets are updated
     * in place; adjacent already-packed segments are merged into one Move.
     *
     * @param newCapacity must be &gt;= {@link #used()}
     */
    public List<Move> compactTo(long newCapacity) {
        if (newCapacity <= 0 || newCapacity < used) {
            throw new IllegalArgumentException(
                "New capacity " + newCapacity + " invalid (used " + used + ")");
        }
        if (newCapacity > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Arena capacity limit exceeded: " + newCapacity);
        }

        // Collect live segments in offset order (the chain is offset-ordered).
        List<Segment> live = new ArrayList<>();
        for (Segment s = head; s != null; s = s.next) {
            if (!s.free) {
                live.add(s);
            }
        }

        List<Move> moves = new ArrayList<>();
        long tail = newCapacity - used;
        int writeOffset = (int) tail;
        int runFrom = -1;
        int runTo = -1;
        int runLength = 0;
        for (Segment s : live) {
            int from = s.offset;
            if (runLength > 0 && from == runFrom + runLength && writeOffset == runTo + runLength) {
                runLength += s.length;
            } else {
                if (runLength > 0 && (runFrom != runTo)) {
                    moves.add(new Move(runFrom, runTo, runLength));
                }
                if (runLength > 0 && runFrom == runTo) {
                    // No-op run (already in place) — drop it.
                }
                runFrom = from;
                runTo = writeOffset;
                runLength = s.length;
            }
            s.offset = writeOffset;
            writeOffset += s.length;
        }
        if (runLength > 0 && runFrom != runTo) {
            moves.add(new Move(runFrom, runTo, runLength));
        }

        // Rebuild the chain: one free head + the packed live segments.
        this.capacity = newCapacity;
        Segment freeHead = new Segment(0, (int) tail, true);
        this.head = freeHead;
        Segment prev = freeHead;
        if (tail == 0 && !live.isEmpty()) {
            // Zero free space: chain starts at the first live segment.
            this.head = live.getFirst();
            live.getFirst().prev = null;
            prev = live.getFirst();
            for (int i = 1; i < live.size(); i++) {
                Segment s = live.get(i);
                prev.next = s;
                s.prev = prev;
                prev = s;
            }
            prev.next = null;
            return moves;
        }
        for (Segment s : live) {
            prev.next = s;
            s.prev = prev;
            prev = s;
        }
        prev.next = null;
        freeHead.prev = null;
        return moves;
    }

    private Segment findFree(int length) {
        Segment best = null;
        for (Segment s = head; s != null; s = s.next) {
            if (!s.free) {
                continue;
            }
            if (s.length == length) {
                return s;
            }
            if (s.length > length && (best == null || s.length < best.length)) {
                best = s;
            }
        }
        return best;
    }

    /** Merges {@code b} (must directly follow {@code a}) into {@code a}. */
    private void mergeInto(Segment a, Segment b) {
        a.length += b.length;
        a.next = b.next;
        if (a.next != null) {
            a.next.prev = a;
        }
    }

    /** Verification helper for tests: walks the chain checking invariants. */
    void checkInvariants() {
        int cursor = 0;
        long liveTotal = 0;
        Segment prev = null;
        for (Segment s = head; s != null; s = s.next) {
            if (s.offset != cursor) {
                throw new AssertionError("Gap/overlap at offset " + s.offset + " (expected " + cursor + ")");
            }
            if (s.prev != prev) {
                throw new AssertionError("Broken prev link at offset " + s.offset);
            }
            if (prev != null && prev.free && s.free) {
                throw new AssertionError("Uncoalesced free neighbours at offset " + s.offset);
            }
            if (!s.free) {
                liveTotal += s.length;
            }
            cursor += s.length;
            prev = s;
        }
        if (cursor != capacity) {
            throw new AssertionError("Chain covers " + cursor + " of capacity " + capacity);
        }
        if (liveTotal != used) {
            throw new AssertionError("Used tracking " + used + " != live total " + liveTotal);
        }
    }
}
