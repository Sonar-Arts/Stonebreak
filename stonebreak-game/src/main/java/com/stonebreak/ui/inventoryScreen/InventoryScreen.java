package com.stonebreak.ui.inventoryScreen;

import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.crafting.CraftingManager;
import com.stonebreak.input.InputHandler;
import com.stonebreak.items.Inventory;
import com.stonebreak.player.CharacterStats;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.ui.Font;
import com.stonebreak.ui.HotbarScreen;
import com.stonebreak.ui.inventoryScreen.core.InventoryController;
import com.stonebreak.ui.inventoryScreen.core.InventoryCraftingManager;
import com.stonebreak.ui.inventoryScreen.core.InventoryInputManager;
import com.stonebreak.ui.inventoryScreen.core.InventorySlotManager;
import com.stonebreak.ui.inventoryScreen.renderers.InventoryRenderCoordinator;
import com.stonebreak.core.GameState;
import com.stonebreak.ui.runtime.screens.PresentationSlot;
import com.stonebreak.ui.runtime.screens.ScreenPresentation;


/**
 * A 2D UI for displaying the player's inventory.
 * Refactored to follow SOLID principles using composition and delegation.
 *
 * <p>Shown by a {@link ScreenPresentation} when one is installed (#300: the shipped document
 * {@value #DOCUMENT_ID}, {@code ui.runtime.screens.ContainerDocument}): the controller keeps the
 * lifecycle (E, Escape, C and the tabs, INVENTORY_UI, closing returns the cursor stack and the 2x2
 * grid) and every slot rule; while the presentation shows, the legacy renderer, mouse poll, tooltip
 * and dragged-item overlay stand down. It shows only in INVENTORY_UI: the recipe book opened over the
 * inventory hides it, as the legacy panel was not drawn there either. The hotbar is not part of it.
 */
public class InventoryScreen {

    /** The shipped document: {@code ui/documents/inventory.sbui}. */
    public static final String DOCUMENT_ID = "inventory";

    /**
     * The shipped gameplay HUD around the hotbar: {@code ui/documents/hud.sbui} (#300). This screen
     * paints the hotbar, so it owns that presentation too: shown wherever the legacy hotbar is drawn.
     */
    public static final String HUD_DOCUMENT_ID = "hud";

    private final InventoryController controller;
    private final InventoryInputManager slotInput;
    private final PresentationSlot presentation = new PresentationSlot();
    private final PresentationSlot hud = new PresentationSlot();
    private volatile GameState state;
    
    /**
     * Creates a new inventory screen using modular architecture.
     */
    public InventoryScreen(Inventory inventory, Font font, Renderer renderer, UIRenderer uiRenderer,
                           InputHandler inputHandler, CraftingManager craftingManager,
                           CharacterStats stats) {
        InventoryCraftingManager craftingManagerModule = new InventoryCraftingManager(craftingManager);
        InventorySlotManager slotManager = new InventorySlotManager(inventory, craftingManagerModule);
        InventoryInputManager inputManager = new InventoryInputManager(inputHandler, inventory,
            slotManager, craftingManagerModule);

        this.controller = new InventoryController(inventory, inputManager, craftingManagerModule, null);
        this.slotInput = inputManager;

        InventoryRenderCoordinator renderCoordinator = new InventoryRenderCoordinator(
            uiRenderer, renderer, inputHandler, inventory, controller, inputManager,
            craftingManagerModule, stats);

        this.controller.setRenderCoordinator(renderCoordinator);
    }

    /**
     * Toggles the visibility of the inventory screen.
     */
    public void toggleVisibility() {
        controller.toggleVisibility();
        if (!isVisible()) {
            presentation.setVisible(false); // shows again only once the state controller enters INVENTORY_UI
        }
        hud.setVisible(hudShows(state));
    }

    /** Installs (or, with null, removes) the alternative presentation; the legacy one is the default. */
    public void setPresentation(ScreenPresentation p) {
        presentation.install(p);
    }

    /**
     * The game entered {@code state}: the inventory's presentation shows while it is open in
     * INVENTORY_UI, the HUD's wherever the hotbar is drawn.
     */
    public void syncPresentation(GameState state) {
        this.state = state;
        com.stonebreak.ui.runtime.GameUiHost.onUiThread(() -> { // the world may be built on another thread
            presentation.setVisible(isVisible() && this.state == GameState.INVENTORY_UI);
            hud.setVisible(hudShows(this.state));
        });
    }

    /** Installs (or, with null, removes) the HUD's presentation; the legacy hotbar renderer is the default. */
    public void setHudPresentation(ScreenPresentation p) {
        hud.install(p);
    }

    /** True while the HUD's presentation, not the legacy hotbar renderer, shows the hotbar. */
    public boolean hudShowing() {
        return hud.showing();
    }

