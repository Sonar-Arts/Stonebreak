package com.openmason.engine.voxel.mms.mmsRegion;

import com.openmason.engine.diagnostics.GpuMemoryTracker;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL43;

import java.util.ArrayList;
import java.util.List;

/**
 * GPU-only ring holding the cull kernel's indirect draw commands, so no
 * dispatch ever rewrites commands that an earlier draw may still be reading.
 *
 * <p>Each region used to own one command buffer that every pass (camera and
 * every shadow cascade, every frame) culled into in place. GL orders a later
 * compute write against an earlier indirect read only through the driver's
 * goodwill, and the CPU runs frames ahead of the GPU: a draw still fetching
 * last frame's commands could read one that the next frame's dispatch had
 * half rewritten — after a mesh churn the same slot names a different mesh,
 * so a torn command draws one mesh's indices against another's base vertex.
 * That was the one-frame "sky-wall" flash, seen while sprinting (constant
 * churn) and gone under {@code glFinish} or {@code -Dstonebreak.gpucull=off}.
 *
 * <p>Same shape as {@code MmsStagingRing}: equal slices, each allocation
 * inside one slice. Slices touched by a pass are fenced at the pass's end
 * (after its draws, which the fence must cover), and a slice is reused only
 * once its fence has signalled. An unsignalled fence grows the ring instead
 * of stalling, up to {@link #MAX_CAPACITY}; past that the CPU waits. A
 * buffer replaced mid-pass stays alive until the pass ends, because regions
 * culled earlier in the pass still draw from it. GL-thread confined.
 */
final class MmsIndirectRing implements AutoCloseable {

    private static final int SLICES = 8;
    private static final long INITIAL_CAPACITY = 1L << 20;
    private static final long MAX_CAPACITY = 64L << 20;

    private final long alignment;
    private final long[] sliceFences = new long[SLICES];
    private final boolean[] touched = new boolean[SLICES];
    private final List<Integer> retired = new ArrayList<>();
    private int bufferId;
    private long capacity;
    private long sliceBytes;
    private long head;
    private int currentSlice = -1;
    private boolean closed;

    MmsIndirectRing() {
        // SSBO range offsets must honour the driver's alignment; indirect
        // offsets need 4. The larger satisfies both.
        alignment = Math.max(4,
            GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT));
        createStorage(INITIAL_CAPACITY);
    }

    /** The buffer the last {@link #allocate} reserved space in. */
    int bufferId() {
        return bufferId;
    }

    /** Reserves {@code bytes} for one dispatch's commands; returns the byte offset. */
    long allocate(long bytes) {
        long size = alignUp(bytes, alignment);
        if (size > sliceBytes) {
            grow(size);
        }
        long sliceEnd = (head / sliceBytes + 1) * sliceBytes;
        if (head + size > sliceEnd) {
            head = sliceEnd >= capacity ? 0 : sliceEnd;
        }
        int slice = (int) (head / sliceBytes);
        if (slice != currentSlice) {
            // Entering a slice: it must hold neither this pass's own commands
            // (the pass lapped the ring) nor ones the GPU may still read.
            // Either way, take fresh space rather than wait.
            if (touched[slice] || !reclaim(slice)) {
                grow(size);
                slice = 0;
            }
            currentSlice = slice;
        }
        touched[slice] = true;
        long offset = head;
        head += size;
        if (head >= capacity) {
            head = 0;
        }
        return offset;
    }

    /** Ends a pass: fences every slice it wrote, after the pass's draws. */
    void endPass() {
        for (int i = 0; i < SLICES; i++) {
            if (touched[i]) {
                if (sliceFences[i] != 0) {
                    GL32.glDeleteSync(sliceFences[i]);
                }
                sliceFences[i] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
                touched[i] = false;
            }
        }
        for (int id : retired) {
            GL15.glDeleteBuffers(id); // the driver keeps it until queued draws finish
        }
        retired.clear();
    }

    /**
     * True when {@code slice} can be rewritten. Below the size cap this never
     * blocks (false means "grow instead"); at the cap it waits. A slice the
     * head stays in across passes is not reclaimed: later bytes never overlap
     * earlier ones, and its next fence covers both.
     */
    private boolean reclaim(int slice) {
        long fence = sliceFences[slice];
        if (fence == 0) {
            return true;
        }
        int status = GL32.glClientWaitSync(fence, 0, 0L);
        if (status != GL32.GL_ALREADY_SIGNALED && status != GL32.GL_CONDITION_SATISFIED) {
            if (capacity < MAX_CAPACITY) {
                return false;
            }
            int flags = GL32.GL_SYNC_FLUSH_COMMANDS_BIT;
            do {
                status = GL32.glClientWaitSync(fence, flags, 1_000_000L);
                flags = 0;
            } while (status == GL32.GL_TIMEOUT_EXPIRED);
        }
        GL32.glDeleteSync(fence);
        sliceFences[slice] = 0;
        return true;
    }

    /** Moves to a bigger buffer; the old one retires at the end of the pass. */
    private void grow(long minSlice) {
        long target = capacity * 2;
        while (target / SLICES < minSlice) {
            target *= 2;
        }
        retired.add(bufferId);
        GpuMemoryTracker.getInstance().untrack(GpuMemoryTracker.Category.CHUNK_MESH, capacity);
        for (int i = 0; i < SLICES; i++) {
            if (sliceFences[i] != 0) {
                GL32.glDeleteSync(sliceFences[i]); // they guard the retiring buffer only
                sliceFences[i] = 0;
            }
            touched[i] = false;
        }
        createStorage(target); // earlier allocations this pass stay in the retired buffer
        System.out.println("[MmsIndirectRing] grew to " + (capacity >> 10) + " KiB");
    }

    private void createStorage(long bytes) {
        capacity = bytes;
        sliceBytes = bytes / SLICES;
        head = 0;
        currentSlice = -1;
        bufferId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, bufferId);
        GL15.glBufferData(GL31.GL_COPY_WRITE_BUFFER, bytes, GL15.GL_DYNAMIC_COPY);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
        GpuMemoryTracker.getInstance().track(GpuMemoryTracker.Category.CHUNK_MESH, bytes);
    }

    private static long alignUp(long value, long alignment) {
        return (value + alignment - 1) / alignment * alignment;
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
        for (int id : retired) {
            GL15.glDeleteBuffers(id);
        }
        retired.clear();
        GL15.glDeleteBuffers(bufferId);
        GpuMemoryTracker.getInstance().untrack(GpuMemoryTracker.Category.CHUNK_MESH, capacity);
    }
}
