package com.openmason.engine.voxel.mms.mmsRegion;

import com.openmason.engine.cearl.CearlKernel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the CEARL-authored cull kernel's compiled shape — everything the
 * dispatcher and the GPU contract depend on, verifiable without a GL context.
 * The std430 layouts must match {@code MmsChunkRegion}'s buffer strides
 * (48-byte metadata, 20-byte tightly packed indirect commands).
 */
class MmsGpuCullerKernelTest {

    @Test
    void kernelCompilesWithTheDispatchContract() {
        CearlKernel kernel = MmsGpuCuller.compileKernel();
        assertEquals("cull_commands", kernel.name());
        assertEquals(64, kernel.localSize());

        // Buffer takes in declaration order: metadata then commands.
        assertEquals(2, kernel.buffers().size());
        assertEquals("meshes", kernel.buffers().getFirst().name());
        assertEquals(0, kernel.buffers().getFirst().binding());
        assertEquals("cmds", kernel.buffers().get(1).name());
        assertEquals(1, kernel.buffers().get(1).binding());

        // Uniforms: the six frustum + four keep-box planes as a uniform array + the live mesh count.
        assertEquals(2, kernel.uniforms().size());
        assertEquals("planes", kernel.uniforms().getFirst().name());
        assertEquals(MmsGpuCuller.PLANES, kernel.uniforms().getFirst().arraySize());
        assertEquals("mesh_count", kernel.uniforms().get(1).name());
        assertEquals("uint", kernel.uniforms().get(1).glslType());
    }

    /** The kernel's positive-vertex test, against all ten planes of a pass. */
    private static boolean visible(float[] planes, float[] min, float[] max) {
        for (int p = 0; p < MmsGpuCuller.PLANES; p++) {
            float a = planes[p * 4], b = planes[p * 4 + 1], c = planes[p * 4 + 2], d = planes[p * 4 + 3];
            float x = a > 0 ? max[0] : min[0], y = b > 0 ? max[1] : min[1], z = c > 0 ? max[2] : min[2];
            if (a * x + b * y + c * z + d < 0) {
                return false;
            }
        }
        return true;
    }

    /** Frustum slots 0..5 left always-pass, so only the keep box decides. */
    private static float[] pass(float[] keepXZ) {
        float[] planes = new float[MmsGpuCuller.PLANES * 4];
        for (int p = 0; p < 6; p++) {
            planes[p * 4 + 3] = 1f;
        }
        MmsGpuCuller.keepBoxPlanes(keepXZ, planes);
        return planes;
    }

    @Test
    void keepBoxDropsMeshesOutsideItAndOnlyThose() {
        // A 3x3-chunk square around chunk (0,0), inset half a block like the camera pass's.
        float[] planes = pass(new float[]{-16f + 0.5f, -16f + 0.5f, 32f - 0.5f, 32f - 0.5f});
        for (int cx = -3; cx <= 3; cx++) {
            for (int cz = -3; cz <= 3; cz++) {
                boolean inside = Math.abs(cx) <= 1 && Math.abs(cz) <= 1;
                float[] min = {cx * 16f, 60f, cz * 16f};
                float[] max = {cx * 16f + 16f, 90f, cz * 16f + 16f};
                assertEquals(inside, visible(planes, min, max), "chunk " + cx + "," + cz);
            }
        }
        assertTrue(visible(pass(null), new float[]{1e6f, 0f, -1e6f}, new float[]{1e6f + 16f, 9f, -1e6f + 16f}),
            "no box: nothing culled by it");
    }

    @Test
    void generatedGlslCarriesTheGpuContract() {
        String glsl = MmsGpuCuller.compileKernel().glsl();
        assertTrue(glsl.contains("layout(local_size_x = 64"), glsl);
        assertTrue(glsl.contains(
            "layout(std430, binding = 0) readonly buffer cearl_meshes { MeshMeta meshes[]; };"),
            glsl);
        assertTrue(glsl.contains(
            "layout(std430, binding = 1) writeonly buffer cearl_cmds { DrawCmd cmds[]; };"),
            glsl);
        assertTrue(glsl.contains("uniform vec4 planes[10];"), glsl);
        assertTrue(glsl.contains("uniform uint mesh_count;"), glsl);
        // The five tightly packed uints of DrawElementsIndirectCommand, in order.
        int a = glsl.indexOf("uint index_count;");
        int b = glsl.indexOf("uint instance_count;");
        int c = glsl.indexOf("uint first_index;");
        int d = glsl.indexOf("uint base_vertex;");
        int e = glsl.indexOf("uint base_instance;");
        assertTrue(a >= 0 && a < b && b < c && c < d && d < e, glsl);
        // pick() lowers to the branchless ternary of the original hand GLSL.
        assertTrue(glsl.contains("((pl.x > 0.0) ? m.maxb.x : m.minb.x)"), glsl);
    }
}
