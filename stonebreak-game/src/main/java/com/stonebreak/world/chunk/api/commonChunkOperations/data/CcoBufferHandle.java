package com.stonebreak.world.chunk.api.commonChunkOperations.data;

import java.util.Objects;

/**
 * Immutable handle to a single OpenGL buffer resource for CCO chunks.
 * Represents either a VBO (Vertex Buffer Object) or EBO (Element Buffer Object).
 *
 * Thread-safe through immutability.
 * Designed for efficient buffer lifecycle management.
 */
public final class CcoBufferHandle {
    /**
     * Buffer types supported by CCO system
     */
    public enum BufferType {
        VBO,  // Vertex Buffer Object (GL_ARRAY_BUFFER)
        EBO   // Element Buffer Object (GL_ELEMENT_ARRAY_BUFFER)
    }

    /** Invalid handle constant */
    public static final CcoBufferHandle INVALID_HANDLE = new CcoBufferHandle(-1, BufferType.VBO);

    private final int bufferId;
    private final BufferType type;

    /**
     * Creates a buffer handle with OpenGL buffer ID and type.
     *
     * @param bufferId OpenGL buffer ID
     * @param type Buffer type (VBO or EBO)
     */
    private CcoBufferHandle(int bufferId, BufferType type) {
        this.bufferId = bufferId;
        this.type = type;
    }

    /**
     * Creates a VBO handle.
     *
     * @param bufferId OpenGL buffer ID
     * @return VBO handle
     */
    public static CcoBufferHandle createVbo(int bufferId) {
        return new CcoBufferHandle(bufferId, BufferType.VBO);
    }

    /**
     * Creates an EBO handle.
     *
     * @param bufferId OpenGL buffer ID
     * @return EBO handle
     */
    public static CcoBufferHandle createEbo(int bufferId) {
        return new CcoBufferHandle(bufferId, BufferType.EBO);
    }

    /**
     * Creates an empty/invalid handle (no resources allocated).
     */
    public static CcoBufferHandle empty() {
        return INVALID_HANDLE;
    }

    /**
     * Checks if this handle represents valid allocated resources.
     */
    public boolean isValid() {
        return bufferId > 0;
    }

    /**
     * Checks if this handle is empty (no resources).
     */
    public boolean isEmpty() {
        return !isValid();
    }

    /**
     * Gets the OpenGL buffer ID.
     */
    public int getBufferId() {
        return bufferId;
    }

    /**
     * Gets the buffer type (VBO or EBO).
     */
    public BufferType getType() {
        return type;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CcoBufferHandle that = (CcoBufferHandle) o;
        return bufferId == that.bufferId && type == that.type;
    }

    @Override
    public int hashCode() {
        return Objects.hash(bufferId, type);
    }

    @Override
    public String toString() {
        if (isEmpty()) {
            return "CcoBufferHandle{invalid}";
        }
        return String.format("CcoBufferHandle{id=%d, type=%s}", bufferId, type);
    }
}
