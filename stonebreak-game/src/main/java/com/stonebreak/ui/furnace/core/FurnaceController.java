package com.stonebreak.ui.furnace.core;

import com.stonebreak.blocks.furnace.FurnaceState;
import com.stonebreak.blocks.furnace.FurnaceStateRegistry;
import com.stonebreak.core.Game;
import com.stonebreak.crafting.SmeltingManager;
import com.stonebreak.items.ItemStack;
import com.openmason.engine.util.BlockPos;

/**
 * Coordinates the furnace UI for a single open block. The controller does NOT
 * own smelting state — it operates on a {@link FurnaceState} held by
 * {@link FurnaceStateRegistry}. Closing the UI just hides the screen; the
 * registry keeps ticking the furnace.
 */
public class FurnaceController {

    private final Game game;
    private FurnaceInputManager inputManager;
    private final SmeltingManager smeltingManager;

    /** The furnace block currently bound to the UI. Null when no UI is open. */
    private FurnaceState state;
    private boolean visible;

    /** Where slot actions go: the server, through the multiplayer session (issue #334). */
    private final com.stonebreak.ui.inventoryScreen.handlers.SlotActionSink actions =
            new com.stonebreak.ui.inventoryScreen.handlers.SlotActionSink();

    /** Where the bound furnace's block is checked; tests substitute it. */
    private java.util.function.Predicate<BlockPos> furnaceAt = FurnaceController::worldHasFurnace;

    // ── Furnace slot identifiers (for input manager) ─────────
    public static final int SLOT_INGREDIENT = 0;
    public static final int SLOT_FUEL       = 1;
    public static final int SLOT_OUTPUT     = 2;

    public FurnaceController(Game game,
                             FurnaceInputManager inputManager,
                             SmeltingManager smeltingManager) {
        this.game = game;
        this.inputManager = inputManager;
        this.smeltingManager = smeltingManager;
        this.visible = false;
    }

    /** Bind the UI to the furnace block at {@code pos} and show it. */
    public void open(BlockPos pos) {
        FurnaceStateRegistry registry = game.getFurnaceRegistry();
        bind((registry != null) ? registry.getOrCreate(pos) : new FurnaceState(pos));
    }

    /** Shows the UI bound to {@code furnace}; {@link #open} resolves it from the registry. */
    void bind(FurnaceState furnace) {
        this.state = furnace;
        this.visible = true;
        // Slot actions are addressed to THIS furnace (issue #334).
        actions.bind(com.stonebreak.network.packet.player.ContainerActionC2S.ContainerKind.FURNACE,
                furnace.getPos().x(), furnace.getPos().y(), furnace.getPos().z());
        // Numbered actions: echoes built before the server saw one keep our slots (#353).
        actions.onNumbered(furnace::markSent);
        if (inputManager != null) {
            inputManager.bindActions(actions);
        }
        // Echoes keep arriving while the UI is closed (every cook tick), and the first one
        // leaves a pre-echo snapshot that nothing consumes. It is not an edit of ours: left in
        // place, the first update() would treat that stale snapshot as an edit of ours.
        state.consumePreEchoSlots();
        // UI documents bound to `furnace` follow this state by notification (#289).
        com.stonebreak.ui.runtime.GameUiHost.ifPresent(h -> h.furnaceOpened(furnace));
    }

    public void close() {
        if (inputManager != null) inputManager.handleCloseWithDraggedItems();
        // Do NOT dump contents or clear state — the registry owns it and keeps ticking.
        this.visible = false;
        this.state = null;
        com.stonebreak.ui.runtime.GameUiHost.ifPresent(com.stonebreak.ui.runtime.GameUiHost::furnaceClosed);
    }

    public boolean isVisible() {
        return visible;
    }

