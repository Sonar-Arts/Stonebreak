package com.openmason.engine.voxel.mms.mmsRegion;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL32;

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
    /** World-space bounding box, supplied at upload (GPU-cull metadata). */
    final float minX, minY, minZ, maxX, maxY, maxZ;
    /** Position in the region's live-handle list (swap-remove bookkeeping). */
    int liveIndex;
    private boolean closed;

    MmsRegionMeshHandle(MmsChunkRegion region,
                        MmsArenaAllocator.Segment vertexSegment,
                        MmsArenaAllocator.Segment indexSegment,
                        int indexCount,
                        float minX, float minY, float minZ,
                        float maxX, float maxY, float maxZ) {
        this.region = region;
        this.vertexSegment = vertexSegment;
        this.indexSegment = indexSegment;
        this.indexCount = indexCount;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
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

    /**
     * Draws just this mesh: binds the region VAO and issues one
     * {@code glDrawElementsBaseVertex}. Meant for the rare cases where a
     * region-resident mesh needs individual state (e.g. a per-node fade
     * uniform) — batched paths should go through the region's multidraw.
     * The caller unbinds the VAO after its loop.
     */
    public void render() {
        region.bind();
        GL32.glDrawElementsBaseVertex(GL11.GL_TRIANGLES, indexCount, GL11.GL_UNSIGNED_SHORT,
            indexOffsetBytes(), baseVertex());
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
