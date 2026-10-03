package com.openmason.main.systems.viewport;

import com.openmason.main.systems.keybinds.KeybindAction;
import com.openmason.main.systems.keybinds.KeybindRegistry;
import com.openmason.main.systems.menus.textureCreator.keyboard.ShortcutKey;
import com.openmason.main.systems.viewport.state.EditMode;
import com.openmason.main.systems.viewport.state.EditModeManager;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers all viewport-related keybindable actions.
 * <p>
 * This class follows the Single Responsibility Principle by handling only
 * the registration of viewport actions with the keybind registry.
 * </p>
 *
 * @author Open Mason Team
 */
public class ViewportKeybindActions {

    private static final Logger logger = LoggerFactory.getLogger(ViewportKeybindActions.class);
    // Categories for organizing keybinds in the preferences UI
    private static final String DISPLAY = "Display";
    private static final String NAVIGATION = "Navigation";
    private static final String EDITING = "Editing";
    private static final String MESH_TOOLS = "Mesh Tools";
    private static final String FILE = "File";

    // Action ids other surfaces (menus, toolbar tooltips) read their shortcut
    // column and label from, so a rebind never leaves them stale.
    public static final String TOGGLE_GRID = "viewport.toggle_grid";
    public static final String TOGGLE_AXES = "viewport.toggle_axes";
    public static final String TOGGLE_UNRENDERED = "viewport.toggle_unrendered";
    public static final String RESET_VIEW = "viewport.reset_view";
    public static final String FIT_TO_VIEW = "viewport.fit_to_view";
    public static final String UNDO = "viewport.undo";
    public static final String REDO = "viewport.redo";
    public static final String OPEN_MODEL = "viewport.open_model";
    public static final String SAVE_MODEL = "viewport.save_model";

    /**
     * Private constructor to prevent instantiation.
     * This class provides only static registration methods.
     */
    private ViewportKeybindActions() {
    }

