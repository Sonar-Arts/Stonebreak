package com.openmason.main.systems.menus.textureCreator;

import com.openmason.main.systems.keybinds.KeybindAction;
import com.openmason.main.systems.keybinds.KeybindRegistry;
import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.commands.DrawCommand;
import com.openmason.main.systems.menus.textureCreator.coordinators.FileOperationsCoordinator;
import com.openmason.main.systems.menus.textureCreator.coordinators.PasteCoordinator;
import com.openmason.main.systems.menus.textureCreator.coordinators.ToolCoordinator;
import com.openmason.main.systems.menus.textureCreator.dialogs.NewTextureDialog;
import com.openmason.main.systems.menus.textureCreator.imports.ImportStrategyResolver;
import com.openmason.main.systems.menus.textureCreator.selection.RectangularSelection;
import com.openmason.main.systems.viewport.ViewportActions;
import com.openmason.main.systems.viewport.ViewportKeybindActions;
import com.openmason.main.systems.viewport.ViewportUIState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Texture editor keybinds: every action is registered once under the "texture"
 * context with its documented default chord, no two share a chord, and the
 * bound actions reach the editor state they claim to drive. Collaborators are
 * the real headless classes (no window, no ImGui frame).
 */
@Tag("integration")
class TextureEditorKeybindActionsTest {

    private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();
    static {
        DEFAULTS.put("texture.new_texture", "Ctrl+N");
        DEFAULTS.put("texture.open_project", "Ctrl+O");
        DEFAULTS.put("texture.save_project", "Ctrl+S");
        DEFAULTS.put("texture.toggle_preferences", "Ctrl+,");
        DEFAULTS.put("texture.undo", "Ctrl+Z");
        DEFAULTS.put("texture.redo", "Ctrl+Y");
        DEFAULTS.put("texture.copy", "Ctrl+C");
        DEFAULTS.put("texture.cut", "Ctrl+X");
        DEFAULTS.put("texture.paste", "Ctrl+V");
        DEFAULTS.put("texture.delete_selection", "Del");
        DEFAULTS.put("texture.delete_selection_backspace", "Backspace");
        DEFAULTS.put("texture.deselect", "Ctrl+D");
        DEFAULTS.put("texture.toggle_grid", "Ctrl+Shift+G");
        DEFAULTS.put("texture.zoom_in", "=");
        DEFAULTS.put("texture.zoom_in_numpad", "Numpad +");
        DEFAULTS.put("texture.zoom_out", "-");
        DEFAULTS.put("texture.zoom_out_numpad", "Numpad -");
        DEFAULTS.put("texture.reset_view", "0");
        DEFAULTS.put("texture.reset_view_numpad", "Numpad 0");
        DEFAULTS.put("texture.confirm", "Enter");
        DEFAULTS.put("texture.confirm_numpad", "Numpad Enter");
        DEFAULTS.put("texture.cancel", "Esc");
    }

    @TempDir
    Path dir;

    private final KeybindRegistry registry = KeybindRegistry.getInstance();
    private final AtomicInteger preferencesOpened = new AtomicInteger();
    private TextureCreatorState state;
    private TextureCreatorController controller;
    private NewTextureDialog newTextureDialog;

    @BeforeEach
    void register() {
        registry.clear();
        state = new TextureCreatorState();
        controller = new TextureCreatorController(state);
        newTextureDialog = new NewTextureDialog();
        // The file-dialog service is only reached by actions that open a native dialog,
        // which these tests never trigger.
        FileOperationsCoordinator fileOperations =
                new FileOperationsCoordinator(null, controller, state, new ImportStrategyResolver());
        ToolCoordinator toolCoordinator = new ToolCoordinator(state, null);
        PasteCoordinator pasteCoordinator = new PasteCoordinator(state, controller, toolCoordinator, null);

        TextureEditorKeybindActions.registerAll(registry, newTextureDialog, fileOperations, null,
                preferencesOpened::incrementAndGet, new TextureCreatorWindowState(), controller, state,
                pasteCoordinator, toolCoordinator);
    }

    @AfterEach
    void clear() {
        registry.clear();
    }

    @Test
    void everyActionIsRegisteredWithItsDocumentedDefault() {
        List<KeybindAction> registered = registry.getActionsByContext("texture");

        assertEquals(DEFAULTS.size(), registered.size());
        DEFAULTS.forEach((id, chord) -> {
            assertNotNull(registry.getAction(id), id);
            assertEquals(chord, registry.getShortcutDisplayName(id), id);
        });
    }

    @Test
    void noTwoTextureActionsShareAChord() {
        Set<Object> chords = new HashSet<>();
        for (KeybindAction action : registry.getActionsByContext("texture")) {
            assertTrue(chords.add(action.getDefaultKey()), "duplicate chord " + action.getDefaultKey());
        }
    }

