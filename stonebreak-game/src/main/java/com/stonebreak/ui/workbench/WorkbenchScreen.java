package com.stonebreak.ui.workbench;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.crafting.CraftingManager;
import com.stonebreak.input.InputHandler;
import com.stonebreak.items.Inventory;
import com.stonebreak.ui.inventoryScreen.core.*;
import com.stonebreak.ui.runtime.screens.PresentationSlot;
import com.stonebreak.ui.runtime.screens.ScreenPresentation;

/**
 * A workbench screen that extends the inventory system architecture.
 * Uses a 3x3 crafting grid instead of the 2x2 inventory crafting grid.
 * Follows SOLID principles by composing existing modular components.
 *
 * <p>Shown by its {@link ScreenPresentation} (#300: the shipped document {@value #DOCUMENT_ID},
 * {@code ui.runtime.screens.ContainerDocument}): the controller keeps the lifecycle (open at a
 * table, Escape, WORKBENCH_UI, the grid bound to the table, a broken table abandoned, close puts the
 * cursor stack back) and every slot rule. It shows only in WORKBENCH_UI: the recipe book opened over
 * the table hides it and Escape back shows it again.
 */
public class WorkbenchScreen {

    /** The shipped document: {@code ui/documents/workbench.sbui}. */
    public static final String DOCUMENT_ID = "workbench";

    private final WorkbenchController controller;
    private final WorkbenchInputManager slotInput;
    private final PresentationSlot presentation = new PresentationSlot();
    private volatile GameState state;

    /**
     * Creates a new workbench screen using the modular inventory architecture.
     */
    public WorkbenchScreen(Game game, Inventory inventory, InputHandler inputHandler,
                          CraftingManager craftingManager) {
        // Create workbench-specific crafting manager with 3x3 grid
        InventoryCraftingManager workbenchCraftingManager = new InventoryCraftingManager(
            craftingManager,
            InventoryLayoutCalculator.getWorkbenchCraftingGridSize()
        );

        // Create slot manager for workbench
        InventorySlotManager slotManager = new InventorySlotManager(inventory, workbenchCraftingManager);

        // Create workbench input manager that uses 3x3 layout
        WorkbenchInputManager inputManager = new WorkbenchInputManager(
            inputHandler, inventory, slotManager, workbenchCraftingManager
        );

        this.slotInput = inputManager;

        // Create workbench controller
        this.controller = new WorkbenchController(
            game, inventory, inputManager, workbenchCraftingManager
        );
    }

    /** Slot rules addressed by slot, for UI documents (#289). */
    public com.stonebreak.ui.inventoryScreen.handlers.ContainerSlotInput getSlotInput() {
        return slotInput;
    }

    /**
     * Opens the workbench screen on the crafting table at {@code pos} (each table has its own
     * persisted grid — issue #307).
     */
    public void open(BlockPos pos) {
        controller.open(pos);
        syncPresentation(GameState.WORKBENCH_UI); // the state controller enters it before opening
    }

    /**
     * Closes the workbench screen.
     */
    public void close() {
        controller.close();
        presentation.setVisible(false);
    }

    /** Installs (or, with null, removes) the presentation. */
    public void setPresentation(ScreenPresentation p) {
        presentation.install(p);
    }

    /** The game entered {@code state}: the presentation shows while the table is open in WORKBENCH_UI. */
    public void syncPresentation(GameState state) {
        this.state = state;
        // posted from another thread, it runs on the UI thread with whatever state is current by then
        com.stonebreak.ui.runtime.GameUiHost.onUiThread(
            () -> presentation.setVisible(isVisible() && this.state == GameState.WORKBENCH_UI));
    }

    /** True while the presentation shows the screen. */
    public boolean presentationShowing() {
        return presentation.showing();
    }

    /**
     * Returns whether the workbench screen is currently visible.
     */
    public boolean isVisible() {
        return controller.isVisible();
    }

    /**
     * Updates the workbench screen.
     */
    public void update(float deltaTime) {
        controller.update(deltaTime);
    }

    /** Paints the presentation: the panel, tooltip and carried stack in one paint. */
    public void render() {
        presentation.paint(Game.getWindowWidth(), Game.getWindowHeight());
    }

    /**
     * Handles close request (Escape key, etc.).
     */
    public void handleCloseRequest() {
        controller.handleCloseRequest();
    }
}
