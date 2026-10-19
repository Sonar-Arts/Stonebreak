package com.openmason.engine.voxel.mms.mmsRegion;

import com.openmason.engine.cearl.CearlCompiler;
import com.openmason.engine.cearl.CearlDispatcher;
import com.openmason.engine.cearl.CearlKernel;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;

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
 * uniform on purpose — the metadata SSBO is pow2-overallocated, so the
 * buffer's own length lies about the live count.
 *
 * <p>Pass shape (all GL-thread): {@link #beginPass} once with the pass's
 * frustum planes, {@link #cull} per region (dispatches the compute),
 * {@link #endCull} once (single command barrier), then {@link #draw} per
 * region and {@link #endDraw}. Batching every dispatch before the barrier
 * keeps it to one barrier per pass.
 *
 * <p>Plane convention: each plane is {@code (a,b,c,d)} with
 * {@code a·x + b·y + c·z + d >= 0} for points inside the frustum (JOML's
 * {@code Matrix4f.frustumPlane} output, unnormalized is fine). Metadata and
 * command buffers live on {@link MmsChunkRegion} and rebuild lazily via
 * {@link MmsChunkRegion#prepareGpuCull()}.
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

    private final CearlDispatcher dispatcher;
    private boolean closed;

    /** Compiles and links the cull kernel. Throws when the driver rejects it. */
    public MmsGpuCuller() {
        this.dispatcher = CearlDispatcher.create(compileKernel());
    }

    /** Frustum planes plus keep-box planes, as the kernel takes them. */
    static final int PLANES = 10;
    /** A plane every point passes: an unused keep-box slot. */
    private static final float[] ALWAYS = {0f, 0f, 0f, 1f};
    private final float[] passPlanes = new float[PLANES * 4];

    /**
     * Starts a cull pass: binds the compute program and uploads the pass's six
     * frustum planes ({@code planes} holds 6×4 floats). The caller's bound
     * program (the pass's draw shader) is saved and restored by
     * {@link #endCull} so the indirect draws render with it.
     */
    public void beginPass(float[] planes) {
        beginPass(planes, null);
    }

    /**
     * {@link #beginPass(float[])} that also drops every mesh whose AABB lies wholly outside
     * the vertical box {@code keepXZ = {minX, minZ, maxX, maxZ}} (null = no box). A pass that
     * must draw exactly what a CPU distance walk would — the camera pass, whose ring edge
     * other renderers (FastLOD) take over — passes that walk's footprint here.
     */
    public void beginPass(float[] planes, float[] keepXZ) {
        System.arraycopy(planes, 0, passPlanes, 0, 6 * 4);
        keepBoxPlanes(keepXZ, passPlanes);
        dispatcher.begin();
        dispatcher.uniform4fv("planes", passPlanes);
    }

    /** Writes planes 6..9 of {@code planes}: the keep box's four sides, or always-pass planes. */
    static void keepBoxPlanes(float[] keepXZ, float[] planes) {
        if (keepXZ == null) {
            for (int p = 6; p < PLANES; p++) {
                System.arraycopy(ALWAYS, 0, planes, p * 4, 4);
            }
            return;
        }
        setPlane(planes, 6, 1f, 0f, -keepXZ[0]);    // x >= minX
        setPlane(planes, 7, 0f, 1f, -keepXZ[1]);    // z >= minZ
        setPlane(planes, 8, -1f, 0f, keepXZ[2]);    // x <= maxX
        setPlane(planes, 9, 0f, -1f, keepXZ[3]);    // z <= maxZ
    }

    private static void setPlane(float[] planes, int p, float a, float c, float d) {
        planes[p * 4] = a;
        planes[p * 4 + 1] = 0f;
        planes[p * 4 + 2] = c;
        planes[p * 4 + 3] = d;
    }

    /**
     * Dispatches the frustum cull for one region's live meshes. Returns the
     * command count the region's indirect draw will submit (0 = nothing to do).
     */
    public int cull(MmsChunkRegion region) {
        int count = region.prepareGpuCull();
        if (count == 0) {
            return 0;
        }
        dispatcher.bindBuffer("meshes", region.gpuMetaBuffer());
        dispatcher.bindBuffer("cmds", region.gpuIndirectBuffer());
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
        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, region.gpuIndirectBuffer());
        GL43.glMultiDrawElementsIndirect(GL11.GL_TRIANGLES, GL11.GL_UNSIGNED_SHORT, 0L, count, 0);
    }

    /** Restores indirect-buffer and VAO bindings after the pass's draws. */
    public void endDraw() {
        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, 0);
        GL30.glBindVertexArray(0);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        dispatcher.close();
    }
}
