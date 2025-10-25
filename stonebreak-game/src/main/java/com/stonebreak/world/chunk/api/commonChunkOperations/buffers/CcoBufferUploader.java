package com.stonebreak.world.chunk.api.commonChunkOperations.buffers;

import com.stonebreak.world.chunk.api.commonChunkOperations.data.CcoBufferHandle;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.logging.Logger;

/**
 * CCO Buffer Uploader - Optimized OpenGL buffer uploads
 *
 * Responsibilities:
 * - Upload mesh data to GPU with optimal strategies
 * - Implement buffer orphaning to avoid stalls
 * - Support partial updates for dynamic data
 * - Validate buffer states before upload
 *
 * Design: Strategy pattern with upload optimization techniques
 * Performance: < 1ms for typical chunk mesh (driver dependent)
 *
 * Upload Strategies:
 * 1. Static Upload - glBufferData with STATIC_DRAW (most chunks)
 * 2. Orphan Upload - Invalidate + reupload to avoid sync (dynamic chunks)
 * 3. Partial Update - glBufferSubData for small changes
 */
public final class CcoBufferUploader {
    private static final Logger LOGGER = Logger.getLogger(CcoBufferUploader.class.getName());

    /**
     * Upload strategies for different use cases
     */
    public enum UploadStrategy {
        /**
         * Static upload - Buffer modified once, used many times
         * Best for: Most chunk meshes that rarely change
         * Uses: glBufferData with GL_STATIC_DRAW
         */
        STATIC,

        /**
         * Orphan upload - Invalidate old buffer, upload new data
         * Best for: Frequently updated chunks to avoid pipeline stalls
         * Uses: glBufferData with null, then glBufferData with data
         */
        ORPHAN,

        /**
         * Partial update - Update subset of existing buffer
         * Best for: Small changes to existing mesh
         * Uses: glBufferSubData
         */
        PARTIAL
    }

    // Prevent instantiation
    private CcoBufferUploader() {}

    /**
     * Upload FloatBuffer to VBO (static strategy)
     *
     * @param handle VBO handle
     * @param data Float data (vertex positions, normals, etc.)
     * @return true if upload succeeded
     *
     * Thread-safety: GL context thread only
     * Performance: < 1ms for ~8KB buffer
     */
    public static boolean uploadFloatData(CcoBufferHandle handle, FloatBuffer data) {
        return uploadFloatData(handle, data, UploadStrategy.STATIC);
    }

