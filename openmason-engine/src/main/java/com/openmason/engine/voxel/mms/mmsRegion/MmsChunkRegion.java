package com.openmason.engine.voxel.mms.mmsRegion;

import com.openmason.engine.diagnostics.GpuMemoryTracker;
import com.openmason.engine.vram.VramArenaPolicy;
import com.openmason.engine.vram.VramPlans;
import com.openmason.engine.voxel.mms.mmsCore.MmsBufferLayout;
import com.openmason.engine.voxel.mms.mmsCore.MmsMeshData;
import com.openmason.engine.voxel.mms.mmsCore.MmsQuadCodec;
import com.openmason.engine.voxel.mms.mmsCore.MmsSharedQuadIndexBuffer;
import com.openmason.engine.voxel.mms.mmsCore.MmsVertexFormat;
import com.openmason.engine.voxel.mms.mmsCore.MmsRenderableHandle;
import com.openmason.engine.voxel.mms.mmsCore.MmsStagingRing;
import com.openmason.engine.voxel.mms.mmsCore.MmsUploadBufferPool;
import org.lwjgl.opengl.ARBSparseBuffer;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL44;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * One render region's shared GPU storage: a vertex arena (40-byte
 * {@link MmsBufferLayout} stride), a u16 index arena, and ONE VAO covering
 * both — so a whole region draws with one VAO bind and one
 * {@code glMultiDrawElementsBaseVertex} instead of a bind + draw per chunk.
 *
 * <p>Chunk meshes are sub-allocated via {@link MmsArenaAllocator}. Two
 * storage modes, chosen by the plan's {@code grow} directive:
 * <ul>
 *   <li><b>Copy</b> (default): when an arena runs out, a fresh larger GL
 *       buffer is created, live segments are packed into it with GPU-side
 *       {@code glCopyBufferSubData}, and the VAO is re-pointed. Segment
 *       offsets update in place, so handles stay valid across compaction.
 *   <li><b>Sparse</b> ({@code grow sparse}, needs ARB_sparse_buffer): the
 *       buffer reserves cheap virtual address space once and commits physical
 *       pages on demand under each uploaded mesh — growth is a capacity
 *       extension with <i>no copy, no spike, no VAO re-point, and offsets
 *       that never move</i>; the trim sweep decommits any page no live
 *       segment touches (copy-free, fragmentation-immune). Falls back to
 *       copy mode when the extension is missing or
 *       {@code -Dstonebreak.sparse=off} is set.
 * </ul>
 *
 * <p>All methods are GL-thread confined. Uploads and copies go through the
 * {@code GL_COPY_READ/WRITE_BUFFER} targets so the VAO's recorded element
 * binding and the global vertex-array state are never disturbed.
 */
public final class MmsChunkRegion {

    /** Region span: 8x8 chunk columns. */
    public static final int REGION_SHIFT = 3;
    public static final int REGION_SPAN = 1 << REGION_SHIFT;

    /** Bytes per mesh entry in the GPU-cull metadata SSBO (2×vec4 + uvec4, std430). */
    private static final int GPU_META_STRIDE = 48;
    /** Bytes per {@code DrawElementsIndirectCommand} (5 uints, tightly packed). */
    private static final int GPU_CMD_STRIDE = 20;

    private final double growthFactor;
    private final double growthReserve;
    private final int alignElements;
    private final double trimFraction;
    private final int initialVertexCapacity;
    private final int initialIndexCapacity;
    private final MmsArenaAllocator vertexAlloc;
    private final MmsArenaAllocator indexAlloc;

