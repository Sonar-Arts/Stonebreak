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
    private static final String CATEGORY = "Viewport";

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

        // Ctrl+T: Toggle Transform Gizmo
        registry.registerAction(new KeybindAction(
                "viewport.toggle_gizmo",
                "Toggle Transform Gizmo",
                CATEGORY,
                ShortcutKey.ctrl(GLFW.GLFW_KEY_T),
                actions::toggleGizmo
        ));

        // Ctrl+G: Toggle Grid
        registry.registerAction(new KeybindAction(
                "viewport.toggle_grid",
                "Toggle Grid",
                CATEGORY,
                ShortcutKey.ctrl(GLFW.GLFW_KEY_G),
                () -> {
                    state.getGridVisible().set(!state.getGridVisible().get());
                    actions.toggleGrid();
                }
        ));

        // Ctrl+Shift+A: Toggle Axes (changed from Ctrl+X to avoid conflict with Cut)
        registry.registerAction(new KeybindAction(
                "viewport.toggle_axes",
                "Toggle Axes",
                CATEGORY,
                ShortcutKey.ctrlShift(GLFW.GLFW_KEY_A),
                () -> {
                    state.getAxesVisible().set(!state.getAxesVisible().get());
                    actions.toggleAxes();
                }
        ));

        // Ctrl+W: Toggle Wireframe
        registry.registerAction(new KeybindAction(
                "viewport.toggle_wireframe",
                "Toggle Wireframe",
                CATEGORY,
                ShortcutKey.ctrl(GLFW.GLFW_KEY_W),
                actions::toggleWireframe
        ));

        // Ctrl+R: Reset View
        registry.registerAction(new KeybindAction(
                "viewport.reset_view",
                "Reset View",
                CATEGORY,
                ShortcutKey.ctrl(GLFW.GLFW_KEY_R),
                actions::resetView
        ));

        // Ctrl+F: Fit to View
        registry.registerAction(new KeybindAction(
                "viewport.fit_to_view",
                "Fit to View",
                CATEGORY,
                ShortcutKey.ctrl(GLFW.GLFW_KEY_F),
                actions::fitToView
        ));

        // Tab: Cycle Edit Mode (None -> Vertex -> Edge -> Face -> None)
        // Automatically enables mesh rendering for non-None modes
        registry.registerAction(new KeybindAction(
                "viewport.cycle_edit_mode",
                "Cycle Edit Mode",
                CATEGORY,
                new ShortcutKey(GLFW.GLFW_KEY_TAB, false, false, false),
                () -> {
                    EditModeManager.getInstance().cycleMode();
                    // Enable mesh rendering for editing modes, disable for None
                    boolean enableMesh = EditModeManager.getInstance().getCurrentMode() != EditMode.NONE;
                    state.getShowVertices().set(enableMesh);
                    actions.toggleShowVertices();
                }
        ));

        // Ctrl+Shift+S: Toggle Grid Snapping
        registry.registerAction(new KeybindAction(
                "viewport.toggle_grid_snapping",
                "Toggle Grid Snapping",
                CATEGORY,
                ShortcutKey.ctrlShift(GLFW.GLFW_KEY_S),
                () -> {
                    state.toggleGridSnapping();
                    actions.toggleGridSnapping();
                }
        ));

        // Ctrl+E: Subdivide Edge (Edge mode only) - subdivides all selected edges, or hovered if none selected
        registry.registerAction(new KeybindAction(
                "viewport.subdivide_edge",
                "Subdivide Edge",
                CATEGORY,
                ShortcutKey.ctrl(GLFW.GLFW_KEY_E),
                actions::subdivideSelectedEdges
        ));

        // G: Grab Selection (Blender-style) - start dragging all selected items
        registry.registerAction(new KeybindAction(
                "viewport.grab_selection",
                "Grab Selection",
                CATEGORY,
                ShortcutKey.simple(GLFW.GLFW_KEY_G),
                actions::startGrabMode
        ));

        // K: Knife Tool (Edge mode only) - two-click face splitting
        registry.registerAction(new KeybindAction(
                "viewport.knife_tool",
                "Knife Tool",
                CATEGORY,
                ShortcutKey.simple(GLFW.GLFW_KEY_K),
                actions::toggleKnifeTool
        ));

        logger.info("Registered {} viewport keybind actions", 11);
    }
}
