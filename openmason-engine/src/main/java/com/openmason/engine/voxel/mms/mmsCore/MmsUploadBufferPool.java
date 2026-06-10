package com.openmason.engine.voxel.mms.mmsCore;

import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;

/**
 * Reusable direct staging buffer for GPU uploads.
 *
 * <p>Mesh uploads run serially on the GL thread, and {@code glBufferData}
 * copies the client data synchronously during the call — so one cached,
 * grow-on-demand direct buffer per GL thread replaces the fresh
 * {@code BufferUtils.createByteBuffer} (~60 KB at 10-16 uploads/frame)
 * previously allocated per upload.
 *
 * <p>Contract: the returned buffer is only valid until the next
 * {@link #acquire} on the same thread. Callers must finish their
 * {@code glBufferData} call before acquiring again.
 */
public final class MmsUploadBufferPool {

    /** Buffers larger than this are not retained — one outlier mesh must not pin megabytes. */
    private static final int MAX_RETAINED_BYTES = 4 * 1024 * 1024;

    private static final ThreadLocal<ByteBuffer> CACHED = new ThreadLocal<>();

    private MmsUploadBufferPool() {
    }

    /**
     * Returns a cleared direct ByteBuffer with capacity >= minBytes,
     * reusing the thread's cached buffer when it is large enough.
     */
    public static ByteBuffer acquire(int minBytes) {
        ByteBuffer buffer = CACHED.get();
        if (buffer == null || buffer.capacity() < minBytes) {
            buffer = BufferUtils.createByteBuffer(roundUpCapacity(minBytes));
            if (buffer.capacity() <= MAX_RETAINED_BYTES) {
                CACHED.set(buffer);
            } else {
                CACHED.remove();
            }
        }
        buffer.clear();
        return buffer;
    }

    private static int roundUpCapacity(int minBytes) {
        // Next power of two, so steadily growing meshes reallocate O(log n) times.
        int capacity = Integer.highestOneBit(Math.max(minBytes, 1));
        return capacity == minBytes ? minBytes : capacity << 1;
    }
}