    @Test
    void displayNamesAreAsciiForTheImGuiFont() {
        for (KeybindAction action : registry.getActionsByContext("texture")) {
            String text = action.getDisplayName() + action.getCategory() + action.getDefaultKey().getDisplayName();
            assertTrue(text.chars().allMatch(c -> c >= 0x20 && c < 0x7F), action.getId() + ": " + text);
        }
    }

    @Test
    void viewportActionsMayReuseTextureChordsInTheirOwnContext() {
        ViewportUIState viewportState = new ViewportUIState();

        assertDoesNotThrow(() -> ViewportKeybindActions.registerAll(registry,
                new ViewportActions(null, viewportState, null), viewportState));

        assertEquals("Ctrl+Z", registry.getShortcutDisplayName(ViewportKeybindActions.UNDO));
        assertEquals("Ctrl+Z", registry.getShortcutDisplayName("texture.undo"));
    }

    @Test
    void undoAndRedoDriveTheEditorHistory() {
        PixelCanvas canvas = controller.getActiveLayerCanvas();
        DrawCommand stroke = new DrawCommand(canvas, "Pencil");
        stroke.recordPixelChange(1, 1, 0, 0xFF0000FF);
        controller.getCommandHistory().executeCommand(stroke);
        assertEquals(0xFF0000FF, canvas.getPixel(1, 1));

        registry.getAction("texture.undo").execute();
        assertEquals(0, canvas.getPixel(1, 1));

        registry.getAction("texture.redo").execute();
        assertEquals(0xFF0000FF, canvas.getPixel(1, 1));
    }

    @Test
    void deleteAndBackspaceClearTheSelectionUndoably() {
        PixelCanvas canvas = controller.getActiveLayerCanvas();
        canvas.fill(0xFF00FF00);
        state.setCurrentSelection(new RectangularSelection(0, 0, 1, 1));

        registry.getAction("texture.delete_selection").execute();
        assertEquals(0, canvas.getPixel(1, 1));
        assertEquals(0xFF00FF00, canvas.getPixel(2, 2));

        registry.getAction("texture.undo").execute();
        assertEquals(0xFF00FF00, canvas.getPixel(1, 1));

        registry.getAction("texture.delete_selection_backspace").execute();
        assertEquals(0, canvas.getPixel(0, 0));
    }

    @Test
    void viewActionsChangeGridAndZoom() {
        boolean grid = state.getShowGrid().get();
        registry.getAction("texture.toggle_grid").execute();
        assertEquals(!grid, state.getShowGrid().get());

        float zoom = controller.getCanvasState().getZoomLevel();
        registry.getAction("texture.zoom_in").execute();
        assertTrue(controller.getCanvasState().getZoomLevel() > zoom);
        registry.getAction("texture.zoom_out_numpad").execute();
        registry.getAction("texture.zoom_in_numpad").execute();
        registry.getAction("texture.zoom_out").execute();
        assertEquals(zoom, controller.getCanvasState().getZoomLevel(), 1e-4f);

        controller.getCanvasState().adjustPanOffset(30, 40);
        registry.getAction("texture.reset_view").execute();
        assertEquals(0f, controller.getCanvasState().getPanOffsetX());
        assertEquals(0f, controller.getCanvasState().getPanOffsetY());
    }

    @Test
    void clipboardShortcutsAreSafeWithNothingSelectedOrCopied() {
        assertFalse(state.hasSelection());

        registry.getAction("texture.copy").execute();
        registry.getAction("texture.cut").execute();
        registry.getAction("texture.paste").execute();

        assertFalse(controller.canPaste(), "nothing was copied without a selection");
    }

    @Test
    void copyWithASelectionFillsTheClipboard() {
        controller.getActiveLayerCanvas().fill(0xFF0000FF);
        state.setCurrentSelection(new RectangularSelection(0, 0, 3, 3));

        registry.getAction("texture.copy").execute();

        assertTrue(controller.canPaste());
        assertEquals(0xFF0000FF, controller.getActiveLayerCanvas().getPixel(0, 0), "copy does not modify");
    }

    @Test
    void preferencesAndNewTextureOpenTheirWindows() {
        registry.getAction("texture.toggle_preferences").execute();
        registry.getAction("texture.new_texture").execute();

        assertEquals(1, preferencesOpened.get());
        assertTrue(newTextureDialog.isOpen());
    }

    @Test
    void saveWritesStraightToTheOpenProjectWithoutADialog() {
        Path project = dir.resolve("open.omt");
        assertTrue(controller.saveProject(project.toString()));
        controller.getActiveLayerCanvas().setPixel(2, 2, 0xFFFFFFFF);

        registry.getAction("texture.save_project").execute();

        assertTrue(Files.isRegularFile(project));
        assertTrue(controller.loadProject(project.toString()));
        assertEquals(0xFFFFFFFF, controller.getActiveLayerCanvas().getPixel(2, 2), "the edit was saved");
        assertFalse(state.hasUnsavedChanges());
    }
}
