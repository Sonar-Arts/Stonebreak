package com.openmason.engine.rendering.model.gmr.uv;

import com.openmason.engine.rendering.model.gmr.core.MeshRebuildPipeline;
import org.lwjgl.BufferUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;

/**
 * Handles GPU texture I/O and material state.
 *
 * <p>UV generation is no longer a responsibility of this class: corner UVs
 * are projected during render-mesh derivation ({@code RenderMeshBuilder}),
 * and material seams cannot occur because render corners are per-face by
 * construction — the legacy seam-duplication machinery is gone.
 * {@link #regenerateUVsAndUpload()} simply re-derives the render mesh.
 */
public class TextureGPUOperations implements ITextureGPUOperations {

    private static final Logger logger = LoggerFactory.getLogger(TextureGPUOperations.class);

    private final FaceTextureManager faceTextureManager;
    private final MeshRebuildPipeline rebuildPipeline;

    // Texture state
    private int textureId = 0;
    private boolean useTexture = false;

    public TextureGPUOperations(
            FaceTextureManager faceTextureManager,
            MeshRebuildPipeline rebuildPipeline) {
        this.faceTextureManager = faceTextureManager;
        this.rebuildPipeline = rebuildPipeline;
    }

    @Override
    public void setTexture(int textureId) {
        this.textureId = textureId;
        this.useTexture = textureId > 0;
    }

    @Override
    public int getTextureId() {
        return textureId;
    }

    @Override
    public boolean isTextureActive() {
        return useTexture && textureId > 0;
    }

    @Override
    public void updateTextureRegion(int targetTextureId, int x, int y,
                                     int width, int height, byte[] rgbaBytes) {
        if (targetTextureId <= 0 || rgbaBytes == null || rgbaBytes.length == 0) {
            return;
        }

        ByteBuffer buffer = BufferUtils.createByteBuffer(rgbaBytes.length);
        buffer.put(rgbaBytes);
        buffer.flip();

        glBindTexture(GL_TEXTURE_2D, targetTextureId);
        glTexSubImage2D(GL_TEXTURE_2D, 0, x, y, width, height,
                        GL_RGBA, GL_UNSIGNED_BYTE, buffer);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    @Override
    public byte[] readTexturePixels(int gpuTextureId) {
        if (gpuTextureId <= 0) {
            return null;
        }

        glBindTexture(GL_TEXTURE_2D, gpuTextureId);
        int width = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH);
        int height = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT);

        if (width <= 0 || height <= 0) {
            glBindTexture(GL_TEXTURE_2D, 0);
            return null;
        }

        ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * 4);
        glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
        glBindTexture(GL_TEXTURE_2D, 0);

        byte[] pixels = new byte[width * height * 4];
        buffer.get(pixels);
        return pixels;
    }

    @Override
    public int[] getTextureDimensions(int gpuTextureId) {
        if (gpuTextureId <= 0) {
            return null;
        }

        glBindTexture(GL_TEXTURE_2D, gpuTextureId);
        int width = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH);
        int height = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT);
        glBindTexture(GL_TEXTURE_2D, 0);

        if (width <= 0 || height <= 0) {
            return null;
        }
        return new int[]{width, height};
    }

    @Override
    public void setFaceMaterial(int faceId, int materialId) {
        faceTextureManager.assignDefaultMapping(faceId, materialId);
        regenerateUVsAndUpload();
        rebuildPipeline.markDrawBatchesDirty();
    }

    @Override
    public boolean hasCustomMaterials() {
        for (FaceTextureMapping mapping : faceTextureManager.getAllMappings()) {
            if (mapping.materialId() != MaterialDefinition.DEFAULT.materialId()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void regenerateUVsAndUpload() {
        // Corner UVs are projected from face regions during derivation.
        rebuildPipeline.rebuildFromEditable();
    }
}
