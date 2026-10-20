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

    /** Last slot snapshot sent to the server, for the per-frame dirty check. */
    private String lastSentSlots;

    /** Where slot snapshots go: the server, through the multiplayer session. */
    private SlotSink slotSink = (pos, seq, slots) ->
        com.stonebreak.network.MultiplayerSession.sendFurnaceSlots(pos.x(), pos.y(), pos.z(), seq, slots);

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
        // Echoes keep arriving while the UI is closed (every cook tick), and the first one
        // leaves a pre-echo snapshot that nothing consumes. It is not an edit of ours: left in
        // place, the first update() would send that stale snapshot and roll the furnace back.
        state.consumePreEchoSlots();
        // Baseline for the slot dirty check: the state as it stands when the UI opens is
        // already what the server knows (streamed/echoed), so don't re-send it.
        this.lastSentSlots = state.encodeSlots();
        // UI documents bound to `furnace` follow this state by notification (#289).
        com.stonebreak.ui.runtime.GameUiHost.ifPresent(h -> h.furnaceOpened(furnace));
    }

    public void close() {
        if (inputManager != null) inputManager.handleCloseWithDraggedItems();
        // A carried stack may just have gone back into a furnace slot. Ship that
        // to the server now: once `state` is null the per-frame dirty check never
        // runs again, and the next echo would overwrite the restore (#320).
        if (state != null) syncSlots();
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

        // Slot intent: whatever mutation path the UI took (drag, split, place, take), the
        // per-frame dirty check catches it and ships the full slot snapshot to the server.
        // BlockStateS2C echoes then confirm/correct — including the smelting results.
        //
        // Echoes are NOT intent. One that left before our last intent arrived carries an older
        // ack, and the state keeps our slots (issue #353). One that acknowledges it may still
        // overwrite an edit made since the last frame, so when an echo landed, first flush any
        // edit made before it (the pre-echo snapshot), then adopt the echoed slots as the
        // baseline; re-sending the echo itself would undo the player's edit.
        if (visible && state != null) {
            syncSlots();
        }
    }

    private static boolean worldHasFurnace(BlockPos p) {
        com.stonebreak.world.World world = Game.getWorld();
        // no world to check against: leave the UI alone
        return world == null || world.getBlockAt(p.x(), p.y(), p.z()) == com.stonebreak.blocks.BlockType.FURNACE;
    }

    private boolean isFurnaceStillThere() {
        return furnaceAt.test(state.getPos());
    }

    /**
     * The furnace was broken (by another player) while this UI was open. The server already dropped
     * the contents it knew about, so the displayed slots are dead: detach from them first, so no
     * later edit or snapshot can reach that furnace again, then give the player back only the stack
     * on the cursor (inventory first, the world for what does not fit), and close.
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

    /** Sends the slot snapshot if it changed since the last send (see {@link #update}). */
    private void syncSlots() {
        String preEcho = state.consumePreEchoSlots();
        if (preEcho != null) {
            if (!preEcho.equals(lastSentSlots)) {
                sendSlots(preEcho);
            }
            lastSentSlots = state.encodeSlots();
        }
        String slots = state.encodeSlots();
        if (!slots.equals(lastSentSlots)) {
            sendSlots(slots);
        }
    }

    private void sendSlots(String slots) {
        lastSentSlots = slots;
        int seq = com.stonebreak.network.SlotIntentSeq.next();
        state.markSent(seq);
        slotSink.send(state.getPos(), seq, slots);
    }

    /** Receives slot snapshots bound for the server, each numbered for the echo's ack. */
    @FunctionalInterface
    interface SlotSink {
        void send(BlockPos pos, int seq, String slots);
    }

    /** Test seam: capture slot snapshots instead of sending them. */
    void setSlotSink(SlotSink sink) {
        this.slotSink = sink;
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
