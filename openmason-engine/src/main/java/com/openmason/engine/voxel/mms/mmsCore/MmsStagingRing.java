package com.openmason.engine.voxel.mms.mmsCore;

import com.openmason.engine.diagnostics.GpuMemoryTracker;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL44;

import java.nio.ByteBuffer;

/**
 * Persistent-mapped staging ring for streaming chunk-mesh bytes into region
 * arenas (GL 4.4 / {@code ARB_buffer_storage} path).
 *
 * <p>The classic upload path hands {@code glBufferSubData} a client pointer,
 * which forces the driver to allocate + schedule an internal staging copy per
 * call (and may stall when the destination is in flight). Here the CPU writes
 * straight into a persistently mapped, coherent ring buffer and the GPU pulls
 * from it with {@code glCopyBufferSubData} — no driver-side staging, no map/
 * unmap per upload.
 *
 * <p>The ring is divided into a few slices; each upload lives entirely inside
 * one slice. A fence is inserted when the write head leaves a slice, and the
 * head waits on a slice's old fence before rewriting it — so CPU writes can
 * never overtake GPU copies still reading the previous lap. With the default
 * 8 MiB capacity against ~40 KB typical chunk meshes, waits are essentially
 * never hit in practice.
 *
 * <p>GL-thread confined, like every mesh upload. Created lazily via
 * {@link #sharedInstance()}; returns null (callers keep the classic
 * {@code glBufferSubData} path) when the context lacks GL 4.4 or
 * {@code -Dstonebreak.stagingring=off} is set.
 */
public final class MmsStagingRing implements AutoCloseable {

    private static final long DEFAULT_CAPACITY = 8L * 1024 * 1024;
    private static final int SLICES = 4;
    /** Write-head alignment between uploads (cache-line friendly). */
    private static final long ALIGNMENT = 64;

    private static MmsStagingRing shared;
    private static boolean sharedDecided;

    /**
     * The shared per-context ring, created on first call (GL thread with a
     * current context required). Null when unsupported or disabled.
     */
    public static MmsStagingRing sharedInstance() {
        if (!sharedDecided) {
            String prop = System.getProperty("stonebreak.stagingring", "on");
            boolean off = "off".equalsIgnoreCase(prop) || "false".equalsIgnoreCase(prop);
            boolean capable = false;
            if (!off) {
                try {
                    capable = GL.getCapabilities().OpenGL44
                            || GL.getCapabilities().GL_ARB_buffer_storage;
                } catch (IllegalStateException e) {
                    return null; // No context on this thread — stay undecided.
                }
            }
            sharedDecided = true;
            if (capable) {
                try {
                    shared = new MmsStagingRing(DEFAULT_CAPACITY);
                    System.out.println("[MmsStagingRing] Persistent-mapped staging ENABLED ("
                            + (DEFAULT_CAPACITY >> 20) + " MiB ring)");
                } catch (RuntimeException e) {
                    System.err.println("[MmsStagingRing] Init failed, falling back to glBufferSubData: "
                            + e.getMessage());
                    shared = null;
                }
            } else {
                System.out.println("[MmsStagingRing] Persistent-mapped staging disabled ("
                        + (off ? "-Dstonebreak.stagingring=off" : "no GL 4.4 / ARB_buffer_storage") + ")");
            }
        }
        return shared;
    }

    private final int bufferId;
    private final long capacity;
    private final long sliceBytes;
    private final ByteBuffer mapped;
    private final long[] sliceFences = new long[SLICES];
    private long head;
    private int currentSlice = -1;
    private boolean closed;

    MmsStagingRing(long capacity) {
        this.capacity = capacity;
        this.sliceBytes = capacity / SLICES;
        this.bufferId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, bufferId);
        int flags = GL30.GL_MAP_WRITE_BIT | GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_MAP_COHERENT_BIT;
        GL44.glBufferStorage(GL31.GL_COPY_READ_BUFFER, capacity, flags);
        this.mapped = GL30.glMapBufferRange(GL31.GL_COPY_READ_BUFFER, 0, capacity, flags, null);
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
        if (mapped == null) {
            GL15.glDeleteBuffers(bufferId);
            throw new IllegalStateException("glMapBufferRange(PERSISTENT|COHERENT) returned null");
        }
        GpuMemoryTracker.getInstance().track(GpuMemoryTracker.Category.OTHER, capacity);
    }

    /**
     * Streams {@code data} into {@code targetBuffer} at {@code targetOffsetBytes}
     * through the ring. Returns false when this upload can't ride the ring
     * (oversized, or offsets violate {@code glCopyBufferSubData}'s 4-byte
     * alignment rule) — the caller must then use its classic upload path.
     */
    public boolean upload(int targetBuffer, long targetOffsetBytes, byte[] data) {
        if (closed) {
            return false;
        }
        int len = data.length;
        if (len == 0) {
            return true;
        }
        if (len > sliceBytes || (len & 3) != 0 || (targetOffsetBytes & 3L) != 0) {
            return false;
        }

        // Keep each upload inside one slice: skip to the next slice start when
        // the write would straddle a boundary.
        long sliceEnd = (head / sliceBytes + 1) * sliceBytes;
        if (head + len > sliceEnd) {
            head = sliceEnd >= capacity ? 0 : sliceEnd;
        }
        int slice = (int) (head / sliceBytes);
        if (slice != currentSlice) {
            if (currentSlice >= 0) {
                fenceSlice(currentSlice); // Departed slice: fence its copies.
            }
            waitSlice(slice); // Previous lap's copies must be done before rewrite.
            currentSlice = slice;
        }

        mapped.clear();
        mapped.position((int) head);
        mapped.put(data, 0, len);

        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, bufferId);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, targetBuffer);
        GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER,
                head, targetOffsetBytes, len);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);

        head = (head + len + ALIGNMENT - 1) & -ALIGNMENT;
        if (head >= capacity) {
            fenceSlice(currentSlice);
            head = 0;
            currentSlice = -1;
        }
        return true;
    }

    private void fenceSlice(int slice) {
        if (sliceFences[slice] != 0) {
            GL32.glDeleteSync(sliceFences[slice]);
        }
        sliceFences[slice] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    }

    private void waitSlice(int slice) {
        long fence = sliceFences[slice];
        if (fence == 0) {
            return;
        }
        // First wait flushes so the fence can actually be reached; loop until
        // signaled. One-millisecond slices keep a pathological wait observable.
        int flags = GL32.GL_SYNC_FLUSH_COMMANDS_BIT;
        while (true) {
            int status = GL32.glClientWaitSync(fence, flags, 1_000_000L);
            if (status == GL32.GL_ALREADY_SIGNALED || status == GL32.GL_CONDITION_SATISFIED
                    || status == GL32.GL_WAIT_FAILED) {
                break;
            }
            flags = 0; // Only flush once.
        }
        GL32.glDeleteSync(fence);
        sliceFences[slice] = 0;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (int i = 0; i < SLICES; i++) {
            if (sliceFences[i] != 0) {
                GL32.glDeleteSync(sliceFences[i]);
                sliceFences[i] = 0;
            }
        }
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, bufferId);
        GL15.glUnmapBuffer(GL31.GL_COPY_READ_BUFFER);
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
        GL15.glDeleteBuffers(bufferId);
        GpuMemoryTracker.getInstance().untrack(GpuMemoryTracker.Category.OTHER, capacity);
        if (shared == this) {
            shared = null;
        }
    }
}
