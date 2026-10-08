package com.stonebreak.ui.inventoryScreen.core;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.workbench.WorkbenchState;
import com.stonebreak.blocks.workbench.WorkbenchStateRegistry;
import com.stonebreak.core.Game;
import com.stonebreak.items.Inventory;
import com.stonebreak.world.World;
import com.stonebreak.ui.inventoryScreen.renderers.InventoryRenderCoordinator;
import com.stonebreak.ui.inventoryScreen.renderers.WorkbenchRenderCoordinator;

/**
 * Controller responsible for coordinating workbench UI operations.
 * Extends InventoryController to reuse inventory functionality while adding workbench-specific behavior.
 * Follows Single Responsibility Principle by handling only workbench coordination logic.
 */
public class WorkbenchController extends InventoryController {

    private final Game game;
    private WorkbenchRenderCoordinator workbenchRenderCoordinator;

    /**
     * The crafting table bound to the UI (issue #307) — the grid the player edits IS this
     * block's persisted grid, held by the world's {@link WorkbenchStateRegistry}. Null when
     * no UI is open.
     */
    private WorkbenchState state;

    /** Last grid snapshot sent to the server, for the per-frame dirty check. */
    private String lastSentSlots;

    public WorkbenchController(Game game,
                              Inventory inventory,
                              InventoryInputManager inputManager,
                              InventoryCraftingManager craftingManager,
                              InventoryRenderCoordinator renderCoordinator) {
        super(inventory, inputManager, craftingManager, renderCoordinator);
        this.game = game;
    }

    /**
     * Only closes: opening needs the crafting table's position ({@link #open(BlockPos)}).
     */
    @Override
    public void toggleVisibility() {
        if (isVisible()) {
            close();
        }
    }

    /**
     * Binds the UI to the crafting table at {@code pos} and shows it. Items already in that
     * table's grid appear; edits go straight to it.
     */
    public void open(BlockPos pos) {
        WorkbenchStateRegistry registry = game == null ? null : game.getWorkbenchRegistry();
        bind((registry != null) ? registry.getOrCreate(pos) : new WorkbenchState(pos));

        // Update mouse capture state when workbench opens
        if (game != null && game.getMouseCaptureManager() != null) {
            game.getMouseCaptureManager().updateCaptureState();
        }
    }

    /**
     * Binds the UI to {@code table}'s grid and shows it, without the mouse-capture update (tests and
     * {@link #open}).
     */
    void bind(WorkbenchState table) {
        this.state = table;
        // An echo that landed while the UI was closed is not an edit of ours — drop its
        // marker, and baseline on the grid as it stands (already what the server knows).
        state.consumePreEchoSlots();
        this.lastSentSlots = state.encodeSlots();
        getCraftingManager().bindInputSlots(state.getSlots());
        setVisible(true);
    }

    /**
     * Closes the workbench screen. Items left in the grid STAY in the crafting table — they
     * are persisted with the block and drop when it is broken (issue #307).
     */
    public void close() {
        if (state != null) {
            handleDraggedItemsOnClose();
            flushSlots();
            getCraftingManager().unbindInputSlots();
            state = null;
        }
        setVisible(false);

        // Update mouse capture state when workbench closes
        if (game != null && game.getMouseCaptureManager() != null) {
            game.getMouseCaptureManager().updateCaptureState();
        }
    }

    @Override
    public void update(float deltaTime) {
        super.update(deltaTime);
        if (!isVisible() || state == null) {
            return;
        }
        if (!isTableStillThere()) {
            abandonBrokenTable();
            return;
        }
        // Grid intent: whatever mutation path the UI took (drag, split, place, take, craft),
        // the per-frame dirty check catches it and ships the full grid to the server, whose
        // BlockStateS2C echo then confirms/corrects it.
        //
        // An echo is NOT intent: one that left the server before our last edit arrived puts
        // the old grid back locally, and re-sending that would undo the edit. So when an echo
        // landed since the last frame, first flush any edit made before it (the pre-echo
        // snapshot), then adopt the echoed grid as the baseline. (Same rule as the furnace.)
        String preEcho = state.consumePreEchoSlots();
        if (preEcho != null) {
            if (!preEcho.equals(lastSentSlots)) {
                sendSlots(preEcho);
            }
            lastSentSlots = state.encodeSlots();
            getCraftingManager().updateCraftingOutput();
        }
        flushSlots();
    }

