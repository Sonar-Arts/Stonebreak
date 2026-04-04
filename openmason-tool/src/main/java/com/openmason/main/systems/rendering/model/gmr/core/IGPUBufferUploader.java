package com.openmason.main.systems.rendering.model.gmr.core;

/**
 * Functional interface for uploading vertex/index data to GPU buffers.
 * Abstracts OpenGL buffer operations so mesh subsystems can trigger
 * GPU uploads without depending on the renderer directly.
 */
public interface IGPUBufferUploader {

    /**
     * Upload interleaved vertex data to the VBO.
     *
     * @param interleavedData Interleaved vertex data (position + texcoord)
     */
    void uploadVBO(float[] interleavedData);

    /**
     * Upload index data to the EBO.
     *
     * @param indices Triangle indices
     */
    void uploadEBO(int[] indices);

    /**
     * Check if the GPU buffers are initialized and ready for uploads.
     *
     * @return true if initialized
     */
    boolean isGPUReady();
}
