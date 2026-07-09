package com.openmason.main.systems.scripting.live;

import com.openmason.engine.rendering.model.GenericModelRenderer;
import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.rendering.model.miscComponents.OMTTextureLoader;
import com.openmason.main.systems.scripting.doc.FacePixelStore;
import org.lwjgl.opengl.GL11;

/**
 * Live-viewport {@link FacePixelStore}: GPU texture readback/upload and
 * material assignment via the model renderer. GL-thread only (same contract
 * as {@code LiveModelDocument}).
 */
public final class LiveFacePixelStore implements FacePixelStore {

    private final GenericModelRenderer gmr;
    private final OMTTextureLoader textureLoader = new OMTTextureLoader();

    public LiveFacePixelStore(GenericModelRenderer gmr) {
        this.gmr = gmr;
    }

    @Override
    public int[] textureSize(int gpuTextureId) {
        return gmr.getTextureDimensions(gpuTextureId);
    }

    @Override
    public byte[] readPixels(int gpuTextureId) {
        return gmr.readTexturePixels(gpuTextureId);
    }

    @Override
    public void writeRegion(int gpuTextureId, int x, int y, int width, int height, byte[] rgbaBytes) {
        gmr.updateTextureRegion(gpuTextureId, x, y, width, height, rgbaBytes);
    }

    @Override
    public int createTexture(PixelCanvas canvas) {
        return textureLoader.uploadPixelCanvasToGPU(canvas);
    }

    @Override
    public int allocateMaterialId() {
        return gmr.allocateMaterialId();
    }

    @Override
    public void assignFaceMaterials(int[] globalFaceIds, int[] materialIds) {
        gmr.setFaceMaterials(globalFaceIds, materialIds);
    }

    @Override
    public void deleteTexture(int gpuTextureId) {
        if (gpuTextureId > 0) {
            GL11.glDeleteTextures(gpuTextureId);
        }
    }
}
