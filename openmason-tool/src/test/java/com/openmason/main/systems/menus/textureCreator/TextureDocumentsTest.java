package com.openmason.main.systems.menus.textureCreator;

import com.openmason.engine.rendering.model.gmr.uv.FaceTextureMapping;
import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.commands.DrawCommand;
import com.openmason.main.systems.menus.textureCreator.selection.RectangularSelection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The texture editor as a multi-document editor (#331): opening a texture adds or focuses a
 * document and never replaces another one, each document keeps its own layers, undo history,
 * dirty flag, view and selection, closing activates a neighbour (the unlisted placeholder once
 * none is left), and listeners hear every switch and close.
 */
@Tag("integration")
class TextureDocumentsTest {

    private static final int RED = 0xFF0000FF;

    @TempDir
    Path dir;

    private TextureCreatorState state;
    private TextureCreatorController controller;

    @BeforeEach
    void setUp() {
        state = new TextureCreatorState();
        controller = new TextureCreatorController(state);
    }

    @Test
    void theEditorStartsWithNoDocumentAndAPlaceholderStandsIn() {
        assertFalse(controller.hasOpenDocument());
        assertTrue(controller.getDocuments().isEmpty());
        assertTrue(controller.getActiveLayerCanvas() != null, "readers never see null");
        assertSame(controller.getActiveDocument(), state.getDocument());
    }

    @Test
    void newTexturesOpenAsTheirOwnActiveTabs() {
        TextureDocument first = controller.newTexture(new TextureCreatorState.CanvasSize(16, 16));
        TextureDocument second = controller.newTexture(new TextureCreatorState.CanvasSize(32, 8));

        assertEquals(List.of(first, second), controller.getDocuments());
        assertSame(second, controller.getActiveDocument());
        assertEquals("Untitled-1", first.title());
        assertEquals("Untitled-2", second.title());
        assertEquals(new TextureCreatorState.CanvasSize(32, 8), state.getCurrentCanvasSize(),
                "the state reads the active document");
        assertNotSame(first.id(), second.id());
        assertSame(first, controller.findDocument(first.id()));
    }

    @Test
    void eachDocumentKeepsItsOwnHistoryDirtyFlagViewAndSelection() {
        TextureDocument a = controller.newTexture(new TextureCreatorState.CanvasSize(16, 16));
        paint(1, 1);
        state.setCurrentSelection(new RectangularSelection(0, 0, 3, 3));
        controller.getCanvasState().zoomIn(2f);
        float zoomA = controller.getCanvasState().getZoomLevel();

        TextureDocument b = controller.newTexture(new TextureCreatorState.CanvasSize(16, 16));
        assertFalse(controller.getCommandHistory().canUndo(), "b starts with an empty history");
        assertFalse(state.hasUnsavedChanges());
        assertFalse(state.hasSelection(), "selections do not leak across documents");
        assertNotEquals(zoomA, controller.getCanvasState().getZoomLevel(), "b has its own view");

        controller.undo(); // nothing to undo in b
        assertEquals(RED, a.getLayerManager().getActiveLayer().getCanvas().getPixel(1, 1), "a untouched");

        controller.activate(a);
        assertTrue(state.hasUnsavedChanges());
        assertTrue(state.hasSelection());
        assertEquals(zoomA, controller.getCanvasState().getZoomLevel());
        controller.undo();
        assertEquals(0, controller.getActiveLayerCanvas().getPixel(1, 1));
        assertFalse(b.hasUnsavedChanges());
    }

    @Test
    void openingAFileAlreadyInATabFocusesItAndKeepsItsEdits() {
        controller.newTexture(new TextureCreatorState.CanvasSize(16, 16));
        Path file = dir.resolve("stone.omt");
        assertTrue(controller.saveProject(file.toString()));
        TextureDocument stone = controller.getActiveDocument();
        assertEquals("stone.omt", stone.title());
        paint(4, 4); // unsaved edit
        controller.newTexture(new TextureCreatorState.CanvasSize(8, 8));

        assertTrue(controller.loadProject(file.toString()));

        assertSame(stone, controller.getActiveDocument());
        assertEquals(2, controller.getDocuments().size(), "no second tab for the same file");
        assertEquals(RED, controller.getActiveLayerCanvas().getPixel(4, 4), "the file was not re-read");
        assertTrue(stone.hasUnsavedChanges());
    }

    @Test
    void loadingAProjectAddsATabAndAFailedLoadOpensNothing() {
        controller.newTexture(new TextureCreatorState.CanvasSize(16, 16));
        paint(2, 2);
        Path file = dir.resolve("a.omt");
        controller.saveProject(file.toString());
        controller.closeAll(TextureCreatorController.CloseReason.USER);

        TextureDocument loaded = controller.openProject(file.toString());
        assertSame(loaded, controller.getActiveDocument());
        assertTrue(loaded.isProjectFile());
        assertFalse(loaded.hasUnsavedChanges());
        assertEquals(RED, controller.getActiveLayerCanvas().getPixel(2, 2));

        assertNull(controller.openProject(dir.resolve("missing.omt").toString()));
        assertEquals(List.of(loaded), controller.getDocuments());
    }

    @Test
    void closingTheActiveTabActivatesItsNeighbourThenThePlaceholder() {
        TextureDocument a = controller.newTexture(new TextureCreatorState.CanvasSize(4, 4));
        TextureDocument b = controller.newTexture(new TextureCreatorState.CanvasSize(4, 4));
        TextureDocument c = controller.newTexture(new TextureCreatorState.CanvasSize(4, 4));
        controller.activate(b);

        controller.closeDocuments(List.of(b), TextureCreatorController.CloseReason.USER);
        assertSame(c, controller.getActiveDocument(), "the right-hand neighbour");
        controller.closeDocuments(List.of(c), TextureCreatorController.CloseReason.USER);
        assertSame(a, controller.getActiveDocument(), "else the left one");
        controller.closeDocuments(List.of(a), TextureCreatorController.CloseReason.USER);

        assertFalse(controller.hasOpenDocument());
        assertSame(controller.getActiveDocument(), state.getDocument());
        assertFalse(controller.getDocuments().contains(controller.getActiveDocument()));
    }

    @Test
    void listenersHearSwitchesAndOneClosedEventPerBatch() {
        List<String> events = new ArrayList<>();
        controller.setBeforeDocumentSwitch(() -> events.add("settle"));
        controller.addDocumentListener(new TextureCreatorController.DocumentListener() {
            @Override
            public void activated(TextureDocument document) {
                events.add("activated " + document.title());
            }

            @Override
            public void closing(TextureDocument document, TextureCreatorController.CloseReason reason) {
                events.add("closing " + document.title());
            }

            @Override
            public void closed(List<TextureDocument> documents, TextureCreatorController.CloseReason reason) {
                events.add("closed " + documents.size() + " " + reason);
            }
        });

        TextureDocument a = controller.newTexture(new TextureCreatorState.CanvasSize(4, 4));
        TextureDocument b = controller.newTexture(new TextureCreatorState.CanvasSize(4, 4));
        controller.activate(b); // already active: nothing
        controller.closeDocuments(List.of(a, b), TextureCreatorController.CloseReason.WORKSPACE_CLOSED);

        assertEquals(List.of(
                "settle", "activated Untitled-1",
                "settle", "activated Untitled-2",
                "settle", "closing Untitled-1", "closing Untitled-2", "activated No Texture",
                "closed 2 WORKSPACE_CLOSED"), events);
    }

    @Test
    void closingFaceDocumentsLeavesStandaloneTextures() {
        TextureDocument standalone = controller.newTexture(new TextureCreatorState.CanvasSize(16, 16));
        controller.prepareBlankCanvas(16, 16, RED);
        TextureDocument face = controller.openFaceRegion(3, 9, FaceTextureMapping.FULL_REGION, null, 800, 600);
        assertTrue(face.isFace());
        assertFalse(face.needsSavePrompt(), "a face document never asks to be saved");
        paint(1, 1);
        assertFalse(face.needsSavePrompt());

        controller.closeFaceDocuments(TextureCreatorController.CloseReason.MODEL_CHANGED);

        assertEquals(List.of(standalone), controller.getDocuments());
        assertSame(standalone, controller.getActiveDocument());
    }

    @Test
    void importingAPngOpensItAsANewUnsavedTab() throws Exception {
        TextureDocument kept = controller.newTexture(new TextureCreatorState.CanvasSize(16, 16));
        paint(0, 0);
        Path png = dir.resolve("leaf.png");
        assertTrue(controller.exportTexture(png.toString()));

        assertTrue(controller.importTexture(png.toString(), new TextureCreatorState.CanvasSize(16, 16)));

        assertEquals(2, controller.getDocuments().size());
        TextureDocument imported = controller.getActiveDocument();
        assertEquals("leaf.png", imported.title());
        assertFalse(imported.isProjectFile());
        assertTrue(imported.needsSavePrompt());
        assertEquals(RED, controller.getActiveLayerCanvas().getPixel(0, 0));
        assertEquals(RED, kept.getLayerManager().getActiveLayer().getCanvas().getPixel(0, 0), "the old tab stays");
    }

    @Test
    void savingADocumentInTheBackgroundOnlyTouchesThatDocument() {
        TextureDocument a = controller.newTexture(new TextureCreatorState.CanvasSize(16, 16));
        paint(5, 5);
        TextureDocument b = controller.newTexture(new TextureCreatorState.CanvasSize(16, 16));
        paint(6, 6);

        assertTrue(controller.saveDocument(a, dir.resolve("a.omt").toString()));

        assertFalse(a.hasUnsavedChanges());
        assertTrue(a.isProjectFile());
        assertTrue(b.hasUnsavedChanges(), "the active document is untouched");
        assertNull(b.getFilePath());
    }

    @Test
    void activatingAClosedDocumentIsRefused() {
        TextureDocument a = controller.newTexture(new TextureCreatorState.CanvasSize(4, 4));
        controller.closeAll(TextureCreatorController.CloseReason.USER);
        assertThrows(IllegalArgumentException.class, () -> controller.activate(a));
    }

    @Test
    void ensureDocumentOpensAnUntitledTextureOnlyWhenNoneIsOpen() {
        TextureDocument opened = controller.ensureDocument();
        assertEquals(List.of(opened), controller.getDocuments());
        assertSame(opened, controller.ensureDocument());
    }

    private void paint(int x, int y) {
        PixelCanvas canvas = controller.getActiveLayerCanvas();
        DrawCommand cmd = new DrawCommand(canvas, "Pencil");
        cmd.recordPixelChange(x, y, canvas.getPixel(x, y), RED);
        controller.getCommandHistory().executeCommand(cmd);
        controller.notifyLayerModified();
    }
}