    /**
     * Upload FloatBuffer to VBO with specific strategy
     *
     * @param handle VBO handle
     * @param data Float data
     * @param strategy Upload strategy
     * @return true if upload succeeded
     */
    public static boolean uploadFloatData(CcoBufferHandle handle, FloatBuffer data, UploadStrategy strategy) {
        if (!validateUpload(handle, data, CcoBufferHandle.BufferType.VBO)) {
            return false;
        }

        try {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, handle.getBufferId());

            switch (strategy) {
                case STATIC -> {
                    // Simple static upload
                    GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);
                }
                case ORPHAN -> {
                    // Orphan old buffer, then upload new data
                    long byteSize = (long) data.remaining() * Float.BYTES;
                    GL15.glBufferData(GL15.GL_ARRAY_BUFFER, byteSize, GL15.GL_DYNAMIC_DRAW);
                    GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_DYNAMIC_DRAW);
                }
                case PARTIAL -> {
                    // Partial update (assumes buffer already allocated)
                    GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, data);
                }
            }

            // Unbind and check errors
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

            int error = GL30.glGetError();
            if (error != GL30.GL_NO_ERROR) {
                LOGGER.severe("OpenGL error during float upload: 0x" + Integer.toHexString(error));
                return false;
            }

            return true;

        } catch (Exception e) {
            LOGGER.severe("Exception during float upload: " + e.getMessage());
            return false;
        }
    }

    /**
     * Upload IntBuffer to EBO (static strategy)
     *
     * @param handle EBO handle
     * @param data Index data
     * @return true if upload succeeded
     *
     * Thread-safety: GL context thread only
     * Performance: < 500μs for ~4KB buffer
     */
    public static boolean uploadIntData(CcoBufferHandle handle, IntBuffer data) {
        return uploadIntData(handle, data, UploadStrategy.STATIC);
    }

    /**
     * Upload IntBuffer to EBO with specific strategy
     *
     * @param handle EBO handle
     * @param data Index data
     * @param strategy Upload strategy
     * @return true if upload succeeded
     */
    public static boolean uploadIntData(CcoBufferHandle handle, IntBuffer data, UploadStrategy strategy) {
        if (!validateUpload(handle, data, CcoBufferHandle.BufferType.EBO)) {
            return false;
        }

        try {
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, handle.getBufferId());

            switch (strategy) {
                case STATIC -> {
                    // Simple static upload
                    GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);
                }
                case ORPHAN -> {
                    // Orphan old buffer, then upload new data
                    long byteSize = (long) data.remaining() * Integer.BYTES;
                    GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, byteSize, GL15.GL_DYNAMIC_DRAW);
                    GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, data, GL15.GL_DYNAMIC_DRAW);
                }
                case PARTIAL -> {
                    // Partial update
                    GL15.glBufferSubData(GL15.GL_ELEMENT_ARRAY_BUFFER, 0, data);
                }
            }

            // Unbind and check errors
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);

            int error = GL30.glGetError();
            if (error != GL30.GL_NO_ERROR) {
                LOGGER.severe("OpenGL error during int upload: 0x" + Integer.toHexString(error));
                return false;
            }

            return true;

        } catch (Exception e) {
            LOGGER.severe("Exception during int upload: " + e.getMessage());
            return false;
        }
    }

    /**
     * Upload ByteBuffer to buffer (static strategy)
     *
     * @param handle Buffer handle
     * @param data Byte data
     * @return true if upload succeeded
     *
     * Thread-safety: GL context thread only
     */
    public static boolean uploadByteData(CcoBufferHandle handle, ByteBuffer data) {
        return uploadByteData(handle, data, UploadStrategy.STATIC);
    }

    /**
     * Upload ByteBuffer to buffer with specific strategy
     *
     * @param handle Buffer handle
     * @param data Byte data
     * @param strategy Upload strategy
     * @return true if upload succeeded
     */
    public static boolean uploadByteData(CcoBufferHandle handle, ByteBuffer data, UploadStrategy strategy) {
        if (!validateUpload(handle, data, null)) {
            return false;
        }

        try {
            int target = handle.getType() == CcoBufferHandle.BufferType.VBO ?
                GL15.GL_ARRAY_BUFFER : GL15.GL_ELEMENT_ARRAY_BUFFER;

            GL15.glBindBuffer(target, handle.getBufferId());

            switch (strategy) {
                case STATIC -> {
                    GL15.glBufferData(target, data, GL15.GL_STATIC_DRAW);
                }
                case ORPHAN -> {
                    long byteSize = data.remaining();
                    GL15.glBufferData(target, byteSize, GL15.GL_DYNAMIC_DRAW);
                    GL15.glBufferData(target, data, GL15.GL_DYNAMIC_DRAW);
                }
                case PARTIAL -> {
                    GL15.glBufferSubData(target, 0, data);
                }
            }

            GL15.glBindBuffer(target, 0);

            int error = GL30.glGetError();
            if (error != GL30.GL_NO_ERROR) {
                LOGGER.severe("OpenGL error during byte upload: 0x" + Integer.toHexString(error));
                return false;
            }

            return true;

        } catch (Exception e) {
            LOGGER.severe("Exception during byte upload: " + e.getMessage());
            return false;
        }
    }

    /**
     * Validate upload preconditions
     *
     * @param handle Buffer handle
     * @param data Buffer data
     * @param expectedType Expected buffer type (null to skip check)
     * @return true if valid for upload
     */
    private static boolean validateUpload(CcoBufferHandle handle, java.nio.Buffer data,
                                          CcoBufferHandle.BufferType expectedType) {
        // Check handle
        if (handle == null || !handle.isValid()) {
            LOGGER.warning("Invalid buffer handle for upload");
            return false;
        }

        // Check type if specified
        if (expectedType != null && handle.getType() != expectedType) {
            LOGGER.warning("Buffer type mismatch: expected " + expectedType +
                          ", got " + handle.getType());
            return false;
        }

        // Check data
        if (data == null || data.remaining() == 0) {
            LOGGER.warning("Invalid data for upload: " + (data == null ? "null" : "empty"));
            return false;
        }

        return true;
    }

    /**
     * Orphan buffer without uploading new data (invalidate only)
     *
     * Useful for clearing buffer before updating or deleting
     *
     * @param handle Buffer handle
     * @return true if orphan succeeded
     */
    public static boolean orphanBuffer(CcoBufferHandle handle) {
        if (handle == null || !handle.isValid()) {
            return false;
        }

        try {
            int target = handle.getType() == CcoBufferHandle.BufferType.VBO ?
                GL15.GL_ARRAY_BUFFER : GL15.GL_ELEMENT_ARRAY_BUFFER;

            GL15.glBindBuffer(target, handle.getBufferId());
            GL15.glBufferData(target, 0, GL15.GL_DYNAMIC_DRAW);
            GL15.glBindBuffer(target, 0);

            int error = GL30.glGetError();
            if (error != GL30.GL_NO_ERROR) {
                LOGGER.warning("OpenGL error during buffer orphan: 0x" + Integer.toHexString(error));
                return false;
            }

            return true;

        } catch (Exception e) {
            LOGGER.warning("Exception during buffer orphan: " + e.getMessage());
            return false;
        }
    }

    /**
     * Get estimated upload time for buffer size
     *
     * @param byteSize Size in bytes
     * @return Estimated time in microseconds
     *
     * Note: Very rough estimate, actual time is driver/hardware dependent
     */
    public static long estimateUploadTime(long byteSize) {
        // Rough estimate: ~1GB/s upload speed
        // = 1 byte/ns = 1000 bytes/μs
        return byteSize / 1000;
    }
}
