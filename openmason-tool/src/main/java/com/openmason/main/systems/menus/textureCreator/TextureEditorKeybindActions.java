package com.openmason.main.systems.menus.textureCreator;

import com.openmason.main.systems.keybinds.KeybindAction;
import com.openmason.main.systems.keybinds.KeybindRegistry;
import com.openmason.main.systems.menus.textureCreator.coordinators.FileOperationsCoordinator;
import com.openmason.main.systems.menus.textureCreator.coordinators.PasteCoordinator;
import com.openmason.main.systems.menus.textureCreator.coordinators.ToolCoordinator;
import com.openmason.main.systems.menus.textureCreator.dialogs.NewTextureDialog;
import com.openmason.main.systems.menus.dialogs.ExportFormatDialog;
import com.openmason.main.systems.menus.textureCreator.keyboard.ShortcutKey;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers all texture editor keybindable actions.
 * <p>
 * This class follows the Single Responsibility Principle by handling only
 * the registration of texture editor actions with the keybind registry.
 * </p>
 * <p>
 * Actions are organized into categories for UI display:
 * - File Operations (New, Open, Save, Save As, Export)
 * - Edit (Undo, Redo)
 * - Clipboard (Copy, Cut, Paste)
 * - Selection (Delete)
 * - View (Toggle Grid, Zoom, Reset View)
 * - Tools (Confirm, Cancel)
 * - Window (Preferences)
 * </p>
 *
 * @author Open Mason Team
 */
public class TextureEditorKeybindActions {

    private static final Logger logger = LoggerFactory.getLogger(TextureEditorKeybindActions.class);
    private static final float ZOOM_FACTOR = 1.2f; // Same as TextureCreatorImGui

    /**
     * Private constructor to prevent instantiation.
     * This class provides only static registration methods.
     */
    private TextureEditorKeybindActions() {
    }

