package com.openmason.main.systems.menus.textureCreator;

import com.openmason.engine.rendering.model.gmr.uv.FaceTextureManager;
import com.openmason.engine.rendering.model.gmr.uv.FaceTextureMapping;
import com.openmason.engine.rendering.model.gmr.uv.FaceTextureMapping.UVRegion;
import com.openmason.engine.rendering.model.gmr.uv.FaceTextureMapping.UVRotation;
import com.openmason.engine.rendering.model.gmr.uv.MaterialDefinition;
import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.commands.DrawCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opening a model face in the texture editor: the face gets its own document tab whose UV
 * region is the paintable area (pixels outside are cleared and locked), missing data refuses
 * cleanly without opening anything, a face already open is focused rather than reloaded, and
 * closing a face tab leaves every other texture alone.
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
        controller = new TextureCreatorController(new TextureCreatorState());
        bridge = new FaceEditorBridge(controller);
        faces = new FaceTextureManager();
        faces.registerMaterial(new MaterialDefinition(2, "Side", 11,
                MaterialDefinition.RenderLayer.OPAQUE, MaterialDefinition.MaterialProperties.NONE));
        faces.registerMaterial(new MaterialDefinition(3, "Top", 12,
                MaterialDefinition.RenderLayer.OPAQUE, MaterialDefinition.MaterialProperties.NONE));
        // Face 5 maps to the top-left quarter (pixels 0..7 x 0..7) of material 2.
        faces.setFaceMapping(new FaceTextureMapping(5, 2, new UVRegion(0f, 0f, 0.5f, 0.5f), UVRotation.NONE));
        // Face 8 maps to the bottom-right quarter of material 3.
        faces.setFaceMapping(new FaceTextureMapping(8, 3, new UVRegion(0.5f, 0.5f, 1f, 1f), UVRotation.NONE));
    }

    @Test
    void openingAFaceLocksEverythingOutsideItsRegion() {
        bridge.prepareBlankCanvas(16, 16, RED);

        assertTrue(bridge.openFaceForEditing(faces, 5, QUAD_X, QUAD_Y, 800, 600));

        assertTrue(bridge.isFaceRegionActive());
        assertEquals(2, bridge.getActiveFaceRegionMaterialId());
        assertEquals("Face 5", controller.getActiveDocument().title());
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
    void closingAFaceTabLeavesTheOtherTexturesOpen() {
        TextureDocument standalone = controller.newTexture(new TextureCreatorState.CanvasSize(16, 16));
        standalone.getLayerManager().getActiveLayer().getCanvas().setPixel(12, 12, RED);
        bridge.prepareBlankCanvas(16, 16, RED);
        bridge.openFaceForEditing(faces, 5, QUAD_X, QUAD_Y, 800, 600);
        assertEquals(2, controller.getDocuments().size());

        bridge.closeFaceEditing();

        assertFalse(bridge.isFaceRegionActive());
        assertEquals(-1, bridge.getActiveFaceRegionMaterialId());
        assertEquals(List.of(standalone), controller.getDocuments());
        assertSame(standalone, controller.getActiveDocument());
        assertEquals(RED, controller.getActiveLayerCanvas().getPixel(12, 12), "the standalone texture is untouched");
    }

    @Test
    void unknownFaceOrMaterialIsRefusedWithoutOpeningAnything() {
        faces.setFaceMapping(new FaceTextureMapping(6, 99, FaceTextureMapping.FULL_REGION, UVRotation.NONE));
        bridge.prepareBlankCanvas(16, 16, RED);

        assertFalse(bridge.openFaceForEditing(faces, 42, QUAD_X, QUAD_Y, 800, 600), "no mapping");
        assertFalse(bridge.openFaceForEditing(faces, 6, QUAD_X, QUAD_Y, 800, 600), "no material");
        assertFalse(bridge.openRectFaceForEditing(faces, 42, 800, 600));
        assertFalse(bridge.isFaceRegionActive());
        assertFalse(controller.hasOpenDocument());

        assertTrue(bridge.openRectFaceForEditing(faces, 5, 800, 600));
        assertEquals(0, controller.getActiveLayerCanvas().getPixel(1, 1),
                "a refused open drops its prepared canvas; it never leaks into the next face");
    }

    @Test
    void rectangularFacesOpenWithoutAMask() {
        bridge.prepareBlankCanvas(16, 16, RED);

        assertTrue(bridge.openRectFaceForEditing(faces, 5, 800, 600));

        assertTrue(bridge.isFaceRegionActive());
        assertEquals(2, bridge.getActiveFaceRegionMaterialId());
        assertEquals(RED, controller.getActiveLayerCanvas().getPixel(12, 12), "nothing is cleared without a mask");
    }

    @Test
    void anotherFaceOpensInItsOwnTabWithItsOwnMask() {
        bridge.prepareBlankCanvas(16, 16, RED);
        bridge.openFaceForEditing(faces, 5, QUAD_X, QUAD_Y, 800, 600);
        TextureDocument face5 = controller.getActiveDocument();
        bridge.prepareBlankCanvas(16, 16, RED);
        assertTrue(bridge.openFaceForEditing(faces, 8, QUAD_X, QUAD_Y, 800, 600));

        assertEquals(2, controller.getDocuments().size());
        assertEquals(3, bridge.getActiveFaceRegionMaterialId());
        PixelCanvas canvas = controller.getActiveLayerCanvas();
        canvas.setPixel(12, 12, 0xFF00FF00);
        canvas.setPixel(2, 2, 0xFF00FF00);
        assertEquals(0xFF00FF00, canvas.getPixel(12, 12), "the new face is paintable");
        assertEquals(0, canvas.getPixel(2, 2), "the other face's region is locked here");

        PixelCanvas face5Canvas = face5.getLayerManager().getActiveLayer().getCanvas();
        assertEquals(RED, face5Canvas.getPixel(2, 2), "face 5 keeps its own pixels and mask");
        assertEquals(0, face5Canvas.getPixel(12, 12));
    }

    @Test
    void anOpenFaceIsFocusedNotReloaded() {
        bridge.prepareBlankCanvas(16, 16, RED);
        bridge.openFaceForEditing(faces, 5, QUAD_X, QUAD_Y, 800, 600);
        TextureDocument face5 = controller.getActiveDocument();
        face5.getCommandHistory().executeCommand(stroke(controller.getActiveLayerCanvas(), 1, 1));
        controller.newTexture(new TextureCreatorState.CanvasSize(8, 8));

        assertTrue(bridge.focusOpenFaceDocument(5, 2));
        assertSame(face5, controller.getActiveDocument());
        assertTrue(face5.getCommandHistory().canUndo(), "its history survives");
        assertFalse(bridge.focusOpenFaceDocument(5, 3), "the face moved to another material: reopen it");
        assertFalse(bridge.focusOpenFaceDocument(8, 3), "not open");
    }

    @Test
    void reopeningAnOpenFaceWithNewPixelsReplacesThatTabsCanvas() {
        bridge.prepareBlankCanvas(16, 16, RED);
        bridge.openFaceForEditing(faces, 5, QUAD_X, QUAD_Y, 800, 600);
        TextureDocument face5 = controller.getActiveDocument();
        face5.getCommandHistory().executeCommand(stroke(controller.getActiveLayerCanvas(), 1, 1));

        bridge.prepareBlankCanvas(32, 32, 0xFF00FF00); // the resize dialog's path
        assertTrue(bridge.openFaceForEditing(faces, 5, QUAD_X, QUAD_Y, 800, 600));

        assertEquals(1, controller.getDocuments().size(), "still one tab for the face");
        assertSame(face5, controller.getActiveDocument());
        assertEquals(32, face5.getLayerManager().getCanvasWidth());
        assertEquals(0xFF00FF00, controller.getActiveLayerCanvas().getPixel(3, 3));
        assertEquals(0, controller.getActiveLayerCanvas().getPixel(30, 30), "masked to the face at the new size");
        assertFalse(face5.getCommandHistory().canUndo(), "the old history does not apply to the new canvas");
    }

    @Test
    void preparedCanvasesMatchTheRequestedTexture() {
        bridge.prepareBlankCanvas(8, 4, 0xFF336699);
        bridge.openRectFaceForEditing(faces, 5, 800, 600);
        assertEquals(8, controller.getLayerManager().getCanvasWidth());
        assertEquals(4, controller.getLayerManager().getCanvasHeight());
        assertEquals(0xFF336699, controller.getActiveLayerCanvas().getPixel(7, 3));

        byte[] rgba = new byte[2 * 1 * 4];
        rgba[0] = (byte) 0xFF; rgba[3] = (byte) 0xFF;             // red
        rgba[5] = (byte) 0x80; rgba[7] = (byte) 0x40;             // translucent green
        bridge.prepareCanvasFromPixels(2, 1, rgba);
        bridge.openRectFaceForEditing(faces, 8, 800, 600);
        assertEquals(PixelCanvas.packRGBA(255, 0, 0, 255), controller.getActiveLayerCanvas().getPixel(0, 0));
        assertEquals(PixelCanvas.packRGBA(0, 0x80, 0, 0x40), controller.getActiveLayerCanvas().getPixel(1, 0));
    }

    private static DrawCommand stroke(PixelCanvas canvas, int x, int y) {
        DrawCommand cmd = new DrawCommand(canvas, "Pencil");
        cmd.recordPixelChange(x, y, canvas.getPixel(x, y), 0xFFFFFFFF);
        return cmd;
    }
}
