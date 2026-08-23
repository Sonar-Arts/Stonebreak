package com.openmason.engine.voxel.mms.mmsCore;

import com.openmason.engine.diagnostics.GpuMemoryTracker;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mighty Mesh System - GPU resource handle with automatic lifecycle management.
 *
 * Represents a mesh that has been uploaded to GPU memory (VAO, VBO, EBO).
 * Implements RAII pattern for automatic resource cleanup.
 *
 * Design Philosophy:
 * - RAII: Resource Acquisition Is Initialization
 * - Thread-safe: Atomic state management
 * - Fail-safe: Prevents double-free and use-after-free
 * - KISS: Simple, focused API
 *
 * Usage Example:
 * <pre>{@code
 * try (MmsRenderableHandle handle = MmsRenderableHandle.upload(meshData)) {
 *     handle.render();
 * } // Automatically cleaned up
 * }</pre>
 *
 * @since MMS 1.0
 */
public final class MmsRenderableHandle implements AutoCloseable {

    // OpenGL resource IDs
    private final int vaoId;
    private final int vboId;
    private final int eboId;
    private final int indexCount;
    /** GL index element type: GL_UNSIGNED_SHORT for packed u16 meshes, else GL_UNSIGNED_INT. */
    private final int indexType;
    private final int bytesPerIndex;

    // Buffer sizes (for pooling)
    private final int vboSizeBytes;
    private final int eboSizeBytes;

    // State management
    private final AtomicBoolean disposed = new AtomicBoolean(false);
    private final boolean useBufferPool;
    /** Per-mesh origin buffer for local-position formats (0 when unused). */
    private int originBufferId;
    /** Vertex pulling: quad buffer texture over the VBO; EBO is the shared quad pattern. */
    private int quadTextureId;
    private boolean pulled;

    // Statistics
    private final long uploadTimestamp;
    private final long memoryUsageBytes;

    /**
     * Creates a new renderable handle (package-private, use factory methods).
     *
     * @param vaoId Vertex Array Object ID
     * @param vboId Vertex Buffer Object ID
     * @param eboId Element Buffer Object ID
     * @param indexCount Number of indices to render
     * @param vboSizeBytes VBO size in bytes
     * @param eboSizeBytes EBO size in bytes
     * @param memoryUsageBytes Estimated GPU memory usage
     * @param useBufferPool Whether to return buffers to pool on close
     */
    private MmsRenderableHandle(int vaoId, int vboId, int eboId, int indexCount, int indexType,
                                int vboSizeBytes, int eboSizeBytes, long memoryUsageBytes,
                                boolean useBufferPool) {
        this.vaoId = vaoId;
        this.vboId = vboId;
        this.eboId = eboId;
        this.indexCount = indexCount;
        this.indexType = indexType;
        this.bytesPerIndex = indexType == GL15.GL_UNSIGNED_SHORT ? Short.BYTES : Integer.BYTES;
        this.vboSizeBytes = vboSizeBytes;
        this.eboSizeBytes = eboSizeBytes;
        this.memoryUsageBytes = memoryUsageBytes;
        this.useBufferPool = useBufferPool;
        this.uploadTimestamp = System.currentTimeMillis();
    }

    /**
     * Uploads mesh data to GPU and creates a renderable handle.
     * Uses buffer pooling by default.
     * MUST be called from the OpenGL thread.
     *
     * @param meshData Mesh data to upload
     * @return Renderable handle managing GPU resources
     * @throws IllegalArgumentException if meshData is null or empty
     * @throws IllegalStateException if not called from OpenGL thread
     */
    public static MmsRenderableHandle upload(MmsMeshData meshData) {
        return upload(meshData, true);
    }