    /**
     * Registers all texture editor keybind actions with the registry.
     *
     * @param registry           the keybind registry
     * @param newTextureDialog   the new texture dialog
     * @param fileOperations     the file operations coordinator
     * @param exportFormatDialog the export format dialog
     * @param windowState        the window state manager
     * @param controller         the texture editor controller
     * @param state              the texture editor state
     * @param pasteCoordinator   the paste coordinator
     * @param toolCoordinator    the tool coordinator
     */
    public static void registerAll(
            KeybindRegistry registry,
            NewTextureDialog newTextureDialog,
            FileOperationsCoordinator fileOperations,
            ExportFormatDialog exportFormatDialog,
            TextureCreatorWindowState windowState,
            TextureCreatorController controller,
            TextureCreatorState state,
            PasteCoordinator pasteCoordinator,
            ToolCoordinator toolCoordinator) {

        logger.debug("Registering texture editor keybind actions");

        // ========== File Operations ==========

        registry.registerAction(new KeybindAction(
                "texture.new_texture",
                "New Texture",
                "File Operations",
                ShortcutKey.ctrl(GLFW.GLFW_KEY_N),
                newTextureDialog::show
        ));

        registry.registerAction(new KeybindAction(
                "texture.open_project",
                "Open Project",
                "File Operations",
                ShortcutKey.ctrl(GLFW.GLFW_KEY_O),
                fileOperations::openProject
        ));

        registry.registerAction(new KeybindAction(
                "texture.save_project",
                "Save Project",
                "File Operations",
                ShortcutKey.ctrl(GLFW.GLFW_KEY_S),
                fileOperations::saveProject
        ));

        // Save Project As - keybind removed (Ctrl+Shift+S now used by viewport grid snapping)
        // Access via File menu instead

        // Export - keybind removed (Ctrl+E now used by viewport edge subdivision)
        // Access via File menu instead

        // ========== Window ==========

        registry.registerAction(new KeybindAction(
                "texture.toggle_preferences",
                "Toggle Preferences",
                "Window",
                ShortcutKey.ctrl(GLFW.GLFW_KEY_COMMA),
                windowState::togglePreferencesWindow
        ));

        // ========== Edit ==========

        registry.registerAction(new KeybindAction(
                "texture.undo",
                "Undo",
                "Edit",
                ShortcutKey.ctrl(GLFW.GLFW_KEY_Z),
                controller::undo
        ));

        registry.registerAction(new KeybindAction(
                "texture.redo",
                "Redo",
                "Edit",
                ShortcutKey.ctrl(GLFW.GLFW_KEY_Y),
                controller::redo
        ));

        // ========== Clipboard ==========

        registry.registerAction(new KeybindAction(
                "texture.copy",
                "Copy Selection",
                "Clipboard",
                ShortcutKey.ctrl(GLFW.GLFW_KEY_C),
                () -> {
                    if (state.hasSelection()) {
                        controller.copySelection();
                    }
                }
        ));

        registry.registerAction(new KeybindAction(
                "texture.cut",
                "Cut Selection",
                "Clipboard",
                ShortcutKey.ctrl(GLFW.GLFW_KEY_X),
                () -> {
                    if (state.hasSelection()) {
                        controller.cutSelection();
                    }
                }
        ));

        registry.registerAction(new KeybindAction(
                "texture.paste",
                "Paste",
                "Clipboard",
                ShortcutKey.ctrl(GLFW.GLFW_KEY_V),
                () -> {
                    if (pasteCoordinator.canPaste()) {
                        pasteCoordinator.initiatePaste();
                    }
                }
        ));

        // ========== Selection ==========

        registry.registerAction(new KeybindAction(
                "texture.delete_selection",
                "Delete Selection",
                "Selection",
                ShortcutKey.simple(GLFW.GLFW_KEY_DELETE),
                controller::deleteSelection
        ));

        registry.registerAction(new KeybindAction(
                "texture.delete_selection_backspace",
                "Delete Selection (Backspace)",
                "Selection",
                ShortcutKey.simple(GLFW.GLFW_KEY_BACKSPACE),
                controller::deleteSelection
        ));

        // ========== View ==========

        registry.registerAction(new KeybindAction(
                "texture.toggle_grid",
                "Toggle Grid",
                "View",
                ShortcutKey.simple(GLFW.GLFW_KEY_G),
                () -> state.getShowGrid().set(!state.getShowGrid().get())
        ));

        registry.registerAction(new KeybindAction(
                "texture.zoom_in",
                "Zoom In",
                "View",
                ShortcutKey.simple(GLFW.GLFW_KEY_EQUAL),
                () -> controller.getCanvasState().zoomIn(ZOOM_FACTOR)
        ));

        registry.registerAction(new KeybindAction(
                "texture.zoom_in_numpad",
                "Zoom In (Numpad)",
                "View",
                ShortcutKey.simple(GLFW.GLFW_KEY_KP_ADD),
                () -> controller.getCanvasState().zoomIn(ZOOM_FACTOR)
        ));

        registry.registerAction(new KeybindAction(
                "texture.zoom_out",
                "Zoom Out",
                "View",
                ShortcutKey.simple(GLFW.GLFW_KEY_MINUS),
                () -> controller.getCanvasState().zoomOut(ZOOM_FACTOR)
        ));

        registry.registerAction(new KeybindAction(
                "texture.zoom_out_numpad",
                "Zoom Out (Numpad)",
                "View",
                ShortcutKey.simple(GLFW.GLFW_KEY_KP_SUBTRACT),
                () -> controller.getCanvasState().zoomOut(ZOOM_FACTOR)
        ));

        registry.registerAction(new KeybindAction(
                "texture.reset_view",
                "Reset View",
                "View",
                ShortcutKey.simple(GLFW.GLFW_KEY_0),
                () -> controller.getCanvasState().resetView()
        ));

        registry.registerAction(new KeybindAction(
                "texture.reset_view_numpad",
                "Reset View (Numpad)",
                "View",
                ShortcutKey.simple(GLFW.GLFW_KEY_KP_0),
                () -> controller.getCanvasState().resetView()
        ));

        // ========== Tools ==========

        registry.registerAction(new KeybindAction(
                "texture.confirm",
                "Confirm Tool Action",
                "Tools",
                ShortcutKey.simple(GLFW.GLFW_KEY_ENTER),
                toolCoordinator::handleEnterKey
        ));

        registry.registerAction(new KeybindAction(
                "texture.confirm_numpad",
                "Confirm Tool Action (Numpad)",
                "Tools",
                ShortcutKey.simple(GLFW.GLFW_KEY_KP_ENTER),
                toolCoordinator::handleEnterKey
        ));

        registry.registerAction(new KeybindAction(
                "texture.cancel",
                "Cancel Tool Action",
                "Tools",
                ShortcutKey.simple(GLFW.GLFW_KEY_ESCAPE),
                toolCoordinator::handleEscapeKey
        ));

        logger.info("Registered {} texture editor keybind actions", 23);
    }
}
