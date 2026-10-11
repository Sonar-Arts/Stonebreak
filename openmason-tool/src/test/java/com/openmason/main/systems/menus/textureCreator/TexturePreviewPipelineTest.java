package com.openmason.main.systems.menus.textureCreator;

import com.openmason.engine.rendering.model.GenericModelRenderer;
import com.openmason.engine.rendering.model.gmr.uv.FaceTextureMapping;
import com.openmason.engine.rendering.model.gmr.uv.MaterialDefinition;
import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live 3D preview of texture edits: canvas changes within a frame coalesce
 * into one dirty rectangle, and {@link TexturePreviewPipeline#flush()} uploads
 * only that rectangle of the composite, only while a face document is active,
 * and always into the texture of the face the edits were made on (tab switches
 * and closes flush first; a model change drops them).
 * The GPU upload is captured by a renderer subclass; no GL context is used.
 */
@Tag("integration")
class TexturePreviewPipelineTest {

    private static final int MATERIAL_ID = 4;
    private static final int TEXTURE_ID = 77;
    private static final int OTHER_MATERIAL_ID = 5;
    private static final int OTHER_TEXTURE_ID = 88;
    private static final int RED = 0xFF0000FF;

    /** Records texture uploads instead of calling glTexSubImage2D. */
    private static final class RecordingRenderer extends GenericModelRenderer {
        record Upload(int textureId, int x, int y, int width, int height, byte[] rgba) { }

        final List<Upload> uploads = new ArrayList<>();

        @Override
        public void updateTextureRegion(int targetTextureId, int x, int y, int width, int height, byte[] rgbaBytes) {
            uploads.add(new Upload(targetTextureId, x, y, width, height, rgbaBytes));
        }
    }

    private TextureCreatorController controller;
    private RecordingRenderer renderer;
    private TexturePreviewPipeline pipeline;

    @BeforeEach
    void setUp() {
        controller = new TextureCreatorController(new TextureCreatorState()); // 16x16
        renderer = new RecordingRenderer();
        renderer.getFaceTextureManager().registerMaterial(new MaterialDefinition(
                MATERIAL_ID, "Face", TEXTURE_ID, MaterialDefinition.RenderLayer.OPAQUE,
                MaterialDefinition.MaterialProperties.NONE));
        renderer.getFaceTextureManager().registerMaterial(new MaterialDefinition(
                OTHER_MATERIAL_ID, "Other face", OTHER_TEXTURE_ID, MaterialDefinition.RenderLayer.OPAQUE,
                MaterialDefinition.MaterialProperties.NONE));
        controller.newTexture(new TextureCreatorState.CanvasSize(16, 16)); // a standalone tab
        pipeline = new TexturePreviewPipeline(controller, renderer);
        pipeline.flush(); // attaches the listener to the active layer
    }

    @Test
    void standaloneEditingNeverPushesToTheModel() {
        paint(3, 3);
        pipeline.flush();

        assertTrue(renderer.uploads.isEmpty());
    }

    @Test
    void openingAFaceUploadsTheWholeCanvasOnce() {
        openFace();
        pipeline.flush();
        pipeline.flush();

        assertEquals(1, renderer.uploads.size());
        RecordingRenderer.Upload upload = renderer.uploads.get(0);
        assertEquals(TEXTURE_ID, upload.textureId());
        assertEquals(0, upload.x());
        assertEquals(0, upload.y());
        assertEquals(16, upload.width());
        assertEquals(16, upload.height());
    }

    @Test
    void editsInOneFrameCoalesceIntoOneDirtyRectangle() {
        openFace();
        pipeline.flush();
        renderer.uploads.clear();

        paint(3, 4);
        paint(10, 2);
        paint(5, 6);
        pipeline.flush();

        assertEquals(1, renderer.uploads.size());
        RecordingRenderer.Upload upload = renderer.uploads.get(0);
        assertEquals(3, upload.x());
        assertEquals(2, upload.y());
        assertEquals(8, upload.width());   // x 3..10
        assertEquals(5, upload.height());  // y 2..6
        PixelCanvas composite = controller.getCompositedCanvas();
        assertArrayEquals(composite.getPixelsAsRGBABytes(3, 2, 8, 5), upload.rgba());
    }

    @Test
    void aDisabledPipelineDropsChanges() {
        openFace();
        pipeline.flush();
        renderer.uploads.clear();

        pipeline.setEnabled(false);
        paint(1, 1);
        pipeline.flush();
        pipeline.setEnabled(true);
        pipeline.flush();

        assertTrue(renderer.uploads.isEmpty());
    }

    @Test
    void closingTheFaceStopsUploads() {
        openFace();
        pipeline.flush();
        renderer.uploads.clear();

        controller.closeFaceRegion();
        paint(2, 2);
        pipeline.flush();

        assertTrue(renderer.uploads.isEmpty());
    }

    @Test
    void theListenerFollowsTheActiveLayer() {
        openFace();
        pipeline.flush();
        renderer.uploads.clear();

        controller.getLayerManager().addLayer("Top"); // becomes the active layer
        pipeline.flush();                              // re-attaches to the new canvas
        controller.getActiveLayerCanvas().setPixel(7, 7, RED);
        controller.notifyLayerModified();
        controller.getLayerManager().getLayer(0).getCanvas().setPixel(0, 0, RED); // old layer: ignored
        pipeline.flush();

        assertEquals(1, renderer.uploads.size());
        assertEquals(7, renderer.uploads.get(0).x());
        assertEquals(1, renderer.uploads.get(0).width());
    }

    @Test
    void disposeDetachesFromTheCanvas() {
        openFace();
        pipeline.flush();
        renderer.uploads.clear();

        pipeline.dispose();
        paint(4, 4); // nothing is listening while detached
        pipeline.flush(); // re-attaches, but has no dirty region to upload

        assertTrue(renderer.uploads.isEmpty());
    }

    @Test
    void switchingTabsUploadsPendingEditsIntoTheirOwnFace() {
        TextureDocument first = openFace();
        pipeline.flush();
        TextureDocument second = openFace(7, OTHER_MATERIAL_ID);
        pipeline.flush();
        controller.activate(first);
        renderer.uploads.clear();

        paint(3, 3);
        controller.activate(second); // no flush in between: the switch must not lose or misroute it
        paint(9, 9);
        pipeline.flush();

        assertEquals(2, renderer.uploads.size());
        assertEquals(TEXTURE_ID, renderer.uploads.get(0).textureId());
        assertEquals(3, renderer.uploads.get(0).x());
        assertEquals(OTHER_TEXTURE_ID, renderer.uploads.get(1).textureId());
        assertEquals(9, renderer.uploads.get(1).x());
    }

    @Test
    void aStandaloneTabNeverReceivesAFacesPendingEdits() {
        TextureDocument face = openFace();
        pipeline.flush();
        renderer.uploads.clear();

        paint(2, 5);
        controller.activate(controller.getDocuments().get(0)); // the standalone tab
        pipeline.flush();

        assertEquals(1, renderer.uploads.size(), "the face's edit still reaches the face");
        assertEquals(TEXTURE_ID, renderer.uploads.get(0).textureId());
        paint(1, 1);
        pipeline.flush();
        assertEquals(1, renderer.uploads.size(), "standalone painting never reaches the model");
        assertTrue(controller.getDocuments().contains(face));
    }

    @Test
    void closingAFaceTabFlushesItsLastEdits() {
        openFace();
        pipeline.flush();
        renderer.uploads.clear();

        paint(6, 6);
        controller.closeFaceRegion();

        assertEquals(1, renderer.uploads.size());
        assertEquals(TEXTURE_ID, renderer.uploads.get(0).textureId());
    }

    @Test
    void aModelChangeDropsPendingEditsInsteadOfWritingIntoTheNewModel() {
        openFace();
        pipeline.flush();
        renderer.uploads.clear();

        paint(6, 6);
        controller.closeFaceDocuments(TextureCreatorController.CloseReason.MODEL_CHANGED);
        pipeline.flush();

        assertTrue(renderer.uploads.isEmpty());
    }

    private TextureDocument openFace() {
        return openFace(-1, MATERIAL_ID);
    }

    private TextureDocument openFace(int faceId, int materialId) {
        controller.prepareBlankCanvas(16, 16, 0xFF808080);
        return controller.openFaceRegion(faceId, materialId, FaceTextureMapping.FULL_REGION, null, 800, 600);
    }

    private void paint(int x, int y) {
        controller.getActiveLayerCanvas().setPixel(x, y, RED);
        controller.notifyLayerModified();
    }
}
