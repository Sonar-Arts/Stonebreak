package com.openmason.engine.voxel.mms.mmsRegion;

import com.openmason.engine.diagnostics.GpuMemoryTracker;
import com.openmason.engine.voxel.mms.mmsCore.MmsBufferLayout;
import com.openmason.engine.voxel.mms.mmsCore.MmsRenderableHandle;
import com.openmason.engine.voxel.mms.mmsCore.MmsStagingRing;
import com.openmason.engine.voxel.mms.mmsCore.MmsUploadBufferPool;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * One render region's shared GPU storage: a vertex arena (40-byte
 * {@link MmsBufferLayout} stride), a u16 index arena, and ONE VAO covering
 * both — so a whole region draws with one VAO bind and one
 * {@code glMultiDrawElementsBaseVertex} instead of a bind + draw per chunk.
 *
 * <p>Chunk meshes are sub-allocated via {@link MmsArenaAllocator}; when an
 * arena runs out, a fresh larger GL buffer is created, live segments are
 * packed into it with GPU-side {@code glCopyBufferSubData}, and the VAO is
 * re-pointed. Segment offsets update in place, so handles stay valid across
 * compaction (draw commands read {@code baseVertex}/offsets fresh each frame).
 *
 * <p>All methods are GL-thread confined. Uploads and copies go through the
 * {@code GL_COPY_READ/WRITE_BUFFER} targets so the VAO's recorded element
 * binding and the global vertex-array state are never disturbed.
 */
public final class MmsChunkRegion {

    /** Region span: 8x8 chunk columns. */
    public static final int REGION_SHIFT = 3;
    public static final int REGION_SPAN = 1 << REGION_SHIFT;

    /** Initial arena sizes; regions grow geometrically as chunks upload. */
    private static final int INITIAL_VERTEX_CAPACITY = 16 * 1024;      // vertices (~640 KB)
    private static final int INITIAL_INDEX_CAPACITY = 24 * 1024;       // u16 indices (~48 KB)

    /** Bytes per mesh entry in the GPU-cull metadata SSBO (2×vec4 + uvec4, std430). */
    private static final int GPU_META_STRIDE = 48;
    /** Bytes per {@code DrawElementsIndirectCommand} (5 uints, tightly packed). */
    private static final int GPU_CMD_STRIDE = 20;

    private final MmsArenaAllocator vertexAlloc;
    private final MmsArenaAllocator indexAlloc;
    private int vertexBufferId;
    private int indexBufferId;
    private int vaoId;
    private long trackedBytes;
    private boolean deleted;

    /** Live handles in metadata order (swap-remove; handle stores its index). */
    private final List<MmsRegionMeshHandle> liveHandles = new ArrayList<>();

    // GPU-driven culling state (lazily created; see MmsGpuCuller).
    private int gpuMetaBufferId;
    private int gpuIndirectBufferId;
    private int gpuBufferCapacity; // in meshes
    private boolean gpuMetaDirty = true;

    /** Per-draw-cycle member bucket, managed by the region renderer. */
    private final List<MmsRegionMeshHandle> cycleMembers = new ArrayList<>();
    private int cycleStamp = Integer.MIN_VALUE;

    public MmsChunkRegion() {
        this(INITIAL_VERTEX_CAPACITY, INITIAL_INDEX_CAPACITY);
    }

    /**
     * Creates a region with custom initial arena capacities (element units).
     * Callers whose regions hold many more meshes than the 8×8-column default
     * (e.g. 16×16-column LOD regions) start larger to avoid several
     * grow-and-compact cycles during the initial fill.
     */
    public MmsChunkRegion(int initialVertexCapacity, int initialIndexCapacity) {
        this.vertexAlloc = new MmsArenaAllocator(initialVertexCapacity);
        this.indexAlloc = new MmsArenaAllocator(initialIndexCapacity);

        this.vertexBufferId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, vertexBufferId);
        GL15.glBufferData(GL31.GL_COPY_WRITE_BUFFER,
            (long) initialVertexCapacity * MmsBufferLayout.VERTEX_STRIDE_BYTES, GL15.GL_STATIC_DRAW);

