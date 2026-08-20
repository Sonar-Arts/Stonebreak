package com.openmason.engine.cearl;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;

import java.util.HashMap;
import java.util.Map;

/**
 * Executes a compiled {@link CearlKernel} on the GPU — the Phase 2 runtime
 * that turns CEARL's generated GLSL-430 compute into dispatches (GL 4.3+).
 *
 * <p>One dispatcher owns one linked compute program. Pass shape (GL-thread):
 * {@link #begin()} once (saves the caller's bound program and binds the
 * kernel's), then any number of {@link #bindBuffer}/uniform/{@link #dispatch}
 * rounds, then {@link #end()} (restores the caller's program). Memory
 * barriers stay with the caller — only it knows what consumes the results.
 *
 * <p>Buffers bind by their CEARL take name to the SSBO binding index the
 * compiler recorded; uniforms set by take name with type checked against the
 * kernel's signature. Every misuse is a {@link CearlException} that names
 * the valid takes — the same teaching contract as the compiler.
 */
public final class CearlDispatcher implements AutoCloseable {

    /** Whether the current context can run compute kernels. */
    public static boolean isSupported() {
        try {
            return GL.getCapabilities().OpenGL43;
        } catch (IllegalStateException e) {
            return false; // No context on this thread.
        }
    }

    private final CearlKernel kernel;
    private final int programId;
    private final Map<String, Integer> uniformLocations = new HashMap<>();
    private final Map<String, CearlKernel.UniformBinding> uniformsByName = new HashMap<>();
    private final Map<String, CearlKernel.BufferBinding> buffersByName = new HashMap<>();
    private int savedProgramId;
    private boolean closed;

    private CearlDispatcher(CearlKernel kernel, int programId) {
        this.kernel = kernel;
        this.programId = programId;
        for (CearlKernel.UniformBinding u : kernel.uniforms()) {
            uniformsByName.put(u.name(), u);
            uniformLocations.put(u.name(), GL20.glGetUniformLocation(programId, u.name()));
        }
        for (CearlKernel.BufferBinding b : kernel.buffers()) {
            buffersByName.put(b.name(), b);
        }
    }

    /**
     * Compiles and links the kernel's GLSL on the current context. Throws a
     * {@link CearlException} carrying the driver's info log on failure —
     * callers keep their non-compute fallback exactly as before.
     */
    public static CearlDispatcher create(CearlKernel kernel) {
        int shader = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL20.glShaderSource(shader, kernel.glsl());
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new CearlException(kernel.name(), 0, 0,
                "the driver rejected the generated compute shader: " + log);
        }
        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, shader);
        GL20.glLinkProgram(program);
        GL20.glDeleteShader(shader);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(program);
            GL20.glDeleteProgram(program);
            throw new CearlException(kernel.name(), 0, 0,
                "the driver failed to link the compute program: " + log);
        }
        return new CearlDispatcher(kernel, program);
    }

    public CearlKernel kernel() {
        return kernel;
    }

    /** Saves the caller's bound program and binds the kernel's. */
    public void begin() {
        ensureOpen();
        savedProgramId = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        GL20.glUseProgram(programId);
    }

    /** Restores the program that was bound at {@link #begin()}. */
    public void end() {
        GL20.glUseProgram(savedProgramId);
    }

    /** Binds a GL buffer to the SSBO slot of the named buffer take. */
    public void bindBuffer(String takeName, int glBufferId) {
        CearlKernel.BufferBinding b = buffersByName.get(takeName);
        if (b == null) {
            throw unknownTake(takeName, "buffer", buffersByName.keySet());
        }
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, b.binding(), glBufferId);
    }

    public void uniform1u(String name, int value) {
        GL30.glUniform1ui(location(name, "uint", 0), value);
    }

    public void uniform1i(String name, int value) {
        GL20.glUniform1i(location(name, "int", 0), value);
    }

    public void uniform1f(String name, float value) {
        GL20.glUniform1f(location(name, "float", 0), value);
    }

    public void uniform3f(String name, float x, float y, float z) {
        GL20.glUniform3f(location(name, "vec3", 0), x, y, z);
    }

    public void uniform4f(String name, float x, float y, float z, float w) {
        GL20.glUniform4f(location(name, "vec4", 0), x, y, z, w);
    }

    /** Sets a vec4 uniform array; {@code values} holds arraySize × 4 floats. */
    public void uniform4fv(String name, float[] values) {
        CearlKernel.UniformBinding u = uniformsByName.get(name);
        int location = location(name, "vec4", values.length / 4);
        if (values.length != u.arraySize() * 4) {
            throw new CearlException(kernel.name(), 0, 0, "uniform array '" + name
                + "' takes " + u.arraySize() + " vec4s (" + (u.arraySize() * 4)
                + " floats), got " + values.length);
        }
        GL20.glUniform4fv(location, values);
    }

    /**
     * Dispatches one round over {@code elementCount} data elements —
     * workgroup count is rounded up from the kernel's declared local size.
     * The caller issues whatever {@code glMemoryBarrier} its consumers need.
     */
    public void dispatch(int elementCount) {
        if (elementCount <= 0) {
            return;
        }
        int groups = (elementCount + kernel.localSize() - 1) / kernel.localSize();
        GL43.glDispatchCompute(groups, 1, 1);
    }

    private int location(String name, String expectedType, int expectedArray) {
        CearlKernel.UniformBinding u = uniformsByName.get(name);
        if (u == null) {
            throw unknownTake(name, "uniform", uniformsByName.keySet());
        }
        if (!u.glslType().equals(expectedType)
                || (expectedArray == 0) != (u.arraySize() == 0)) {
            throw new CearlException(kernel.name(), 0, 0, "uniform '" + name + "' is "
                + u.glslType() + (u.arraySize() > 0 ? "[" + u.arraySize() + "]" : "")
                + " — use the matching setter");
        }
        return uniformLocations.get(name);
    }

    private CearlException unknownTake(String name, String what, Iterable<String> known) {
        return new CearlException(kernel.name(), 0, 0, "kernel '" + kernel.name()
            + "' has no " + what + " take named '" + name + "' — takes: " + known);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("dispatcher for kernel '" + kernel.name()
                + "' is closed");
        }
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
