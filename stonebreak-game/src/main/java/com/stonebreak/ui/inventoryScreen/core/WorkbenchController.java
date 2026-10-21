package com.stonebreak.ui.inventoryScreen.core;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.workbench.WorkbenchState;
import com.stonebreak.blocks.workbench.WorkbenchStateRegistry;
import com.stonebreak.core.Game;
import com.stonebreak.items.Inventory;
import com.stonebreak.world.World;

/**
 * Controller responsible for coordinating workbench UI operations.
 * Extends InventoryController to reuse inventory functionality while adding workbench-specific behavior.
 * Follows Single Responsibility Principle by handling only workbench coordination logic.
 */
public class WorkbenchController extends InventoryController {

    private final Game game;

    /**
     * The crafting table bound to the UI (issue #307) — the grid the player edits IS this
     * block's persisted grid, held by the world's {@link WorkbenchStateRegistry}. Null when
     * no UI is open.
     */
    private WorkbenchState state;

    public WorkbenchController(Game game,
                              Inventory inventory,
                              InventoryInputManager inputManager,
                              InventoryCraftingManager craftingManager) {
        super(inventory, inputManager, craftingManager);
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
        // Slot actions are addressed to THIS table (issue #334).
        getInputManager().getSlotManager().bindContainer(
                com.stonebreak.network.packet.player.ContainerActionC2S.ContainerKind.WORKBENCH,
                table.getPos().x(), table.getPos().y(), table.getPos().z(),
                com.stonebreak.network.packet.player.ContainerActionC2S.Region.WORKBENCH_GRID,
                com.stonebreak.network.packet.player.ContainerActionC2S.Region.WORKBENCH_OUT);
        // Numbered actions: echoes built before the server saw one keep our grid (#353).
        getInputManager().getSlotManager().actions.onNumbered(table::markSent);
        // An echo that landed while the UI was closed is not an edit of ours — drop its
        // marker, and baseline on the grid as it stands (already what the server knows).
        state.consumePreEchoSlots();
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
        // Grid mutations ship as atomic actions the moment the input manager applies them
        // (issue #334) — there is no per-frame grid snapshot to flush. An echo older than our
        // last numbered action is dropped (issue #353); one acknowledging it re-baselines
        // via the pre-echo machinery; the output preview is recomputed from the (local)
        // grid, and the AUTHORITATIVE result comes from the server's TAKE_OUTPUT.
        if (state.consumePreEchoSlots() != null) {
            getCraftingManager().updateCraftingOutput();
        }
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
            // Return the cursor stack, and deposit whatever the server may still carry
            // (issue #334). The grid stays in the table, so it is not quick-moved out.
            inputManager.closeBlockContainer();
        }
    }
}
