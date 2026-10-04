package com.openmason.engine.voxel.mms.mmsRegion;

import com.openmason.engine.cearl.CearlCompiler;
import com.openmason.engine.cearl.CearlDispatcher;
import com.openmason.engine.cearl.CearlKernel;
import com.openmason.engine.voxel.mms.mmsCore.MmsUploadBufferPool;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;

import java.nio.ByteBuffer;
import java.util.Map;

/**
 * GPU-driven per-mesh culling for region rendering (GL 4.3+): a compute
 * kernel frustum-tests every live mesh in a region against six caller-supplied
 * planes and writes a {@code DrawElementsIndirectCommand} per mesh (culled
 * meshes get {@code instanceCount = 0}); the region then draws with ONE
 * {@code glMultiDrawElementsIndirect} — no per-chunk CPU visibility work, no
 * per-command CPU packing.
 *
 * <p>The kernel is authored in <b>CEARL</b> ({@code cearl/engine/culling.CEARL}, an
 * engine resource — device code lives with its owner, not in the game's plan
 * file) and runs
 * through {@link CearlDispatcher} — the first production compute shader the
 * language owns, replacing the hand-written GLSL string this class shipped
 * with. The generated source is semantically identical: same std430 layouts
 * (48-byte mesh metadata, 20-byte tightly packed commands), same
 * positive-vertex plane test, same uniforms. {@code mesh_count} stays a
 * uniform on purpose — ring ranges are alignment-padded, so the bound
 * range's own length can overstate the live count.
 *
 * <p>Pass shape (all GL-thread): {@link #beginPass} once with the pass's
 * frustum planes, {@link #cull} per region (dispatches the compute),
 * {@link #endCull} once (single command barrier), then {@link #draw} per
 * region and {@link #endDraw}. Batching every dispatch before the barrier
 * keeps it to one barrier per pass.
 *
 * <p>Plane convention: each plane is {@code (a,b,c,d)} with
 * {@code a·x + b·y + c·z + d >= 0} for points inside the frustum (JOML's
 * {@code Matrix4f.frustumPlane} output, unnormalized is fine). Each cull writes the
 * region's mesh metadata and the kernel's commands into a fenced
 * {@link MmsIndirectRing}, so no pass rewrites data a queued dispatch or
 * draw still reads.
 */
public final class MmsGpuCuller implements AutoCloseable {

    /**
     * Classpath location of the kernel source. Module-namespaced deliberately:
     * resource directories are JPMS packages, and two modules with files in
     * the same directory (the game plan lives in the game's {@code cearl/})
     * split the package and kill the boot layer — each module keeps its CEARL
     * under {@code cearl/<module>/}.
     */
    static final String CULL_RESOURCE = "/cearl/engine/culling.CEARL";

    /**
     * Compiles the kernel from its resource file (no GL needed) — exposed so
     * the headless tests can pin the kernel's shape without a context.
     */
    public static CearlKernel compileKernel() {
        String source;
        try (java.io.InputStream in = MmsGpuCuller.class.getResourceAsStream(CULL_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(CULL_RESOURCE + " missing from the engine jar");
            }
            source = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read " + CULL_RESOURCE, e);
        }
        return CearlCompiler.compile(source, "cearl/engine/culling.CEARL", Map.of())
            .kernel("cull_commands");
    }

    /** Whether the current context can run the GPU-cull path. */
    public static boolean isSupported() {
        try {
            return GL.getCapabilities().OpenGL43;
        } catch (IllegalStateException e) {
            return false; // No context on this thread.
        }
    }

    /** Bytes per {@code DrawElementsIndirectCommand} (5 uints, tightly packed). */
    private static final int CMD_STRIDE = 20;

    private final CearlDispatcher dispatcher;
    private final MmsIndirectRing commands;
    private boolean closed;

    /** Compiles and links the cull kernel. Throws when the driver rejects it. */
    public MmsGpuCuller() {
        this.dispatcher = CearlDispatcher.create(compileKernel());
        this.commands = new MmsIndirectRing();
    }

    /**
     * Starts a cull pass: binds the compute program and uploads the pass's six
     * frustum planes ({@code planes} holds 6×4 floats). The caller's bound
     * program (the pass's draw shader) is saved and restored by
     * {@link #endCull} so the indirect draws render with it.
     */
    public void beginPass(float[] planes) {
        dispatcher.begin();
        dispatcher.uniform4fv("planes", planes);
    }

    /**
     * Dispatches the frustum cull for one region's live meshes. Both the
     * kernel's metadata input and its command output take fresh space in the
     * fenced ring, so nothing a queued dispatch or draw still reads is ever
     * rewritten. Returns the command count the region's indirect draw will
     * submit (0 = nothing to do).
     */
    public int cull(MmsChunkRegion region) {
        int count = region.gpuCommandCount();
        if (count == 0) {
            return 0;
        }
        int metaBytes = count * MmsChunkRegion.GPU_META_STRIDE;
        ByteBuffer meta = MmsUploadBufferPool.acquire(metaBytes);
        region.writeGpuCullMeta(meta);
        meta.flip();
        long metaOffset = commands.allocate(metaBytes);
        int metaBuffer = commands.bufferId();
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, metaBuffer);
        GL15.glBufferSubData(GL31.GL_COPY_WRITE_BUFFER, metaOffset, meta);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);

        long cmdBytes = (long) count * CMD_STRIDE;
        // May grow the ring; the metadata then stays in the retired buffer,
        // which lives until the pass ends.
        region.gpuCmdOffsetBytes = commands.allocate(cmdBytes);
        region.gpuCmdBufferId = commands.bufferId();

        dispatcher.bindBufferRange("meshes", metaBuffer, metaOffset, metaBytes);
        dispatcher.bindBufferRange("cmds", region.gpuCmdBufferId, region.gpuCmdOffsetBytes, cmdBytes);
        dispatcher.uniform1u("mesh_count", count);
        dispatcher.dispatch(count);
        return count;
    }

    /**
     * Ends the cull phase: restores the caller's draw shader and issues one
     * barrier making every dispatched command buffer visible to the indirect
     * draws that follow.
     */
    public void endCull() {
        dispatcher.end();
        GL42.glMemoryBarrier(GL42.GL_COMMAND_BARRIER_BIT);
    }

    /**
     * Draws one culled region: binds its VAO + indirect buffer and issues a
     * single {@code glMultiDrawElementsIndirect} over every live mesh (culled
     * commands are zero-instance no-ops). Caller owns shader/state setup.
     */
    public void draw(MmsChunkRegion region) {
        int count = region.gpuCommandCount();
        if (count == 0) {
            return;
        }
        region.bind();
        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, region.gpuCmdBufferId);
        GL43.glMultiDrawElementsIndirect(GL11.GL_TRIANGLES, GL11.GL_UNSIGNED_SHORT,
            region.gpuCmdOffsetBytes, count, 0);
    }

    /**
     * Restores indirect-buffer and VAO bindings after the pass's draws and
     * fences the pass's commands, so the ring reuses them only once the GPU
     * has drawn them.
     */
    public void endDraw() {
        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, 0);
        GL30.glBindVertexArray(0);
        commands.endPass();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        dispatcher.close();
        commands.close();
    }
}