    // Sparse mode (null/0 when running the classic copy path).
    /** Packed vertex layout every mesh in this region must use (fixed at creation). */
    private final MmsVertexFormat format;
    private final int vertexStride;
    /** Vertex pulling: no index arena (shared EBO), quads read via a buffer texture. */
    private final boolean pulled;
    private int quadTextureId;
    /** World-space origin shared by every mesh in the region (local-position formats). */
    private float originX, originY, originZ;
    private boolean originSet;
    private int originBufferId;
    private final boolean sparse;
    private final long pageSize;
    private final BitSet vertexPages;
    private final BitSet indexPages;
    private long vertexVirtualBytes;
    private long indexVirtualBytes;

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
        this(VramPlans.arena(VramPlans.POOL_CHUNK_MESH));
    }

    /**
     * Creates a region sized and grown by a VRAM-plan arena policy (the CEARL
     * plan's pool arena, or the builtin defaults). Byte sizes convert to
     * element units here — the vertex arena divides by the 40-byte stride,
     * the index arena by 2.
     */
    public MmsChunkRegion(VramArenaPolicy policy) {
        this(MmsVertexFormat.active(), policy);
    }

    /** Creates a region holding meshes of an explicit format (stamp regions use {@code stampFormat()}). */
    public MmsChunkRegion(MmsVertexFormat format, VramArenaPolicy policy) {
        this(elementCount(policy.vertexInitialBytes(), format.stride()),
            format.pulled() ? 0 : elementCount(policy.indexInitialBytes(), Short.BYTES), policy, format);
    }

    /**
     * Creates a region with custom initial arena capacities (element units).
     * Callers whose regions hold many more meshes than the 8×8-column default
     * (e.g. 16×16-column LOD regions) start larger to avoid several
     * grow-and-compact cycles during the initial fill. Growth follows the
     * builtin default policy.
     */
    public MmsChunkRegion(int initialVertexCapacity, int initialIndexCapacity) {
        this(initialVertexCapacity, initialIndexCapacity, VramPlans.defaultArena(),
            MmsVertexFormat.active());
    }

    private MmsChunkRegion(int initialVertexCapacity, int initialIndexCapacity,
                           VramArenaPolicy policy, MmsVertexFormat format) {
        this.format = format;
        this.vertexStride = format.stride();
        this.pulled = format.pulled();
        if (pulled) {
            initialIndexCapacity = 0;
        }
        this.growthFactor = policy.growthFactor();
        this.growthReserve = policy.growthReserve();
        // Pulled arenas must stay quad-aligned (one quad = 4 "vertex" elements
        // = one RGBA32UI texel): never let a plan's align drop below 4.
        this.alignElements = pulled ? Math.max(4, policy.alignElements()) : policy.alignElements();
        this.trimFraction = policy.trimFraction();
        this.initialVertexCapacity = initialVertexCapacity;
        this.initialIndexCapacity = initialIndexCapacity;
        this.vertexAlloc = new MmsArenaAllocator(initialVertexCapacity);
        this.indexAlloc = pulled ? null : new MmsArenaAllocator(initialIndexCapacity);

        this.sparse = policy.sparseGrowth() && sparseSupported();
        if (sparse) {
            long queried = GL11.glGetInteger(ARBSparseBuffer.GL_SPARSE_BUFFER_PAGE_SIZE_ARB);
            this.pageSize = queried > 0 ? queried : 65536;
            this.vertexPages = new BitSet();
            this.indexPages = new BitSet();
            this.vertexVirtualBytes = virtualBytes(
                (long) initialVertexCapacity * vertexStride, pageSize);
            this.indexVirtualBytes = pulled ? 0 : virtualBytes(
                (long) initialIndexCapacity * Short.BYTES, pageSize);
            this.vertexBufferId = createSparseBuffer(vertexVirtualBytes);
            this.indexBufferId = pulled ? MmsSharedQuadIndexBuffer.id() : createSparseBuffer(indexVirtualBytes);
            // Physical pages commit on demand under the first uploads — an
            // empty sparse region costs (almost) nothing.
            trackedBytes = 0;
        } else {
            this.pageSize = 0;
            this.vertexPages = null;
            this.indexPages = null;
            this.vertexBufferId = GL15.glGenBuffers();
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, vertexBufferId);
            GL15.glBufferData(GL31.GL_COPY_WRITE_BUFFER,
                (long) initialVertexCapacity * vertexStride,
                GL15.GL_STATIC_DRAW);
            if (pulled) {
                this.indexBufferId = MmsSharedQuadIndexBuffer.id();
            } else {
                this.indexBufferId = GL15.glGenBuffers();
                GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, indexBufferId);
                GL15.glBufferData(GL31.GL_COPY_WRITE_BUFFER,
                    (long) initialIndexCapacity * Short.BYTES, GL15.GL_STATIC_DRAW);
            }
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
            trackedBytes = (long) initialVertexCapacity * vertexStride
                + (long) initialIndexCapacity * Short.BYTES;
        }

        if (pulled) {
            this.quadTextureId = GL11.glGenTextures();
            attachQuadTexture();
        }
        this.vaoId = GL30.glGenVertexArrays();
        rebuildVao();
        GpuMemoryTracker.getInstance().track(GpuMemoryTracker.Category.CHUNK_MESH, trackedBytes);
    }

    // ─── Sparse-mode support ──────────────────────────────────────────────

    private static Boolean sparseCapable;

    /** One-time capability + kill-switch check (only reached when a plan asks). */
    private static boolean sparseSupported() {
        if (sparseCapable == null) {
            boolean off = "off".equalsIgnoreCase(System.getProperty("stonebreak.sparse", "on"));
            boolean capable;
            try {
                GL.getCapabilities();
                capable = GL.getCapabilities().GL_ARB_sparse_buffer
                    && (GL.getCapabilities().OpenGL44
                        || GL.getCapabilities().GL_ARB_buffer_storage);
            } catch (IllegalStateException e) {
                return false; // No context on this thread — stay undecided.
            }
            sparseCapable = !off && capable;
            System.out.println("[MmsChunkRegion] Sparse arenas "
                + (sparseCapable ? "ENABLED (page commitment growth)"
                    : "unavailable (" + (off ? "-Dstonebreak.sparse=off"
                        : "no ARB_sparse_buffer") + ") — copy growth"));
        }
        return sparseCapable;
    }

    /** Virtual reservation for one arena: generous, page-aligned, still bounded. */
    static long virtualBytes(long initialBytes, long pageSize) {
        long virtual = Math.clamp(initialBytes * 32, 16L << 20, 128L << 20);
        virtual = Math.max(virtual, initialBytes * 2);
        return alignUp(virtual, pageSize);
    }

    static long alignUp(long value, long alignment) {
        return (value + alignment - 1) / alignment * alignment;
    }

    private int createSparseBuffer(long virtualCapacityBytes) {
        int id = GL15.glGenBuffers();
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, id);
        GL44.glBufferStorage(GL31.GL_COPY_WRITE_BUFFER, virtualCapacityBytes,
            GL44.GL_DYNAMIC_STORAGE_BIT | ARBSparseBuffer.GL_SPARSE_STORAGE_BIT_ARB);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
        return id;
    }

    /** Commits any uncommitted pages covering [fromByte, toByte) — batched runs. */
    private void ensureCommitted(boolean vertex, long fromByte, long toByte) {
        BitSet pages = vertex ? vertexPages : indexPages;
        int buffer = vertex ? vertexBufferId : indexBufferId;
        int firstPage = (int) (fromByte / pageSize);
        int endPage = (int) ((toByte + pageSize - 1) / pageSize);
        boolean bound = false;
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
            if (!bound) {
                GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, buffer);
                bound = true;
            }
            long bytes = (long) (p - runStart) * pageSize;
            ARBSparseBuffer.glBufferPageCommitmentARB(GL31.GL_COPY_WRITE_BUFFER,
                (long) runStart * pageSize, bytes, true);
            pages.set(runStart, p);
            trackedBytes += bytes;
            GpuMemoryTracker.getInstance().track(GpuMemoryTracker.Category.CHUNK_MESH, bytes);
        }
        if (bound) {
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
        }
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
        return upload(vertexBytes, indexBytes, vertexCount, indexCount,
            MmsVertexFormat.LEGACY40, 0f, 0f, 0f, minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** Uploads a packed mesh: its format must match the region's; all meshes share one origin. */
    public MmsRegionMeshHandle upload(MmsMeshData mesh, float minX, float minY, float minZ,
                                      float maxX, float maxY, float maxZ) {
        return upload(mesh.getPackedVertexData(), mesh.getPackedIndexData(),
            mesh.getVertexCount(), mesh.getIndexCount(), mesh.getFormat(),
            mesh.getOriginX(), mesh.getOriginY(), mesh.getOriginZ(),
            minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * @param meshFormat layout of {@code vertexBytes} — must equal this region's format
     * @param originX    world origin the mesh positions are relative to (local formats);
     *                   the first upload fixes the region origin, later ones must agree
     */
    public MmsRegionMeshHandle upload(byte[] vertexBytes, byte[] indexBytes,
                                      int vertexCount, int indexCount,
                                      MmsVertexFormat meshFormat,
                                      float originX, float originY, float originZ,
                                      float minX, float minY, float minZ,
                                      float maxX, float maxY, float maxZ) {
        ensureNotDeleted();
        if (meshFormat != format) {
            throw new IllegalArgumentException("Mesh format " + meshFormat
                + " cannot join a " + format + " region");
        }
        if (format.localPositions()) {
            if (!originSet) {
                this.originX = originX;
                this.originY = originY;
                this.originZ = originZ;
                this.originSet = true;
                this.originBufferId = format.createOriginBuffer(originX, originY, originZ);
                rebuildVao();
            } else if (originX != this.originX || originY != this.originY || originZ != this.originZ) {
                throw new IllegalArgumentException(String.format(
                    "Mesh origin (%.1f,%.1f,%.1f) differs from region origin (%.1f,%.1f,%.1f)",
                    originX, originY, originZ, this.originX, this.originY, this.originZ));
            }
        }
        MmsArenaAllocator.Segment vertexSeg = allocOrGrow(vertexAlloc, vertexCount, true);
        MmsArenaAllocator.Segment indexSeg = null;
        if (!pulled) {
            try {
                indexSeg = allocOrGrow(indexAlloc, indexCount, false);
            } catch (RuntimeException e) {
                // Don't strand the vertex segment if the index arena can't grow.
                vertexAlloc.free(vertexSeg);
                throw e;
            }
        }

        if (sparse) {
            // Physical pages materialize exactly where meshes land.
            ensureCommitted(true,
                (long) vertexSeg.offset() * vertexStride,
                (long) (vertexSeg.offset() + vertexSeg.length()) * vertexStride);
            if (indexSeg != null) {
                ensureCommitted(false,
                    (long) indexSeg.offset() * Short.BYTES,
                    (long) (indexSeg.offset() + indexSeg.length()) * Short.BYTES);
            }
        }
        uploadBytes(vertexBufferId, (long) vertexSeg.offset() * vertexStride,
            vertexBytes);
        if (indexSeg != null) {
            uploadBytes(indexBufferId, (long) indexSeg.offset() * Short.BYTES, indexBytes);
        }

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
        if (indexAlloc != null && handle.indexSegment != null) {
            indexAlloc.free(handle.indexSegment);
        }
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
        if (pulled) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + MmsQuadCodec.QUAD_TEXTURE_UNIT);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, quadTextureId);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
        }
    }

    /** (Re)points the quad buffer texture at the current vertex buffer (pulled regions). */
    private void attachQuadTexture() {
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, quadTextureId);
        GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32UI, vertexBufferId);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
    }

    /** The packed vertex layout this region's arenas hold. */
    public MmsVertexFormat format() {
        return format;
    }

    /** Bytes per vertex in this region's vertex arena. */
    public int vertexStride() {
        return vertexStride;
    }

    public float originX() {
        return originX;
    }

    public float originY() {
        return originY;
    }

    public float originZ() {
        return originZ;
    }

    /** True when this region grows by sparse page commitment rather than copy. */
    public boolean isSparse() {
        return sparse;
    }

    /** Sparse page size in bytes (0 in copy mode). */
    public long pageSize() {
        return pageSize;
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
                meta.putInt(h.getIndexCount())
                    .putInt(h.indexSegment == null ? 0 : h.indexSegment.offset())
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

    /** Sum of live meshes' index counts (6 per quad) — a page-independent size measure. */
    public long liveIndexCount() {
        long n = 0;
        for (MmsRegionMeshHandle h : liveHandles) {
            n += h.getIndexCount();
        }
        return n;
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
        long grown = nextCapacity(alloc.used() + length, alloc.capacity(),
            growthFactor, growthReserve, alignElements);
        long elementBytes = vertex ? vertexStride : Short.BYTES;
        if (sparse) {
            // Sparse growth: pure bookkeeping — capacity extends in place,
            // offsets never move, pages commit under the upload that follows.
            // CRITICAL: extension does NOT compact, so fragmented free spans
            // are useless to this request — the TAIL EXTENSION alone must fit
            // it, or a fragmented arena throws on large meshes even with
            // plenty of total free space (live bug, 2026-08-19). Virtual
            // capacity is free; over-extending costs zero physical bytes.
            long target = sparseExtendTarget(grown, alloc.capacity(), length,
                growthReserve, alignElements);
            long virtualLimit = vertex ? vertexVirtualBytes : indexVirtualBytes;
            if (target * elementBytes <= virtualLimit) {
                alloc.extendTo(target);
            } else {
                resizeArena(alloc, grown, vertex); // compacts + doubles the reservation
            }
        } else {
            resizeArena(alloc, grown, vertex);
        }
        segment = alloc.alloc(length);
        if (segment == null) {
            // Belt and braces: compaction always yields one contiguous free
            // span of at least the reserve over the need.
            resizeArena(alloc, nextCapacity(alloc.used() + length, alloc.capacity(),
                growthFactor, growthReserve, alignElements), vertex);
            segment = alloc.alloc(length);
        }
        if (segment == null) {
            throw new IllegalStateException("Region arena grow failed to fit " + length + " elements");
        }
        return segment;
    }

    /**
     * The sparse-mode grow target: at least the normal geometric growth, and
     * always enough that the request (plus reserve) fits in the contiguous
     * tail extension regardless of how fragmented the existing spans are.
     */
    static long sparseExtendTarget(long grown, long capacity, long length,
                                   double reserve, int align) {
        long tailFit = capacity + length + (long) (length * reserve);
        long mask = align - 1L;
        return Math.max(grown, (tailFit + mask) & ~mask);
    }

    /**
     * The grown capacity for an arena that needs {@code needed} elements:
     * whichever is larger of needed-plus-reserve and capacity-times-factor,
     * rounded up to the alignment. With the default policy (1.75 / 25% / 4)
     * this is bit-identical to the pre-CEARL formula
     * {@code max(needed + (needed >> 2), capacity * 7 / 4)} — pinned by
     * {@code MmsChunkRegionGrowthTest}.
     */
    static long nextCapacity(long needed, long capacity, double factor, double reserve,
                             int align) {
        long grown = Math.max(needed + (long) (needed * reserve), (long) (capacity * factor));
        long mask = align - 1L;
        return (grown + mask) & ~mask;
    }

    /** Converts a policy's byte size to whole elements (at least 1024). */
    private static int elementCount(long bytes, int elementBytes) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1024, bytes / elementBytes));
    }

    /**
     * Shrinks one underused arena back toward its live contents — the
     * counterpart to growth, driven by the plan's {@code trim} directive.
     * Without it arenas ratchet to their session high-water capacity forever
     * (cross one dense area and the memory never comes back). At most one
     * arena is resized per call so the GPU-side repack cost stays a
     * once-per-frame background expense; callers invoke it from their
     * per-frame prune pass. Returns true when a resize happened. GL thread.
     */
    public boolean maybeTrim() {
        if (deleted) {
            return false;
        }
        boolean acted;
        if (sparse) {
            // Copy-free: decommit every committed page no live segment
            // touches. Cheap enough to sweep both arenas each call.
            acted = sweepDecommit(true) | (!pulled && sweepDecommit(false));
        } else {
            acted = false;
            long vertexTarget = trimTarget(vertexAlloc.used(), vertexAlloc.capacity(),
                initialVertexCapacity, trimFraction, growthReserve, alignElements);
            if (vertexTarget > 0) {
                resizeArena(vertexAlloc, vertexTarget, true);
                acted = true;
            } else if (!pulled) {
                long indexTarget = trimTarget(indexAlloc.used(), indexAlloc.capacity(),
                    initialIndexCapacity, trimFraction, growthReserve, alignElements);
                if (indexTarget > 0) {
                    resizeArena(indexAlloc, indexTarget, false);
                    acted = true;
                }
            }
        }
        return shrinkGpuCullBuffers() || acted;
    }

    /**
     * Sparse trim: decommits committed pages that no live segment touches.
     * Gated like copy-trim (usage under {@code trimFraction} of committed,
     * recover at least a third — except a fully empty arena always drops to
     * zero physical). Fragmentation-immune: a page frees the moment nothing
     * lives on it, wherever it sits.
     */
    private boolean sweepDecommit(boolean vertex) {
        if (trimFraction <= 0) {
            return false;
        }
        BitSet pages = vertex ? vertexPages : indexPages;
        MmsArenaAllocator alloc = vertex ? vertexAlloc : indexAlloc;
        long elementBytes = vertex ? vertexStride : Short.BYTES;
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
        int buffer = vertex ? vertexBufferId : indexBufferId;
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, buffer);
        int p = dead.nextSetBit(0);
        while (p >= 0) {
            int runEnd = dead.nextClearBit(p);
            ARBSparseBuffer.glBufferPageCommitmentARB(GL31.GL_COPY_WRITE_BUFFER,
                (long) p * pageSize, (long) (runEnd - p) * pageSize, false);
            pages.clear(p, runEnd);
            p = dead.nextSetBit(runEnd);
        }
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
        trackedBytes -= deadBytes;
        GpuMemoryTracker.getInstance().untrack(GpuMemoryTracker.Category.CHUNK_MESH, deadBytes);
        return true;
    }

    /**
     * The GPU-cull metadata/indirect buffers grow pow2 and previously never
     * shrank. When the live set has fallen well below capacity (and trimming
     * is enabled at all), drop them — {@link #prepareGpuCull} lazily rebuilds
     * right-sized buffers on the next GPU-cull pass, so this is copy-free.
     */
    private boolean shrinkGpuCullBuffers() {
        if (trimFraction <= 0 || gpuBufferCapacity <= 64
                || gpuBufferCapacity < liveHandles.size() * 4) {
            return false;
        }
        long bytes = (long) gpuBufferCapacity * (GPU_META_STRIDE + GPU_CMD_STRIDE);
        GL15.glDeleteBuffers(gpuMetaBufferId);
        GL15.glDeleteBuffers(gpuIndirectBufferId);
        gpuMetaBufferId = 0;
        gpuIndirectBufferId = 0;
        gpuBufferCapacity = 0;
        gpuMetaDirty = true;
        trackedBytes -= bytes;
        GpuMemoryTracker.getInstance().untrack(GpuMemoryTracker.Category.CHUNK_MESH, bytes);
        return true;
    }

    /**
     * The trimmed capacity for an arena, or -1 when no trim should happen.
     * Trim fires only when live elements sit under {@code trimFraction} of
     * capacity AND the arena is above its initial size; the target keeps the
     * normal growth reserve over the live bytes and never drops below the
     * initial capacity. Hysteresis: a trim must return at least a third of
     * the arena, so trim and growth (factor ≥ 1.5) can never oscillate.
     */
    static long trimTarget(long used, long capacity, long initialCapacity,
                           double trimFraction, double reserve, int align) {
        if (trimFraction <= 0 || capacity <= initialCapacity) {
            return -1;
        }
        if (used > (long) (capacity * trimFraction)) {
            return -1;
        }
        long mask = align - 1L;
        long target = Math.max(initialCapacity,
            (used + (long) (used * reserve) + mask) & ~mask);
        if (target * 3L > capacity * 2L) {
            return -1;
        }
        return target;
    }

    /**
     * Resizes one arena (growth or trim): fresh GL buffer at the new capacity, GPU-side copies
     * packing every live segment to the tail, old buffer deleted, VAO
     * re-pointed at the new buffer.
     */
    private void resizeArena(MmsArenaAllocator alloc, long newCapacity, boolean vertex) {
        int elementBytes = vertex ? vertexStride : Short.BYTES;
        long newBytes = newCapacity * elementBytes;
        // Captured BEFORE compaction mutates the allocator's capacity.
        long oldBytes = sparse
            ? (long) (vertex ? vertexPages : indexPages).cardinality() * pageSize
            : alloc.capacity() * elementBytes;
        int oldBuffer = vertex ? vertexBufferId : indexBufferId;

        List<MmsArenaAllocator.Move> moves = alloc.compactTo(newCapacity);

        int newBuffer;
        if (sparse) {
            // Rare path: the virtual reservation itself ran out. Reserve a
            // bigger one, commit the packed range wholesale, copy across.
            BitSet pages = vertex ? vertexPages : indexPages;
            long newVirtual = alignUp(
                Math.max((vertex ? vertexVirtualBytes : indexVirtualBytes) * 2, newBytes * 2),
                pageSize);
            newBuffer = createSparseBuffer(newVirtual);
            long commitBytes = alignUp(newBytes, pageSize);
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, newBuffer);
            ARBSparseBuffer.glBufferPageCommitmentARB(GL31.GL_COPY_WRITE_BUFFER,
                0, commitBytes, true);
            pages.clear();
            pages.set(0, (int) (commitBytes / pageSize));
            if (vertex) {
                vertexVirtualBytes = newVirtual;
            } else {
                indexVirtualBytes = newVirtual;
            }
            newBytes = commitBytes;
        } else {
            newBuffer = GL15.glGenBuffers();
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, newBuffer);
            GL15.glBufferData(GL31.GL_COPY_WRITE_BUFFER, newBytes, GL15.GL_STATIC_DRAW);
        }
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
            if (pulled) {
                attachQuadTexture();
            }
        } else {
            indexBufferId = newBuffer;
        }
        rebuildVao();

        long delta = newBytes - oldBytes;
        // track() ignores non-positive deltas, so a trim must go through
        // untrack() or the accounting would silently ratchet upward.
        if (delta > 0) {
            GpuMemoryTracker.getInstance().track(GpuMemoryTracker.Category.CHUNK_MESH, delta);
        } else if (delta < 0) {
            GpuMemoryTracker.getInstance().untrack(GpuMemoryTracker.Category.CHUNK_MESH, -delta);
        }
        trackedBytes += delta;
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
        format.setupVertexAttributes();
        if (originBufferId != 0) {
            format.setupOriginAttribute(originBufferId);
        }
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
        if (originBufferId != 0) {
            GL15.glDeleteBuffers(originBufferId);
            originBufferId = 0;
        }
        GL15.glDeleteBuffers(vertexBufferId);
        if (!pulled) {
            GL15.glDeleteBuffers(indexBufferId); // pulled regions share the quad EBO
        }
        if (quadTextureId != 0) {
            GL11.glDeleteTextures(quadTextureId);
            quadTextureId = 0;
        }
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
