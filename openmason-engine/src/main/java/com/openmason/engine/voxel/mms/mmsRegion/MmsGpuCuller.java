package com.openmason.engine.voxel.mms.mmsRegion;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;

/**
 * GPU-driven per-mesh culling for region rendering (GL 4.3+): a tiny compute
 * shader frustum-tests every live mesh in a region against six caller-supplied
 * planes and writes a {@code DrawElementsIndirectCommand} per mesh (culled
 * meshes get {@code instanceCount = 0}); the region then draws with ONE
 * {@code glMultiDrawElementsIndirect} — no per-chunk CPU visibility work, no
 * per-command CPU packing.
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

    private static final int WORKGROUP_SIZE = 64;

    private static final String CULL_COMPUTE_SOURCE = """
            #version 430 core
            layout(local_size_x = 64) in;

            struct MeshMeta {
                vec4 minB;   // xyz = AABB min
                vec4 maxB;   // xyz = AABB max
                uvec4 draw;  // x = indexCount, y = firstIndex, z = baseVertex
            };
            layout(std430, binding = 0) readonly restrict buffer MeshMetaBuf {
                MeshMeta meshes[];
            };

            struct DrawCmd {
                uint count;
                uint instanceCount;
                uint firstIndex;
                uint baseVertex;
                uint baseInstance;
            };
            layout(std430, binding = 1) writeonly restrict buffer DrawCmdBuf {
                DrawCmd cmds[];
            };

            uniform vec4 u_planes[6];
            uniform uint u_meshCount;

            void main() {
                uint i = gl_GlobalInvocationID.x;
                if (i >= u_meshCount) {
                    return;
                }
                MeshMeta m = meshes[i];
                bool visible = true;
                for (int p = 0; p < 6 && visible; ++p) {
                    vec4 pl = u_planes[p];
                    // Positive vertex: the AABB corner farthest along the
                    // plane normal — if even it is behind the plane, the whole
                    // box is out.
                    vec3 v = vec3(pl.x > 0.0 ? m.maxB.x : m.minB.x,
                                  pl.y > 0.0 ? m.maxB.y : m.minB.y,
                                  pl.z > 0.0 ? m.maxB.z : m.minB.z);
                    visible = dot(pl.xyz, v) + pl.w >= 0.0;
                }
                cmds[i].count = m.draw.x;
                cmds[i].instanceCount = visible ? 1u : 0u;
                cmds[i].firstIndex = m.draw.y;
                cmds[i].baseVertex = m.draw.z;
                cmds[i].baseInstance = 0u;
            }
            """;

    /** Whether the current context can run the GPU-cull path. */
    public static boolean isSupported() {
        try {
            return GL.getCapabilities().OpenGL43;
        } catch (IllegalStateException e) {
            return false; // No context on this thread.
        }
    }

    private final int programId;
    private final int planesLocation;
    private final int meshCountLocation;
    private int savedProgramId;
    private boolean closed;

    /** Compiles the cull program. Throws when compilation/linking fails. */
    public MmsGpuCuller() {
        int shader = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL20.glShaderSource(shader, CULL_COMPUTE_SOURCE);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("Cull compute shader failed to compile: " + log);
        }
        programId = GL20.glCreateProgram();
        GL20.glAttachShader(programId, shader);
        GL20.glLinkProgram(programId);
        GL20.glDeleteShader(shader);
        if (GL20.glGetProgrami(programId, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(programId);
            GL20.glDeleteProgram(programId);
            throw new IllegalStateException("Cull compute program failed to link: " + log);
        }
        planesLocation = GL20.glGetUniformLocation(programId, "u_planes");
        meshCountLocation = GL20.glGetUniformLocation(programId, "u_meshCount");
    }

    /**
     * Starts a cull pass: binds the compute program and uploads the pass's six
     * frustum planes ({@code planes} holds 6×4 floats). The caller's bound
     * program (the pass's draw shader) is saved and restored by
     * {@link #endCull} so the indirect draws render with it.
     */
    public void beginPass(float[] planes) {
        savedProgramId = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        GL20.glUseProgram(programId);
        GL20.glUniform4fv(planesLocation, planes);
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
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, region.gpuMetaBuffer());
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, region.gpuIndirectBuffer());
        GL30.glUniform1ui(meshCountLocation, count);
        GL43.glDispatchCompute((count + WORKGROUP_SIZE - 1) / WORKGROUP_SIZE, 1, 1);
        return count;
    }

    /**
     * Ends the cull phase: restores the caller's draw shader and issues one
     * barrier making every dispatched command buffer visible to the indirect
     * draws that follow.
     */
    public void endCull() {
        GL20.glUseProgram(savedProgramId);
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
        GL20.glDeleteProgram(programId);
    }
}
