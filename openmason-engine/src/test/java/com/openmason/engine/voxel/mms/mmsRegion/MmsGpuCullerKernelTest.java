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

        // Uniforms: the six planes as a uniform array + the live mesh count.
        assertEquals(2, kernel.uniforms().size());
        assertEquals("planes", kernel.uniforms().getFirst().name());
        assertEquals(6, kernel.uniforms().getFirst().arraySize());
        assertEquals("mesh_count", kernel.uniforms().get(1).name());
        assertEquals("uint", kernel.uniforms().get(1).glslType());
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
        assertTrue(glsl.contains("uniform vec4 planes[6];"), glsl);
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
