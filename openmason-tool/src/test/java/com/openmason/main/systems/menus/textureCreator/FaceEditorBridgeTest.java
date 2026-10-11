package com.openmason.main.systems.menus.textureCreator;

import com.openmason.engine.rendering.model.gmr.uv.FaceTextureManager;
import com.openmason.engine.rendering.model.gmr.uv.FaceTextureMapping;
import com.openmason.engine.rendering.model.gmr.uv.FaceTextureMapping.UVRegion;
import com.openmason.engine.rendering.model.gmr.uv.FaceTextureMapping.UVRotation;
import com.openmason.engine.rendering.model.gmr.uv.MaterialDefinition;
import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opening a model face in the texture editor: the face's UV region becomes
 * the paintable area (pixels outside are cleared and locked), missing data
 * refuses cleanly, and closing restores the full canvas.
 */
@Tag("integration")
class FaceEditorBridgeTest {

    private static final int RED = 0xFF0000FF;
    private static final float[] QUAD_X = {0f, 1f, 1f, 0f};
    private static final float[] QUAD_Y = {0f, 0f, 1f, 1f};

    private TextureCreatorController controller;
    private FaceEditorBridge bridge;
    private FaceTextureManager faces;

    @BeforeEach
    void setUp() {
        controller = new TextureCreatorController(new TextureCreatorState()); // 16x16
        bridge = new FaceEditorBridge(controller);
        faces = new FaceTextureManager();
        faces.registerMaterial(new MaterialDefinition(2, "Side", 11,
                MaterialDefinition.RenderLayer.OPAQUE, MaterialDefinition.MaterialProperties.NONE));
        // Face 5 maps to the top-left quarter (pixels 0..7 x 0..7) of material 2.
        faces.setFaceMapping(new FaceTextureMapping(5, 2, new UVRegion(0f, 0f, 0.5f, 0.5f), UVRotation.NONE));
    }

    @Test
    void openingAFaceLocksEverythingOutsideItsRegion() {
        controller.getActiveLayerCanvas().fill(RED);

        assertTrue(bridge.openFaceForEditing(faces, 5, QUAD_X, QUAD_Y, 800, 600));

        assertTrue(bridge.isFaceRegionActive());
        assertEquals(2, bridge.getActiveFaceRegionMaterialId());
        PixelCanvas canvas = controller.getActiveLayerCanvas();
        assertEquals(RED, canvas.getPixel(2, 2), "inside the face keeps its pixels");
        assertEquals(0, canvas.getPixel(12, 12), "outside the face is cleared");
        assertEquals(0, canvas.getPixel(12, 2));

        canvas.setPixel(12, 12, RED);
        assertEquals(0, canvas.getPixel(12, 12), "outside the face cannot be painted");
        canvas.setPixel(3, 3, 0xFF00FF00);
        assertEquals(0xFF00FF00, canvas.getPixel(3, 3));
    }

    @Test
    void closingRestoresTheFullCanvas() {
        bridge.openFaceForEditing(faces, 5, QUAD_X, QUAD_Y, 800, 600);

        bridge.closeFaceEditing();

        assertFalse(bridge.isFaceRegionActive());
        assertEquals(-1, bridge.getActiveFaceRegionMaterialId());
        assertNull(controller.getActiveLayerCanvas().getShapeMask());
        controller.getActiveLayerCanvas().setPixel(12, 12, RED);
        assertEquals(RED, controller.getActiveLayerCanvas().getPixel(12, 12));
    }

    @Test
    void unknownFaceOrMaterialIsRefusedWithoutOpeningAnything() {
        faces.setFaceMapping(new FaceTextureMapping(6, 99, FaceTextureMapping.FULL_REGION, UVRotation.NONE));

        assertFalse(bridge.openFaceForEditing(faces, 42, QUAD_X, QUAD_Y, 800, 600), "no mapping");
        assertFalse(bridge.openFaceForEditing(faces, 6, QUAD_X, QUAD_Y, 800, 600), "no material");
        assertFalse(bridge.openRectFaceForEditing(faces, 42, 800, 600));
        assertFalse(bridge.isFaceRegionActive());
    }

    @Test
    void rectangularFacesOpenWithoutAMask() {
        controller.getActiveLayerCanvas().fill(RED);

        assertTrue(bridge.openRectFaceForEditing(faces, 5, 800, 600));

        assertTrue(bridge.isFaceRegionActive());
        assertEquals(2, bridge.getActiveFaceRegionMaterialId());
        assertEquals(RED, controller.getActiveLayerCanvas().getPixel(12, 12), "nothing is cleared without a mask");
    }

    @Test
    void openingAnotherFaceReplacesTheStaleRegion() {
        faces.registerMaterial(new MaterialDefinition(3, "Top", 12,
                MaterialDefinition.RenderLayer.OPAQUE, MaterialDefinition.MaterialProperties.NONE));
        faces.setFaceMapping(new FaceTextureMapping(8, 3, new UVRegion(0.5f, 0.5f, 1f, 1f), UVRotation.NONE));

        bridge.openFaceForEditing(faces, 5, QUAD_X, QUAD_Y, 800, 600);
        assertTrue(bridge.openFaceForEditing(faces, 8, QUAD_X, QUAD_Y, 800, 600));

        assertEquals(3, bridge.getActiveFaceRegionMaterialId());
        PixelCanvas canvas = controller.getActiveLayerCanvas();
        canvas.setPixel(12, 12, RED);
        canvas.setPixel(2, 2, RED);
        assertEquals(RED, canvas.getPixel(12, 12), "the new face is paintable");
        assertEquals(0, canvas.getPixel(2, 2), "the previous face's mask does not linger");
    }

    @Test
    void preparedCanvasesMatchTheRequestedTexture() {
        bridge.prepareBlankCanvas(8, 4, 0xFF336699);
        assertEquals(8, controller.getLayerManager().getCanvasWidth());
        assertEquals(4, controller.getLayerManager().getCanvasHeight());
        assertEquals(0xFF336699, controller.getActiveLayerCanvas().getPixel(7, 3));

        byte[] rgba = new byte[2 * 1 * 4];
        rgba[0] = (byte) 0xFF; rgba[3] = (byte) 0xFF;             // red
        rgba[5] = (byte) 0x80; rgba[7] = (byte) 0x40;             // translucent green
        bridge.prepareCanvasFromPixels(2, 1, rgba);
        assertEquals(PixelCanvas.packRGBA(255, 0, 0, 255), controller.getActiveLayerCanvas().getPixel(0, 0));
        assertEquals(PixelCanvas.packRGBA(0, 0x80, 0, 0x40), controller.getActiveLayerCanvas().getPixel(1, 0));
    }
}