    /** Sends the grid if it changed since the last send. */
    private void flushSlots() {
        String slots = state.encodeSlots();
        if (!slots.equals(lastSentSlots)) {
            sendSlots(slots);
        }
    }

    private void sendSlots(String slots) {
        lastSentSlots = slots;
        BlockPos p = state.getPos();
        com.stonebreak.network.MultiplayerSession.sendWorkbenchSlots(p.x(), p.y(), p.z(), slots);
    }

    private boolean isTableStillThere() {
        World world = Game.getWorld();
        if (world == null) {
            return true; // nothing to check against — leave the UI alone
        }
        BlockPos p = state.getPos();
        return world.getBlockAt(p.x(), p.y(), p.z()) == BlockType.WORKBENCH;
    }

    /**
     * The table was broken (by another player) while this UI was open. The server already
     * dropped the grid it knew about, so the displayed grid is dead: detach from it, and give
     * the player back only what they were holding on the cursor.
     */
    private void abandonBrokenTable() {
        getCraftingManager().unbindInputSlots();
        state = null;
        // A dragged stack taken from the grid "returns" into the now-detached scratch grid;
        // returnHeldItemsToPlayer then empties that scratch grid into the inventory (only
        // the overflow is dropped), so the player gets back exactly what they held.
        returnHeldItemsToPlayer();
        game.closeWorkbenchScreen();
    }

    /**
     * Handles close request from input (Escape key, etc.).
     */
    public void handleCloseRequest() {
        if (isVisible()) {
            // Game's close method handles the state transition and calls close(), which
            // returns the cursor stack; the grid itself stays in the table.
            game.closeWorkbenchScreen();
        }
    }

    /**
     * Handles dragged items when closing the workbench.
     * Attempts to return items to original slots or player inventory.
     */
    private void handleDraggedItemsOnClose() {
        // This functionality would be handled by the InputManager
        // The controller delegates to the InputManager for drag state management
        InventoryInputManager inputManager = getInputManager();
        if (inputManager != null) {
            inputManager.handleCloseWithDraggedItems();
        }
    }

    /**
     * Opens the recipe book screen from the workbench.
     */
    public void openRecipeBook() {
        if (game != null) {
            game.openRecipeBookScreen();
        }
    }

    /**
     * Sets the workbench render coordinator.
     */
    public void setRenderCoordinator(WorkbenchRenderCoordinator renderCoordinator) {
        this.workbenchRenderCoordinator = renderCoordinator;
    }

    @Override
    public void render(int screenWidth, int screenHeight) {
        if (!isVisible() || workbenchRenderCoordinator == null) return;
        workbenchRenderCoordinator.render(screenWidth, screenHeight);
    }

    @Override
    public void renderWithoutTooltips(int screenWidth, int screenHeight) {
        if (!isVisible() || workbenchRenderCoordinator == null) return;
        workbenchRenderCoordinator.renderWithoutTooltips(screenWidth, screenHeight);
    }

    @Override
    public void renderTooltipsOnly(int screenWidth, int screenHeight) {
        if (!isVisible() || workbenchRenderCoordinator == null) return;
        workbenchRenderCoordinator.renderTooltipsOnly(screenWidth, screenHeight);
    }

    @Override
    public void renderDraggedItemOnly(int screenWidth, int screenHeight) {
        if (!isVisible() || workbenchRenderCoordinator == null) return;
        workbenchRenderCoordinator.renderDraggedItemOnly(screenWidth, screenHeight);
    }

    @Override
    public void renderHotbar(int screenWidth, int screenHeight) {
        if (!isVisible() && workbenchRenderCoordinator != null) {
            workbenchRenderCoordinator.renderHotbar(screenWidth, screenHeight);
        }
    }

    @Override
    public void renderHotbarWithoutTooltips(int screenWidth, int screenHeight) {
        if (!isVisible() && workbenchRenderCoordinator != null) {
            workbenchRenderCoordinator.renderHotbarWithoutTooltips(screenWidth, screenHeight);
        }
    }

    @Override
    public void renderHotbarTooltipsOnly(int screenWidth, int screenHeight) {
        if (!isVisible() && workbenchRenderCoordinator != null) {
            workbenchRenderCoordinator.renderHotbarTooltipsOnly(screenWidth, screenHeight);
        }
    }
}