package com.openmason.engine.voxel.mms.mmsRegion;

/**
 * One chunk mesh living inside a region's arenas: a vertex segment plus a
 * u16 index segment. Where a legacy {@code MmsRenderableHandle} owns a whole
 * VAO/VBO/EBO triple, this is just two ranges of the region's shared buffers —
 * drawing happens through the region's multidraw batch, never per handle.
 *
 * <p>{@link #close()} returns the segments to the region (GL-thread only, via
 * the mesh pipeline's deferred cleanup queue). Idempotent.
 */
public final class MmsRegionMeshHandle implements AutoCloseable {

    private final MmsChunkRegion region;
    final MmsArenaAllocator.Segment vertexSegment;
    final MmsArenaAllocator.Segment indexSegment;
    private final int indexCount;
    private boolean closed;

    MmsRegionMeshHandle(MmsChunkRegion region,
                        MmsArenaAllocator.Segment vertexSegment,
                        MmsArenaAllocator.Segment indexSegment,
                        int indexCount) {
        this.region = region;
        this.vertexSegment = vertexSegment;
        this.indexSegment = indexSegment;
        this.indexCount = indexCount;
    }

    public MmsChunkRegion region() {
        return region;
    }

    public int getIndexCount() {
        return indexCount;
    }

    /** The mesh's first vertex in the region vertex buffer (for baseVertex draws). */
    public int baseVertex() {
        return vertexSegment.offset();
    }

    /** Byte offset of the mesh's indices in the region index buffer. */
    public long indexOffsetBytes() {
        return (long) indexSegment.offset() * Short.BYTES;
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        region.free(this);
    }
}
