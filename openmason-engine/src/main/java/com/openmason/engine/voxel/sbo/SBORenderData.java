package com.openmason.engine.voxel.sbo;

import com.openmason.engine.voxel.mms.mmsCore.MmsRenderableHandle;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;

/**
 * Rendering data for SBO blocks in a chunk.
 *
 * <p>Holds a single GPU mesh (VAO) with all SBO triangles sorted by face,
 * plus per-face index ranges and GPU texture IDs. During rendering, iterates
 * faces, binds each face's texture, and draws the corresponding index range.
 */
public class SBORenderData {

    private final MmsRenderableHandle handle;
    private final FaceBatch[] batches;

    /**
     * A batch of triangles sharing the same texture.
     *
     * @param textureId   GPU texture ID for this face
     * @param indexOffset start index in the index buffer
     * @param indexCount  number of indices to draw
     */
    public record FaceBatch(int textureId, int indexOffset, int indexCount) {}

    public SBORenderData(MmsRenderableHandle handle, FaceBatch[] batches) {
        this.handle = handle;
        this.batches = batches;
    }

    /**
     * Render all SBO faces, binding each face's texture before drawing.
     */
    public void render() {
        if (handle == null || batches == null || batches.length == 0) {
            return;
        }

        handle.bind();
        glActiveTexture(GL_TEXTURE0);

        for (FaceBatch batch : batches) {
            if (batch.indexCount() > 0 && batch.textureId() > 0) {
                glBindTexture(GL_TEXTURE_2D, batch.textureId());
                handle.renderRange(batch.indexOffset(), batch.indexCount());
            }
        }

        MmsRenderableHandle.unbind();
    }

    /**
     * Clean up GPU resources.
     */
    public void close() {
        if (handle != null) {
            handle.close();
        }
        // Texture cleanup is handled by whoever created them (SBOMeshProcessor)
    }

    public MmsRenderableHandle getHandle() {
        return handle;
    }
}
