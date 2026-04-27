package com.openmason.engine.voxel.mms.mmsCore;

import com.openmason.engine.diagnostics.GpuMemoryTracker;
import org.lwjgl.BufferUtils;
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

    // Buffer sizes (for pooling)
    private final int vboSizeBytes;
    private final int eboSizeBytes;

    // State management
    private final AtomicBoolean disposed = new AtomicBoolean(false);
    private final boolean useBufferPool;

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
    private MmsRenderableHandle(int vaoId, int vboId, int eboId, int indexCount,
                                int vboSizeBytes, int eboSizeBytes, long memoryUsageBytes,
                                boolean useBufferPool) {
        this.vaoId = vaoId;
        this.vboId = vboId;
        this.eboId = eboId;
        this.indexCount = indexCount;
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

        // Prepare interleaved data (mixed float + packed-byte layout)
        ByteBuffer interleavedData = createInterleavedVertexData(meshData);
        int vboSizeBytes = interleavedData.remaining();
        int eboSizeBytes = meshData.getIndexCount() * Integer.BYTES;

        // Acquire buffers from pool or allocate new
        MmsBufferPool pool = useBufferPool ? MmsBufferPool.getInstance() : null;
        int vaoId, vboId, eboId;

        if (pool != null) {
            vaoId = pool.acquireVAO();
            vboId = pool.acquireVBO(vboSizeBytes);
            eboId = pool.acquireEBO(eboSizeBytes);
        } else {
            vaoId = GL30.glGenVertexArrays();
            vboId = GL15.glGenBuffers();
            eboId = GL15.glGenBuffers();
        }

        // Bind VAO and setup buffers
        GL30.glBindVertexArray(vaoId);

        // Upload VBO data
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboId);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, interleavedData, GL15.GL_STATIC_DRAW);

        // Setup vertex attributes
        setupVertexAttributes();

        // Upload EBO data
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, eboId);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, meshData.getIndices(), GL15.GL_STATIC_DRAW);

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

        return new MmsRenderableHandle(
            vaoId, vboId, eboId, meshData.getIndexCount(),
            vboSizeBytes, eboSizeBytes, memoryUsage, useBufferPool
        );
    }

    /**
     * Builds the interleaved VBO data as a native ByteBuffer in little-endian
     * order. The four flag fields (water, alpha, translucent, light) are
     * packed into a single 4-byte word that GL reads as a normalized
     * {@code GL_UNSIGNED_BYTE} vec4.
     *
     * Layout per vertex (36 bytes):
     *   pos(3 floats) | tex(2 floats) | normal(3 floats) | flags(4 bytes)
     *
     * @param meshData Source mesh data
     * @return direct ByteBuffer positioned at 0, limit = vertexCount * stride
     */
    private static ByteBuffer createInterleavedVertexData(MmsMeshData meshData) {
        int vertexCount = meshData.getVertexCount();
        int totalBytes = vertexCount * MmsBufferLayout.VERTEX_STRIDE_BYTES;
        ByteBuffer buffer = BufferUtils.createByteBuffer(totalBytes);

        float[] positions = meshData.getVertexPositions();
        float[] texCoords = meshData.getTextureCoordinates();
        float[] normals = meshData.getVertexNormals();
        float[] water = meshData.getWaterHeightFlags();
        float[] alpha = meshData.getAlphaTestFlags();
        float[] translucent = meshData.getTranslucentFlags();
        float[] light = meshData.getLightValues();

        for (int i = 0; i < vertexCount; i++) {
            // Position (3 floats)
            buffer.putFloat(positions[i * 3]);
            buffer.putFloat(positions[i * 3 + 1]);
            buffer.putFloat(positions[i * 3 + 2]);

            // Texture (2 floats)
            buffer.putFloat(texCoords[i * 2]);
            buffer.putFloat(texCoords[i * 2 + 1]);

            // Normal (3 floats)
            buffer.putFloat(normals[i * 3]);
            buffer.putFloat(normals[i * 3 + 1]);
            buffer.putFloat(normals[i * 3 + 2]);

            // Packed flags (4 unsigned bytes)
            int packed = MmsBufferLayout.packFlags(water[i], alpha[i], translucent[i], light[i]);
            buffer.putInt(packed);
        }

        buffer.flip();
        return buffer;
    }

    /**
     * Configures OpenGL vertex attribute pointers for the interleaved layout.
     * Three float attributes (position, tex, normal) plus one packed-byte vec4
     * attribute (water/alpha/translucent/light), normalized so the shader
     * reads it as a [0,1] vec4.
     */
    private static void setupVertexAttributes() {
        int stride = MmsBufferLayout.VERTEX_STRIDE_BYTES;

        // Position attribute (location 0) — 3 floats
        GL30.glEnableVertexAttribArray(MmsBufferLayout.POSITION_LOCATION);
        GL30.glVertexAttribPointer(
            MmsBufferLayout.POSITION_LOCATION,
            MmsBufferLayout.POSITION_SIZE,
            GL15.GL_FLOAT,
            false,
            stride,
            MmsBufferLayout.POSITION_OFFSET
        );

        // Texture coordinate attribute (location 1) — 2 floats
        GL30.glEnableVertexAttribArray(MmsBufferLayout.TEXTURE_LOCATION);
        GL30.glVertexAttribPointer(
            MmsBufferLayout.TEXTURE_LOCATION,
            MmsBufferLayout.TEXTURE_SIZE,
            GL15.GL_FLOAT,
            false,
            stride,
            MmsBufferLayout.TEXTURE_OFFSET
        );

        // Normal attribute (location 2) — 3 floats
        GL30.glEnableVertexAttribArray(MmsBufferLayout.NORMAL_LOCATION);
        GL30.glVertexAttribPointer(
            MmsBufferLayout.NORMAL_LOCATION,
            MmsBufferLayout.NORMAL_SIZE,
            GL15.GL_FLOAT,
            false,
            stride,
            MmsBufferLayout.NORMAL_OFFSET
        );

        // Packed flags (location 3) — 4 unsigned bytes, normalized.
        // Shader reads as vec4 aFlags: .x=water, .y=alpha, .z=translucent, .w=light
        GL30.glEnableVertexAttribArray(MmsBufferLayout.FLAGS_LOCATION);
        GL30.glVertexAttribPointer(
            MmsBufferLayout.FLAGS_LOCATION,
            MmsBufferLayout.FLAGS_COMPONENTS,
            GL15.GL_UNSIGNED_BYTE,
            true, // normalized → shader sees [0,1]
            stride,
            MmsBufferLayout.FLAGS_OFFSET
        );
    }

    /**
     * Renders the mesh.
     * MUST be called from the OpenGL thread.
     *
     * @throws IllegalStateException if handle has been disposed
     */
    public void render() {
        ensureNotDisposed();

        GL30.glBindVertexArray(vaoId);
        GL15.glDrawElements(GL15.GL_TRIANGLES, indexCount, GL15.GL_UNSIGNED_INT, 0);
        GL30.glBindVertexArray(0);
    }

    /**
     * Renders a sub-range of the mesh indices.
     * VAO must be bound first via {@link #bind()}.
     *
     * @param indexOffset byte offset into the index buffer (index * 4 for GL_UNSIGNED_INT)
     * @param count       number of indices to draw
     */
    public void renderRange(int indexOffset, int count) {
        GL15.glDrawElements(GL15.GL_TRIANGLES, count, GL15.GL_UNSIGNED_INT, (long) indexOffset * 4L);
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
            if (useBufferPool) {
                // Return buffers to pool for reuse
                MmsBufferPool pool = MmsBufferPool.getInstance();
                if (vaoId != 0) {
                    pool.returnVAO(vaoId);
                }
                if (vboId != 0) {
                    pool.returnVBO(vboId, vboSizeBytes);
                }
                if (eboId != 0) {
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
                if (eboId != 0) {
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
