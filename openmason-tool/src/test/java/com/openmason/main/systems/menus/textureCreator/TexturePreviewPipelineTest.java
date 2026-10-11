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
 * only that rectangle of the composite, only while a face region is open.
 * The GPU upload is captured by a renderer subclass; no GL context is used.
 */
@Tag("integration")
class TexturePreviewPipelineTest {

    private static final int MATERIAL_ID = 4;
    private static final int TEXTURE_ID = 77;
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

    private void openFace() {
        controller.openFaceRegion(MATERIAL_ID, FaceTextureMapping.FULL_REGION, null, 800, 600);
    }

    private void paint(int x, int y) {
        controller.getActiveLayerCanvas().setPixel(x, y, RED);
        controller.notifyLayerModified();
    }
}