    /**
     * Registers all viewport keybind actions with the registry.
     *
     * @param registry the keybind registry
     * @param actions  the viewport actions executor
     * @param state    the viewport UI state
     */
    public static void registerAll(KeybindRegistry registry, ViewportActions actions, ViewportUIState state) {
        logger.debug("Registering viewport keybind actions");

        // ========== Display ==========

        // Ctrl+T: Toggle Transform Gizmo
        registry.registerAction(new KeybindAction(
                "viewport.toggle_gizmo",
                "Toggle Transform Gizmo",
                DISPLAY,
                ShortcutKey.ctrl(GLFW.GLFW_KEY_T),
                actions::toggleGizmo
        ));

        // Ctrl+G: Toggle Grid
        registry.registerAction(new KeybindAction(
                TOGGLE_GRID,
                "Show Grid",
                DISPLAY,
                ShortcutKey.ctrl(GLFW.GLFW_KEY_G),
                () -> {
                    state.getGridVisible().set(!state.getGridVisible().get());
                    actions.toggleGrid();
                }
        ));

        // Ctrl+Shift+A: Toggle Axes (changed from Ctrl+X to avoid conflict with Cut)
        registry.registerAction(new KeybindAction(
                TOGGLE_AXES,
                "Show Axes",
                DISPLAY,
                ShortcutKey.ctrlShift(GLFW.GLFW_KEY_A),
                () -> {
                    state.getAxesVisible().set(!state.getAxesVisible().get());
                    actions.toggleAxes();
                }
        ));

        // Ctrl+W: Toggle Unrendered Mode
        registry.registerAction(new KeybindAction(
                TOGGLE_UNRENDERED,
                "Unrendered Mode",
                DISPLAY,
                ShortcutKey.ctrl(GLFW.GLFW_KEY_W),
                actions::toggleUnrendered
        ));

        // ========== Navigation ==========

        // Ctrl+R: Reset View
        registry.registerAction(new KeybindAction(
                RESET_VIEW,
                "Reset View",
                NAVIGATION,
                ShortcutKey.ctrl(GLFW.GLFW_KEY_R),
                actions::resetView
        ));

        // Ctrl+F: Fit to View
        registry.registerAction(new KeybindAction(
                FIT_TO_VIEW,
                "Fit to View",
                NAVIGATION,
                ShortcutKey.ctrl(GLFW.GLFW_KEY_F),
                actions::fitToView
        ));

        // Tab: Cycle Edit Mode (None -> Vertex -> Edge -> Face -> None)
        // Automatically enables mesh rendering for non-None modes
        registry.registerAction(new KeybindAction(
                "viewport.cycle_edit_mode",
                "Cycle Edit Mode",
                NAVIGATION,
                new ShortcutKey(GLFW.GLFW_KEY_TAB, false, false, false),
                () -> {
                    EditModeManager.getInstance().cycleMode();
                    // Enable mesh rendering for editing modes, disable for None
                    boolean enableMesh = EditModeManager.getInstance().getCurrentMode() != EditMode.NONE;
                    state.getShowVertices().set(enableMesh);
                    actions.toggleShowVertices();
                }
        ));

        // Numpad 5: Toggle Camera Mode (Arcball ↔ First-Person)
        registry.registerAction(new KeybindAction(
                "viewport.toggle_camera_mode",
                "Toggle Camera Mode",
                NAVIGATION,
                ShortcutKey.simple(GLFW.GLFW_KEY_KP_5),
                () -> {
                    int current = state.getCurrentCameraModeIndex().get();
                    int next = (current + 1) % state.getCameraModes().length;
                    state.getCurrentCameraModeIndex().set(next);
                    actions.updateCameraMode();
                }
        ));

        // ========== Editing ==========

        // Ctrl+Z: Undo
        registry.registerAction(new KeybindAction(
                UNDO,
                "Undo",
                EDITING,
                ShortcutKey.ctrl(GLFW.GLFW_KEY_Z),
                actions::undo
        ));

        // Ctrl+Y: Redo
        registry.registerAction(new KeybindAction(
                REDO,
                "Redo",
                EDITING,
                ShortcutKey.ctrl(GLFW.GLFW_KEY_Y),
                actions::redo
        ));

        // G: Grab Selection (Blender-style) - start dragging all selected items
        registry.registerAction(new KeybindAction(
                "viewport.grab_selection",
                "Grab Selection",
                EDITING,
                ShortcutKey.simple(GLFW.GLFW_KEY_G),
                actions::startGrabMode
        ));

        // S: Scale Selection (Blender-style) - modal uniform scale, S again confirms
        registry.registerAction(new KeybindAction(
                "viewport.scale_selection",
                "Scale Selection",
                EDITING,
                ShortcutKey.simple(GLFW.GLFW_KEY_S),
                actions::startScaleMode
        ));

        // B: Box Select (Blender-style) - drag a rectangle to select, B/Esc cancels
        registry.registerAction(new KeybindAction(
                "viewport.box_select",
                "Box Select",
                EDITING,
                ShortcutKey.simple(GLFW.GLFW_KEY_B),
                actions::toggleBoxSelect
        ));

        // Ctrl+Shift+S: Toggle Grid Snapping
        registry.registerAction(new KeybindAction(
                "viewport.toggle_grid_snapping",
                "Toggle Grid Snapping",
                EDITING,
                ShortcutKey.ctrlShift(GLFW.GLFW_KEY_S),
                () -> {
                    state.toggleGridSnapping();
                    actions.toggleGridSnapping();
                }
        ));

        // ========== Mesh Tools ==========

        // Ctrl+E: Subdivide Edge (Edge mode only) - subdivides all selected edges, or hovered if none selected
        registry.registerAction(new KeybindAction(
                "viewport.subdivide_edge",
                "Subdivide Edge",
                MESH_TOOLS,
                ShortcutKey.ctrl(GLFW.GLFW_KEY_E),
                actions::subdivideSelectedEdges
        ));

        // K: Knife Tool (Edge mode only) - two-click face splitting
        registry.registerAction(new KeybindAction(
                "viewport.knife_tool",
                "Knife Tool",
                MESH_TOOLS,
                ShortcutKey.simple(GLFW.GLFW_KEY_K),
                actions::toggleKnifeTool
        ));

        // I: Inset Faces (Face mode only) - modal per-face inset, I again confirms
        registry.registerAction(new KeybindAction(
                "viewport.inset_faces",
                "Inset Faces",
                MESH_TOOLS,
                ShortcutKey.simple(GLFW.GLFW_KEY_I),
                actions::startInsetMode
        ));

        // E: Extrude Faces (Face mode only) - modal per-face extrude along normals, E again confirms
        registry.registerAction(new KeybindAction(
                "viewport.extrude_faces",
                "Extrude Faces",
                MESH_TOOLS,
                ShortcutKey.simple(GLFW.GLFW_KEY_E),
                actions::startExtrudeMode
        ));

        logger.info("Registered {} viewport keybind actions", 18);
    }

    /**
     * Registers the model editor's file shortcuts (Ctrl+O / Ctrl+S) in the
     * viewport context, so they fire while the model viewport has focus and
     * appear on the Keybinds page. Registered separately from
     * {@link #registerAll} because the file operations live on the main
     * interface, not on {@link ViewportActions}.
     *
     * @param registry  the keybind registry
     * @param openModel opens a model via the file dialog
     * @param saveModel saves the current model (no-op when there is nothing to save)
     */
    public static void registerFileActions(KeybindRegistry registry, Runnable openModel, Runnable saveModel) {
        registry.registerAction(new KeybindAction(
                OPEN_MODEL,
                "Open Model",
                FILE,
                ShortcutKey.ctrl(GLFW.GLFW_KEY_O),
                openModel
        ));

        registry.registerAction(new KeybindAction(
                SAVE_MODEL,
                "Save Model",
                FILE,
                ShortcutKey.ctrl(GLFW.GLFW_KEY_S),
                saveModel
        ));
    }
}