    /**
     * Where the frame renderer draws the hotbar: gameplay and the pause menu (the inventory closed:
     * it has its own hotbar row), and under the character sheet and the recipe book.
     */
    private boolean hudShows(GameState s) {
        if (com.stonebreak.battle.stage.FocusBattle.isActive()) {
            return false; // a battle (paused or not) owns the screen: no hotbar is drawn
        }
        return s == GameState.CHARACTER_SHEET_UI || s == GameState.RECIPE_BOOK_UI
            || ((s == GameState.PLAYING || s == GameState.PAUSED) && !isVisible());
    }

    /** True while a presentation shows the screen instead of the legacy renderer. */
    public boolean presentationShowing() {
        return presentation.showing();
    }

    /** Returns the cursor stack and the 2x2 crafting grid to the player (see issue #307). */
    public void returnHeldItemsToPlayer() {
        controller.returnHeldItemsToPlayer();
    }

    /**
     * Returns whether the inventory screen is currently visible.
     */
    public boolean isVisible() {
        return controller.isVisible();
    }

    public void update(float deltaTime) {
        controller.update(deltaTime);
    }

    /**
     * Call this when a hotbar item is selected to show its name.
     */
    public void displayHotbarItemTooltip(BlockType blockType) {
        controller.displayHotbarItemTooltip(blockType);
    }

    /**
     * Call this when a hotbar item is selected to show its name (supports all Item types).
     */
    public void displayHotbarItemTooltip(com.stonebreak.items.Item item) {
        controller.displayHotbarItemTooltip(item);
    }

    /**
     * Call this when a hotbar item is selected to show its name from an ItemStack.
     */
    public void displayHotbarItemTooltip(com.stonebreak.items.ItemStack itemStack) {
        controller.displayHotbarItemTooltip(itemStack);
    }

    /**
     * Gets the hotbar screen instance.
     */
    public HotbarScreen getHotbarScreen() {
        return controller.getHotbarScreen();
    }

    /**
     * Renders the inventory screen.
     */
    public void render(int screenWidth, int screenHeight) {
        if (presentation.paint(screenWidth, screenHeight)) {
            return; // the document draws the panel, tooltip and carried stack in one paint
        }
        controller.render(screenWidth, screenHeight);
    }
    /**
     * Render the full inventory screen without tooltips.
     * This method is called during the main UI phase, before block drops are rendered.
     */
    public void renderWithoutTooltips(int screenWidth, int screenHeight) {
        if (presentation.paint(screenWidth, screenHeight)) {
            return;
        }
        controller.renderWithoutTooltips(screenWidth, screenHeight);
    }
    
    /**
     * Render only tooltips for the full inventory screen.
     * This method is called after block drops are rendered to ensure tooltips appear above them.
     */
    public void renderTooltipsOnly(int screenWidth, int screenHeight) {
        if (presentation.showing()) {
            return; // the document's router draws its tooltip
        }
        controller.renderTooltipsOnly(screenWidth, screenHeight);
    }

    /**
     * Render only the dragged item for the inventory screen.
     * This method is called during the overlay phase to ensure dragged items appear above all other UI.
     */
    public void renderDraggedItemOnly(int screenWidth, int screenHeight) {
        if (presentation.showing()) {
            return; // the document's cursor layer carries it
        }
        controller.renderDraggedItemOnly(screenWidth, screenHeight);
    }

    // All rendering methods have been delegated to the InventoryRenderCoordinator


    /**
     * Renders the separate hotbar at the bottom of the screen when inventory is closed.
     */
    public void renderHotbar(int screenWidth, int screenHeight) {
        if (hud.paint(screenWidth, screenHeight)) {
            return; // the HUD document draws the hotbar, its surroundings and its tooltip in one paint
        }
        controller.renderHotbar(screenWidth, screenHeight);
    }

    /**
     * Renders the separate hotbar at the bottom of the screen without tooltips.
     * This method is called during the main UI phase, before block drops are rendered.
     */
    public void renderHotbarWithoutTooltips(int screenWidth, int screenHeight) {
        if (hud.paint(screenWidth, screenHeight)) {
            return;
        }
        controller.renderHotbarWithoutTooltips(screenWidth, screenHeight);
    }

    /**
     * Renders only the hotbar tooltip.
     * This method is called after block drops are rendered to ensure tooltips appear above them.
     */
    public void renderHotbarTooltipsOnly(int screenWidth, int screenHeight) {
        if (hud.showing()) {
            return; // the HUD document paints the tooltip with the hotbar
        }
        controller.renderHotbarTooltipsOnly(screenWidth, screenHeight);
    }

    /** Slot rules addressed by slot, for UI documents (#289). */
    public com.stonebreak.ui.inventoryScreen.handlers.ContainerSlotInput getSlotInput() {
        return slotInput;
    }

    // Method to handle mouse clicks for drag and drop
    public void handleMouseInput(int screenWidth, int screenHeight) {
        if (presentation.showing()) {
            return; // a document routes its own input
        }
        controller.handleInput(screenWidth, screenHeight);
    }

    // All input handling methods have been delegated to the InventoryInputManager

}