    /**
     * Uploads mesh data to GPU and creates a renderable handle.
     * MUST be called from the OpenGL thread.
     *
     * @param meshData Mesh data to upload
     * @param useBufferPool Whether to use buffer pooling
     * @return Renderable handle managing GPU resources
     * @throws IllegalArgumentException if meshData is null or empty
     * @throws IllegalStateException if not called from OpenGL thread
     */
    public static MmsRenderableHandle upload(MmsMeshData meshData, boolean useBufferPool) {
        if (meshData == null) {
            throw new IllegalArgumentException("Mesh data cannot be null");
        }

        if (meshData.isEmpty()) {
            throw new IllegalArgumentException("Cannot upload empty mesh data");
        }

        // Prepare interleaved data. Packed meshes (the chunk path) already
        // hold the exact GPU byte layout — staging is a bulk copy; SoA meshes
        // interleave here as before.
        // SoA meshes pack through the active format first (self-contained origin),
        // so one upload path serves every layout.
        if (!meshData.isPacked()) {
            meshData = meshData.toPacked();
        }
        boolean packed = true;
        MmsVertexFormat format = meshData.getFormat();
        ByteBuffer interleavedData = MmsUploadBufferPool.acquire(meshData.getPackedVertexData().length);
        interleavedData.put(meshData.getPackedVertexData());
        interleavedData.flip();
        boolean pulled = format.pulled();
        int indexType = packed && meshData.hasShortIndices()
            ? GL15.GL_UNSIGNED_SHORT : GL15.GL_UNSIGNED_INT;
        int vboSizeBytes = interleavedData.remaining();
        // Pulled meshes draw through the shared quad EBO — no per-mesh index bytes.
        int eboSizeBytes = pulled ? 0 : meshData.getIndexCount()
            * (indexType == GL15.GL_UNSIGNED_SHORT ? Short.BYTES : Integer.BYTES);

        // Acquire buffers from pool or allocate new
        MmsBufferPool pool = useBufferPool ? MmsBufferPool.getInstance() : null;
        int vaoId, vboId, eboId;

        if (pool != null) {
            vaoId = pool.acquireVAO();
            vboId = pool.acquireVBO(vboSizeBytes);
            eboId = pulled ? MmsSharedQuadIndexBuffer.id() : pool.acquireEBO(eboSizeBytes);
        } else {
            vaoId = GL30.glGenVertexArrays();
            vboId = GL15.glGenBuffers();
            eboId = pulled ? MmsSharedQuadIndexBuffer.id() : GL15.glGenBuffers();
        }

        // Bind VAO and setup buffers
        GL30.glBindVertexArray(vaoId);

        // Upload VBO data (glBufferData consumes the staging buffer
        // synchronously, so it is free for the EBO staging below)
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboId);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, interleavedData, GL15.GL_STATIC_DRAW);

        // Setup vertex attributes (+ the per-mesh origin for local-position formats)
        format.setupVertexAttributes();
        int originBuffer = 0;
        if (format.localPositions()) {
            originBuffer = format.createOriginBuffer(
                meshData.getOriginX(), meshData.getOriginY(), meshData.getOriginZ());
            format.setupOriginAttribute(originBuffer);
        }

        // Upload EBO data
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, eboId);
        if (!pulled) {
            ByteBuffer indexData = MmsUploadBufferPool.acquire(meshData.getPackedIndexData().length);
            indexData.put(meshData.getPackedIndexData());
            indexData.flip();
            GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indexData, GL15.GL_STATIC_DRAW);
        }
        int quadTexture = 0;
        if (pulled) {
            quadTexture = GL11.glGenTextures();
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, quadTexture);
            GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32UI, vboId);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
        }

        // Unbind (good practice)
        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);

        // Calculate GPU memory usage
        long memoryUsage = vboSizeBytes + eboSizeBytes;

        // Charge the active VRAM. If useBufferPool=true, BUFFER_POOL_IDLE has
        // already been decremented inside the pool's acquire path; we now
        // attribute these bytes to the active CHUNK_MESH bucket.
        GpuMemoryTracker.getInstance()
            .track(GpuMemoryTracker.Category.CHUNK_MESH, memoryUsage);

        MmsRenderableHandle handle = new MmsRenderableHandle(
            vaoId, vboId, eboId, meshData.getIndexCount(), indexType,
            vboSizeBytes, eboSizeBytes, memoryUsage, useBufferPool
        );
        handle.originBufferId = originBuffer;
        handle.quadTextureId = quadTexture;
        handle.pulled = pulled;
        return handle;
    }

    /**
     * Records the active {@link MmsVertexFormat}'s attribute pointers on the
     * bound VAO. Public because {@code MmsChunkRegion.rebuildVao} shares it.
     */
    public static void setupVertexAttributes() {
        MmsVertexFormat.active().setupVertexAttributes();
    }

    public void render() {
        ensureNotDisposed();

        bind();
        GL15.glDrawElements(GL15.GL_TRIANGLES, indexCount, indexType, 0);
        GL30.glBindVertexArray(0);
    }

    /**
     * Renders a sub-range of the mesh indices.
     * VAO must be bound first via {@link #bind()}.
     *
     * @param indexOffset offset into the index buffer, in indices
     * @param count       number of indices to draw
     */
    public void renderRange(int indexOffset, int count) {
        GL15.glDrawElements(GL15.GL_TRIANGLES, count, indexType, (long) indexOffset * bytesPerIndex);
    }

    /**
     * Binds the VAO for rendering without drawing.
     * Useful for custom rendering operations.
     *
     * @throws IllegalStateException if handle has been disposed
     */
    public void bind() {
        ensureNotDisposed();
        GL30.glBindVertexArray(vaoId);
        if (pulled) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + MmsQuadCodec.QUAD_TEXTURE_UNIT);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, quadTextureId);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
        }
    }

    /**
     * Unbinds the VAO.
     */
    public static void unbind() {
        GL30.glBindVertexArray(0);
    }

    /**
     * Gets the number of indices in this mesh.
     *
     * @return Index count
     */
    public int getIndexCount() {
        return indexCount;
    }

    /**
     * Gets the number of triangles in this mesh.
     *
     * @return Triangle count
     */
    public int getTriangleCount() {
        return indexCount / 3;
    }

    /**
     * Gets the estimated GPU memory usage.
     *
     * @return Memory usage in bytes
     */
    public long getMemoryUsageBytes() {
        return memoryUsageBytes;
    }

    /**
     * Gets the timestamp when this mesh was uploaded.
     *
     * @return Upload timestamp (milliseconds since epoch)
     */
    public long getUploadTimestamp() {
        return uploadTimestamp;
    }

    /**
     * Checks if this handle has been disposed.
     *
     * @return true if disposed
     */
    public boolean isDisposed() {
        return disposed.get();
    }

    /**
     * Gets the VAO ID (for advanced usage).
     *
     * @return OpenGL VAO ID
     * @throws IllegalStateException if handle has been disposed
     */
    public int getVaoId() {
        ensureNotDisposed();
        return vaoId;
    }

    /**
     * Ensures the handle has not been disposed.
     *
     * @throws IllegalStateException if disposed
     */
    private void ensureNotDisposed() {
        if (disposed.get()) {
            throw new IllegalStateException("Renderable handle has been disposed");
        }
    }

    /**
     * Disposes GPU resources.
     * Returns buffers to pool if pooling is enabled.
     * MUST be called from the OpenGL thread.
     * Safe to call multiple times (idempotent).
     */
    @Override
    public void close() {
        if (disposed.compareAndSet(false, true)) {
            // Active VRAM moves out of CHUNK_MESH whether the buffers go to
            // the pool (which re-tags them as BUFFER_POOL_IDLE) or are
            // outright deleted.
            GpuMemoryTracker.getInstance()
                .untrack(GpuMemoryTracker.Category.CHUNK_MESH, memoryUsageBytes);
            if (originBufferId != 0) {
                GL15.glDeleteBuffers(originBufferId);
                originBufferId = 0;
            }
            if (quadTextureId != 0) {
                GL11.glDeleteTextures(quadTextureId);
                quadTextureId = 0;
            }
            if (useBufferPool) {
                // Return buffers to pool for reuse
                MmsBufferPool pool = MmsBufferPool.getInstance();
                if (vaoId != 0) {
                    pool.returnVAO(vaoId);
                }
                if (vboId != 0) {
                    pool.returnVBO(vboId, vboSizeBytes);
                }
                if (eboId != 0 && !pulled) {
                    pool.returnEBO(eboId, eboSizeBytes);
                }
            } else {
                // Delete OpenGL resources directly
                if (vaoId != 0) {
                    GL30.glDeleteVertexArrays(vaoId);
                }
                if (vboId != 0) {
                    GL15.glDeleteBuffers(vboId);
                }
                if (eboId != 0 && !pulled) {
                    GL15.glDeleteBuffers(eboId);
                }
            }
        }
    }

    @Override
    public String toString() {
        return String.format("MmsRenderableHandle{vao=%d, vbo=%d, ebo=%d, indices=%d, memory=%d bytes, disposed=%b}",
            vaoId, vboId, eboId, indexCount, memoryUsageBytes, disposed.get());
    }
}
