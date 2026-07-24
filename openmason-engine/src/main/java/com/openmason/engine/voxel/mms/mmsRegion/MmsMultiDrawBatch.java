package com.openmason.engine.voxel.mms.mmsRegion;

import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryUtil;

import java.nio.IntBuffer;

/**
 * Reusable command storage for one {@code glMultiDrawElementsBaseVertex}
 * call: parallel native arrays of element counts, index-buffer byte offsets
 * and base vertices. Grow-on-demand; owned by the region renderer and reused
 * across every region and pass, so filling a batch allocates nothing.
 *
 * <p>GL-thread confined. {@link #close()} releases the native memory.
 */
public final class MmsMultiDrawBatch implements AutoCloseable {

    private IntBuffer counts;
    private PointerBuffer offsets;
    private IntBuffer baseVertices;
    private int size;

    public MmsMultiDrawBatch(int initialCapacity) {
        int cap = Math.max(initialCapacity, 16);
        counts = MemoryUtil.memAllocInt(cap);
        offsets = MemoryUtil.memAllocPointer(cap);
        baseVertices = MemoryUtil.memAllocInt(cap);
    }

    /** Removes all commands (native capacity is kept). */
    public void reset() {
        size = 0;
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * Appends one draw command.
     *
     * @param indexCount       number of indices to draw
     * @param indexOffsetBytes byte offset of the mesh's indices in the region index buffer
     * @param baseVertex       the mesh's first vertex in the region vertex buffer
     */
    public void add(int indexCount, long indexOffsetBytes, int baseVertex) {
        if (size == counts.capacity()) {
            grow();
        }
        counts.put(size, indexCount);
        offsets.put(size, indexOffsetBytes);
        baseVertices.put(size, baseVertex);
        size++;
    }

    /**
     * Issues the multidraw for the accumulated commands. The caller must have
     * the region VAO bound (its element buffer supplies the indices).
     *
     * @param indexType GL element type, e.g. {@code GL_UNSIGNED_SHORT}
     */
    public void draw(int indexType) {
        if (size == 0) {
            return;
        }
        counts.position(0).limit(size);
        offsets.position(0).limit(size);
        baseVertices.position(0).limit(size);
        GL32.glMultiDrawElementsBaseVertex(GL11.GL_TRIANGLES, counts, indexType, offsets, baseVertices);
        counts.clear();
        offsets.clear();
        baseVertices.clear();
    }

    private void grow() {
        int newCap = counts.capacity() * 2;
        counts = MemoryUtil.memRealloc(counts, newCap);
        offsets = MemoryUtil.memRealloc(offsets, newCap);
        baseVertices = MemoryUtil.memRealloc(baseVertices, newCap);
        // All access is absolute-indexed (or explicitly positioned in draw),
        // but normalize position/limit so capacity checks stay simple.
        counts.clear();
        offsets.clear();
        baseVertices.clear();
    }

    @Override
    public void close() {
        MemoryUtil.memFree(counts);
        MemoryUtil.memFree(offsets);
        MemoryUtil.memFree(baseVertices);
        counts = null;
        offsets = null;
        baseVertices = null;
    }
}