    public void update(float deltaTime) {
        if (visible && state != null && !isFurnaceStillThere()) {
            abandonBrokenFurnace();
            return;
        }
        // Smelting is ticked by the AUTHORITATIVE (server-world) FurnaceStateRegistry; this
        // UI is bound to the client display registry, updated by BlockStateS2C echoes.
        //
        // Slot mutations ship as atomic actions the moment the input manager applies them
        // (issue #334) — there is no per-frame slot snapshot to flush any more. Each action
        // is numbered, and an echo that left the server before it arrived carries an older
        // ack, so the state keeps our predicted slots (issue #353); the echo acknowledging
        // it re-baselines via the pre-echo machinery in applyStateString.
    }

    private static boolean worldHasFurnace(BlockPos p) {
        com.stonebreak.world.World world = Game.getWorld();
        // no world to check against: leave the UI alone
        return world == null || world.getBlockAt(p.x(), p.y(), p.z()) == com.stonebreak.blocks.BlockType.FURNACE;
    }

    private boolean isFurnaceStillThere() {
        return furnaceAt.test(state.getPos());
    }

    /** The furnace was broken (by another player) while this UI was open. The server already dropped
     * the contents it knew about, so the displayed slots are dead: detach from them first, so no
     * later edit or action can reach that furnace again, then give the player back only the stack
     * on the cursor (inventory first, the world for what does not fit), and close.
     *
     * <p>An action still in flight at the break is refused at the dead cell, so its stack is
     * still on the server's cursor; the close's DEPOSIT_CARRIED (sent whenever this screen sent
     * anything) drains it, and that sync replaces the predicted mirror (issue #407).
     */
    void abandonBrokenFurnace() {
        this.state = null;
        if (inputManager != null) {
            inputManager.returnCarriedToPlayer();
        }
        if (game != null) {
            game.closeFurnaceScreen();
        } else {
            close();
        }
    }

    /** Test seam: what counts as the furnace still standing. */
    void setFurnaceAt(java.util.function.Predicate<BlockPos> test) {
        this.furnaceAt = test;
    }

    /** The container-action sink this screen's slot rules emit through (test seam). */
    com.stonebreak.ui.inventoryScreen.handlers.SlotActionSink actions() {
        return actions;
    }

    public void handleCloseRequest() {
        game.closeFurnaceScreen();
    }

    /* ── Accessors ───────────────────────────────────────── */

    /** Slot rules addressed by slot, for UI documents (#289). */
    public FurnaceInputManager getInputManager() {
        return inputManager;
    }

    public void setInputManager(FurnaceInputManager inputManager) {
        this.inputManager = inputManager;
    }

    public SmeltingManager getSmeltingManager() { return smeltingManager; }

    /* ── Slot accessors (delegate to FurnaceState) ──────── */

    public ItemStack getIngredientSlot() { return state != null ? state.getIngredient() : new ItemStack(0, 0); }
    public ItemStack getFuelSlot()       { return state != null ? state.getFuel()       : new ItemStack(0, 0); }
    public ItemStack getOutputSlot()     { return state != null ? state.getOutput()     : new ItemStack(0, 0); }

    public void setIngredientSlot(ItemStack stack) { if (state != null) state.setIngredient(stack); }
    public void setFuelSlot(ItemStack stack)       { if (state != null) state.setFuel(stack); }
    public void setOutputSlot(ItemStack stack)     { if (state != null) state.setOutput(stack); }

    public int getBurnTimeRemaining() { return state != null ? state.getBurnTimeRemaining() : 0; }
    public int getCookProgress()      { return state != null ? state.getCookProgress()      : 0; }
    public boolean isCooking()        { return state != null && state.isCooking(); }
    public float getCookProgressRatio() { return state != null ? state.getCookProgressRatio() : 0f; }
    public float getFuelRatio()         { return state != null ? state.getFuelRatio()         : 0f; }
    public int getCurrentBurnUnitTotal() { return state != null ? state.getCurrentBurnUnitTotal() : 0; }

    /** Unused — used to live here for fuel pre-credit; kept as no-op for callers. */
    public void setBurnTimeRemaining(int ticks) { /* fuel ignition is now controlled by FurnaceState.tick */ }
}
