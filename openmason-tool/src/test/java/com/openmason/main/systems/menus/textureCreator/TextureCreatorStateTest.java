package com.openmason.main.systems.menus.textureCreator;

import com.openmason.main.systems.menus.textureCreator.selection.RectangularSelection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Editor-level state: canvas size validation, dirty tracking, selection
 * delegation and symmetry settings.
 */
class TextureCreatorStateTest {

    @Test
    void canvasSizeRejectsNonPositiveDimensions() {
        assertThrows(IllegalArgumentException.class, () -> new TextureCreatorState.CanvasSize(0, 16));
        assertThrows(IllegalArgumentException.class, () -> new TextureCreatorState.CanvasSize(16, -1));
        assertEquals("64x48", new TextureCreatorState.CanvasSize(64, 48).getDisplayName());
    }

    @Test
    void newStateIsACleanSixteenSquareCanvasWithOpaqueBlack() {
        TextureCreatorState state = new TextureCreatorState();
        assertEquals(new TextureCreatorState.CanvasSize(16, 16), state.getCurrentCanvasSize());
        assertEquals(0xFF000000, state.getCurrentColor());
        assertFalse(state.hasUnsavedChanges());
        assertFalse(state.hasFilePath());
        assertFalse(state.hasSelection());
    }

    @Test
    void dirtyFlagFollowsModifyAndSave() {
        TextureCreatorState state = new TextureCreatorState();
        state.markAsModified();
        assertTrue(state.hasUnsavedChanges());
        state.markAsSaved();
        assertFalse(state.hasUnsavedChanges());
    }

    @Test
    void blankFilePathsDoNotCountAsLoaded() {
        TextureCreatorState state = new TextureCreatorState();
        state.setCurrentFilePath("   ");
        assertFalse(state.hasFilePath());
        state.setCurrentFilePath("/tmp/a.omt");
        assertTrue(state.hasFilePath());
    }

    @Test
    void selectionConvenienceMethodsDelegateToTheSharedManager() {
        TextureCreatorState state = new TextureCreatorState();
        RectangularSelection selection = new RectangularSelection(0, 0, 3, 3);
        state.setCurrentSelection(selection);

        assertSame(selection, state.getSelectionManager().getActiveSelection());
        assertSame(selection, state.getCurrentSelection());
        assertTrue(state.hasSelection());

        state.getSelectionManager().clearSelection();
        assertNull(state.getCurrentSelection());
        state.setCurrentSelection(selection);
        state.clearSelection();
        assertFalse(state.getSelectionManager().hasActiveSelection());
    }

    @Test
    void symmetryDefaultsEnableOnlyFreehandTools() {
        SymmetryState symmetry = new TextureCreatorState().getSymmetryState();
        assertFalse(symmetry.isActive());
        assertTrue(symmetry.isEnabledForTool("PencilTool"));
        assertTrue(symmetry.isEnabledForTool("BrushTool"));
        assertTrue(symmetry.isEnabledForTool("EraserTool"));
        assertFalse(symmetry.isEnabledForTool("FillTool"));
        assertFalse(symmetry.isEnabledForTool("SomeFutureTool"), "unknown tools default to off");
    }

    @Test
    void symmetryResetRestoresModeAxisAndToolDefaults() {
        SymmetryState symmetry = new SymmetryState();
        symmetry.setMode(SymmetryState.SymmetryMode.QUADRANT);
        symmetry.setAxisOffsetX(-3);
        symmetry.setAxisOffsetY(5);
        symmetry.setShowAxisLines(false);
        symmetry.setEnabledForTool("PencilTool", false);
        symmetry.setEnabledForTool("FillTool", true);
        assertTrue(symmetry.isActive());

        symmetry.resetToDefaults();

        assertEquals(SymmetryState.SymmetryMode.NONE, symmetry.getMode());
        assertEquals(0, symmetry.getAxisOffsetX());
        assertEquals(0, symmetry.getAxisOffsetY());
        assertTrue(symmetry.isShowAxisLines());
        assertTrue(symmetry.isEnabledForTool("PencilTool"));
        assertFalse(symmetry.isEnabledForTool("FillTool"));
    }

    @Test
    void symmetryModesHaveAsciiDisplayText() {
        // Shown in ImGui, whose font has no glyphs beyond ASCII.
        for (SymmetryState.SymmetryMode mode : SymmetryState.SymmetryMode.values()) {
            assertTrue(mode.getDisplayName().chars().allMatch(c -> c >= 0x20 && c < 0x7F), mode.name());
            assertTrue(mode.getDescription().chars().allMatch(c -> c >= 0x20 && c < 0x7F), mode.name());
        }
    }
}
