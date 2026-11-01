package com.openmason.engine.cearl;

import java.util.List;

/**
 * A compiled CEARL kernel: the generated GLSL-430 compute source plus the
 * binding layout a dispatcher needs to feed it — SSBO buffer bindings in
 * declaration order and the uniforms by name.
 *
 * <p>Phase 1 produces the source and layout; the GL dispatch runtime that
 * compiles the program, binds buffers, and issues {@code glDispatchCompute}
 * is the next layer up.
 */
public record CearlKernel(String name, int localSize,
                          List<BufferBinding> buffers, List<UniformBinding> uniforms,
                          String glsl) {

    /** One SSBO in the kernel signature. {@code binding} is the layout index. */
    public record BufferBinding(String name, CearlAst.Dir dir, String glslType, int binding) {
    }

    /** {@code arraySize} 0 = scalar uniform; &gt; 0 = uniform array of that length. */
    public record UniformBinding(String name, String glslType, int arraySize) {
    }
}