        this.indexBufferId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, indexBufferId);
        GL15.glBufferData(GL31.GL_COPY_WRITE_BUFFER,
            (long) initialIndexCapacity * Short.BYTES, GL15.GL_STATIC_DRAW);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);

        this.vaoId = GL30.glGenVertexArrays();
        rebuildVao();

        trackedBytes = (long) initialVertexCapacity * MmsBufferLayout.VERTEX_STRIDE_BYTES
            + (long) initialIndexCapacity * Short.BYTES;
        GpuMemoryTracker.getInstance().track(GpuMemoryTracker.Category.CHUNK_MESH, trackedBytes);
    }

    /**
     * Uploads one packed chunk mesh into this region's arenas. The six bound
     * parameters are the mesh's world-space AABB, recorded for the GPU-cull
     * metadata (the engine stays agnostic of chunk dimensions — the caller
     * supplies the box).
     *
     * @param vertexBytes interleaved {@link MmsBufferLayout} vertex bytes
     * @param indexBytes  u16 index bytes
     * @return a live handle whose segments the caller must eventually close
     */
    public MmsRegionMeshHandle upload(byte[] vertexBytes, byte[] indexBytes,
                                      int vertexCount, int indexCount,
                                      float minX, float minY, float minZ,
                                      float maxX, float maxY, float maxZ) {
        ensureNotDeleted();
        MmsArenaAllocator.Segment vertexSeg = allocOrGrow(vertexAlloc, vertexCount, true);
        MmsArenaAllocator.Segment indexSeg;
        try {
            indexSeg = allocOrGrow(indexAlloc, indexCount, false);
        } catch (RuntimeException e) {
            // Don't strand the vertex segment if the index arena can't grow.
            vertexAlloc.free(vertexSeg);
            throw e;
        }

        uploadBytes(vertexBufferId, (long) vertexSeg.offset() * MmsBufferLayout.VERTEX_STRIDE_BYTES,
            vertexBytes);
        uploadBytes(indexBufferId, (long) indexSeg.offset() * Short.BYTES, indexBytes);

        MmsRegionMeshHandle handle = new MmsRegionMeshHandle(this, vertexSeg, indexSeg, indexCount,
            minX, minY, minZ, maxX, maxY, maxZ);
        handle.liveIndex = liveHandles.size();
        liveHandles.add(handle);
        gpuMetaDirty = true;
        return handle;
    }

    /** Returns a handle's segments to the arenas (called via handle.close()). */
    void free(MmsRegionMeshHandle handle) {
        if (deleted) {
            return; // Region already torn down wholesale.
        }
        vertexAlloc.free(handle.vertexSegment);
        indexAlloc.free(handle.indexSegment);
        MmsRegionMeshHandle last = liveHandles.removeLast();
        if (last != handle) {
            liveHandles.set(handle.liveIndex, last);
            last.liveIndex = handle.liveIndex;
        }
        gpuMetaDirty = true;
    }

    /** True when no live handles remain — the owner may delete the region. */
    public boolean isEmpty() {
        return liveHandles.isEmpty();
    }

    /** True once {@link #delete()} has released the GL resources. */
    public boolean isDeleted() {
        return deleted;
    }

    /** Binds the region VAO (vertex attributes + element buffer). */
    public void bind() {
        ensureNotDeleted();
        GL30.glBindVertexArray(vaoId);
    }

    /** GPU bytes currently reserved by this region's buffers. */
    public long capacityBytes() {
        return trackedBytes;
    }

    // ─── Draw-cycle member bucket (used by the region renderer) ───────────

    /**
     * Returns this region's member bucket for the given draw cycle, clearing
     * it when the region is first touched in that cycle.
     */
    public List<MmsRegionMeshHandle> cycleMembers(int stamp) {
        if (cycleStamp != stamp) {
            cycleStamp = stamp;
            cycleMembers.clear();
        }
        return cycleMembers;
    }

    /** Whether this region was already touched in the given draw cycle. */
    public boolean touchedInCycle(int stamp) {
        return cycleStamp == stamp;
    }

    // ─── GPU-driven culling (used by MmsGpuCuller, GL 4.3+) ───────────────

    /**
     * Ensures the mesh-metadata SSBO and the indirect command buffer reflect
     * the current live set (rebuilt only when membership or arena offsets
     * changed since the last pass). Returns the number of live meshes — the
     * command count a subsequent indirect draw submits.
     */
    public int prepareGpuCull() {
        ensureNotDeleted();
        int count = liveHandles.size();
        if (count == 0) {
            return 0;
        }
        if (gpuMetaBufferId == 0) {
            gpuMetaBufferId = GL15.glGenBuffers();
            gpuIndirectBufferId = GL15.glGenBuffers();
        }
        if (count > gpuBufferCapacity) {
            int newCapacity = Math.max(64, Integer.highestOneBit(count - 1) << 1);
            long oldBytes = (long) gpuBufferCapacity * (GPU_META_STRIDE + GPU_CMD_STRIDE);
            long newBytes = (long) newCapacity * (GPU_META_STRIDE + GPU_CMD_STRIDE);
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, gpuMetaBufferId);
            GL15.glBufferData(GL31.GL_COPY_WRITE_BUFFER,
                (long) newCapacity * GPU_META_STRIDE, GL15.GL_DYNAMIC_DRAW);
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, gpuIndirectBufferId);
            GL15.glBufferData(GL31.GL_COPY_WRITE_BUFFER,
                (long) newCapacity * GPU_CMD_STRIDE, GL15.GL_DYNAMIC_COPY);
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
            gpuBufferCapacity = newCapacity;
            gpuMetaDirty = true;
            GpuMemoryTracker.getInstance()
                .track(GpuMemoryTracker.Category.CHUNK_MESH, newBytes - oldBytes);
            trackedBytes += newBytes - oldBytes;
        }
        if (gpuMetaDirty) {
            ByteBuffer meta = MmsUploadBufferPool.acquire(count * GPU_META_STRIDE);
            for (int i = 0; i < count; i++) {
                MmsRegionMeshHandle h = liveHandles.get(i);
                meta.putFloat(h.minX).putFloat(h.minY).putFloat(h.minZ).putFloat(0f);
                meta.putFloat(h.maxX).putFloat(h.maxY).putFloat(h.maxZ).putFloat(0f);
                meta.putInt(h.getIndexCount()).putInt(h.indexSegment.offset())
                    .putInt(h.baseVertex()).putInt(0);
            }
            meta.flip();
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, gpuMetaBufferId);
            GL15.glBufferSubData(GL31.GL_COPY_WRITE_BUFFER, 0, meta);
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
            gpuMetaDirty = false;
        }
        return count;
    }

    /** The metadata SSBO id (valid after {@link #prepareGpuCull}). */
    public int gpuMetaBuffer() {
        return gpuMetaBufferId;
    }

    /** The indirect command buffer id (valid after {@link #prepareGpuCull}). */
    public int gpuIndirectBuffer() {
        return gpuIndirectBufferId;
    }

    /** Command count currently laid out in the indirect buffer. */
    public int gpuCommandCount() {
        return liveHandles.size();
    }

    // ─── Internals ────────────────────────────────────────────────────────

    private MmsArenaAllocator.Segment allocOrGrow(MmsArenaAllocator alloc, int length,
                                                  boolean vertex) {
        MmsArenaAllocator.Segment segment = alloc.alloc(length);
        if (segment != null) {
            return segment;
        }
        long needed = alloc.used() + length;
        long grown = Math.max(needed + (needed >> 2), alloc.capacity() * 7L / 4L);
        grown = (grown + 3L) & ~3L; // 4-element alignment keeps quad math tidy
        growArena(alloc, grown, vertex);
        segment = alloc.alloc(length);
        if (segment == null) {
            throw new IllegalStateException("Region arena grow failed to fit " + length + " elements");
        }
        return segment;
    }

    /**
     * Grows one arena: fresh GL buffer at the new capacity, GPU-side copies
     * packing every live segment to the tail, old buffer deleted, VAO
     * re-pointed at the new buffer.
     */
    private void growArena(MmsArenaAllocator alloc, long newCapacity, boolean vertex) {
        int elementBytes = vertex ? MmsBufferLayout.VERTEX_STRIDE_BYTES : Short.BYTES;
        long oldBytes = alloc.capacity() * elementBytes;
        int oldBuffer = vertex ? vertexBufferId : indexBufferId;

        List<MmsArenaAllocator.Move> moves = alloc.compactTo(newCapacity);

        int newBuffer = GL15.glGenBuffers();
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, newBuffer);
        GL15.glBufferData(GL31.GL_COPY_WRITE_BUFFER, newCapacity * elementBytes, GL15.GL_STATIC_DRAW);
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, oldBuffer);
        for (MmsArenaAllocator.Move move : moves) {
            GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER,
                (long) move.from() * elementBytes,
                (long) move.to() * elementBytes,
                (long) move.length() * elementBytes);
        }
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
        GL15.glDeleteBuffers(oldBuffer);

        if (vertex) {
            vertexBufferId = newBuffer;
        } else {
            indexBufferId = newBuffer;
        }
        rebuildVao();

        long newBytes = newCapacity * elementBytes;
        GpuMemoryTracker.getInstance()
            .track(GpuMemoryTracker.Category.CHUNK_MESH, newBytes - oldBytes);
        trackedBytes += newBytes - oldBytes;
        // Compaction rewrote segment offsets — cull metadata must rebuild.
        gpuMetaDirty = true;
    }

    /**
     * (Re)records the VAO: vertex attributes over the vertex buffer and the
     * element binding. Element unbind must happen AFTER the VAO unbind or it
     * would be recorded into the VAO.
     */
    private void rebuildVao() {
        GL30.glBindVertexArray(vaoId);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vertexBufferId);
        MmsRenderableHandle.setupVertexAttributes();
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBufferId);
        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
    }

    private void uploadBytes(int bufferId, long offsetBytes, byte[] data) {
        // Preferred path: persistent-mapped staging ring (GL 4.4) — CPU writes
        // land directly in mapped memory and the GPU copies them in, with no
        // driver-side staging allocation per upload.
        MmsStagingRing ring = MmsStagingRing.sharedInstance();
        if (ring != null && ring.upload(bufferId, offsetBytes, data)) {
            return;
        }
        ByteBuffer staging = MmsUploadBufferPool.acquire(data.length);
        staging.put(data);
        staging.flip();
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, bufferId);
        GL15.glBufferSubData(GL31.GL_COPY_WRITE_BUFFER, offsetBytes, staging);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
    }

    private void ensureNotDeleted() {
        if (deleted) {
            throw new IllegalStateException("Region has been deleted");
        }
    }

    /** Deletes the region's GL resources. Outstanding handles become inert. */
    public void delete() {
        if (deleted) {
            return;
        }
        deleted = true;
        GL30.glDeleteVertexArrays(vaoId);
        GL15.glDeleteBuffers(vertexBufferId);
        GL15.glDeleteBuffers(indexBufferId);
        if (gpuMetaBufferId != 0) {
            GL15.glDeleteBuffers(gpuMetaBufferId);
            GL15.glDeleteBuffers(gpuIndirectBufferId);
            gpuMetaBufferId = 0;
            gpuIndirectBufferId = 0;
            gpuBufferCapacity = 0;
        }
        liveHandles.clear();
        GpuMemoryTracker.getInstance()
            .untrack(GpuMemoryTracker.Category.CHUNK_MESH, trackedBytes);
        trackedBytes = 0;
    }
}
