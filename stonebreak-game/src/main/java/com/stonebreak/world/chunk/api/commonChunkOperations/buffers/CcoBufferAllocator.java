package com.stonebreak.world.chunk.api.commonChunkOperations.buffers;

import com.stonebreak.world.chunk.api.commonChunkOperations.data.CcoBufferHandle;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;

import java.util.logging.Logger;

/**
 * CCO Buffer Allocator - OpenGL buffer creation with error handling
 *
 * Responsibilities:
 * - Create VBO/EBO buffers with proper error checking
 * - Validate buffer creation success
 * - Provide type-safe buffer handle creation
 * - Log allocation failures for debugging
 *
 * Design: Simple factory pattern with validation
 * Performance: < 1ms per allocation (OpenGL driver dependent)
 */
public final class CcoBufferAllocator {
    private static final Logger LOGGER = Logger.getLogger(CcoBufferAllocator.class.getName());

    /**
     * Buffer types for allocation
     */
    public enum BufferType {
        VERTEX_BUFFER(GL15.GL_ARRAY_BUFFER),
        INDEX_BUFFER(GL15.GL_ELEMENT_ARRAY_BUFFER);

        private final int glTarget;

        BufferType(int glTarget) {
            this.glTarget = glTarget;
        }

        public int getGlTarget() {
            return glTarget;
        }
    }

    /**
     * Usage hints for OpenGL buffer optimization
     */
    public enum UsageHint {
        STATIC_DRAW(GL15.GL_STATIC_DRAW),   // Modified once, used many times
        DYNAMIC_DRAW(GL15.GL_DYNAMIC_DRAW), // Modified frequently
        STREAM_DRAW(GL15.GL_STREAM_DRAW);   // Modified every frame

        private final int glUsage;

        UsageHint(int glUsage) {
            this.glUsage = glUsage;
        }

        public int getGlUsage() {
            return glUsage;
        }
    }

    // Prevent instantiation
    private CcoBufferAllocator() {}

    /**
     * Allocate a new OpenGL buffer
     *
     * @param type Buffer type (VBO or EBO)
     * @param usage Usage hint for driver optimization
     * @return Valid buffer handle or INVALID_HANDLE on failure
     *
     * Thread-safety: GL context thread only
     * Performance: < 1ms (driver dependent)
     */
    public static CcoBufferHandle allocate(BufferType type, UsageHint usage) {
        try {
            // Generate buffer ID
            int bufferId = GL15.glGenBuffers();

            // Validate creation
            if (bufferId == 0) {
                LOGGER.severe("Failed to generate OpenGL buffer: glGenBuffers returned 0");
                return CcoBufferHandle.INVALID_HANDLE;
            }

            // Check for GL errors
            int error = GL30.glGetError();
            if (error != GL30.GL_NO_ERROR) {
                LOGGER.severe("OpenGL error during buffer allocation: 0x" + Integer.toHexString(error));

                // Clean up if buffer was created
                if (bufferId != 0) {
                    GL15.glDeleteBuffers(bufferId);
                }

                return CcoBufferHandle.INVALID_HANDLE;
            }

            // Create type-specific handle
            return switch (type) {
                case VERTEX_BUFFER -> CcoBufferHandle.createVbo(bufferId);
                case INDEX_BUFFER -> CcoBufferHandle.createEbo(bufferId);
            };

        } catch (Exception e) {
            LOGGER.severe("Exception during buffer allocation: " + e.getMessage());
            return CcoBufferHandle.INVALID_HANDLE;
        }
    }

    /**
     * Allocate vertex buffer with static draw usage (most common case)
     *
     * @return Valid VBO handle or INVALID_HANDLE on failure
     */
    public static CcoBufferHandle allocateVbo() {
        return allocate(BufferType.VERTEX_BUFFER, UsageHint.STATIC_DRAW);
    }

    /**
     * Allocate index buffer with static draw usage (most common case)
     *
     * @return Valid EBO handle or INVALID_HANDLE on failure
     */
    public static CcoBufferHandle allocateEbo() {
        return allocate(BufferType.INDEX_BUFFER, UsageHint.STATIC_DRAW);
    }

    /**
     * Allocate dynamic vertex buffer (frequently updated)
     *
     * @return Valid VBO handle or INVALID_HANDLE on failure
     */
    public static CcoBufferHandle allocateDynamicVbo() {
        return allocate(BufferType.VERTEX_BUFFER, UsageHint.DYNAMIC_DRAW);
    }

    /**
     * Free an OpenGL buffer
     *
     * @param handle Buffer handle to free
     *
     * Thread-safety: GL context thread only
     */
    public static void free(CcoBufferHandle handle) {
        if (handle == null || !handle.isValid()) {
            return;
        }

        try {
            GL15.glDeleteBuffers(handle.getBufferId());

            // Check for errors
            int error = GL30.glGetError();
            if (error != GL30.GL_NO_ERROR) {
                LOGGER.warning("OpenGL error during buffer deletion: 0x" + Integer.toHexString(error));
            }

        } catch (Exception e) {
            LOGGER.warning("Exception during buffer deletion: " + e.getMessage());
        }
    }

    /**
     * Check if a buffer ID is valid (non-zero and exists in GL)
     *
     * @param bufferId OpenGL buffer ID
     * @return true if buffer exists
     *
     * Note: This queries GL state, use sparingly
     */
    public static boolean isBufferValid(int bufferId) {
        if (bufferId == 0) {
            return false;
        }

        try {
            return GL15.glIsBuffer(bufferId);
        } catch (Exception e) {
            LOGGER.warning("Exception checking buffer validity: " + e.getMessage());
            return false;
        }
    }
}
