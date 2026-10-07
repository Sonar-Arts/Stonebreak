package com.stonebreak.ui.furnace.core;

import com.stonebreak.blocks.furnace.FurnaceState;
import com.stonebreak.blocks.furnace.FurnaceStateRegistry;
import com.stonebreak.core.Game;
import com.stonebreak.crafting.SmeltingManager;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.ui.HotbarScreen;
import com.stonebreak.ui.furnace.renderers.FurnaceRenderCoordinator;
import com.openmason.engine.util.BlockPos;

/**
 * Coordinates the furnace UI for a single open block. The controller does NOT
 * own smelting state — it operates on a {@link FurnaceState} held by
 * {@link FurnaceStateRegistry}. Closing the UI just hides the screen; the
 * registry keeps ticking the furnace.
 */
public class FurnaceController {

    private final Game game;
    private final Inventory inventory;
    private final HotbarScreen hotbarScreen;
    private FurnaceInputManager inputManager;
    private final SmeltingManager smeltingManager;
    private FurnaceRenderCoordinator renderCoordinator;

    /** The furnace block currently bound to the UI. Null when no UI is open. */
    private FurnaceState state;
    private boolean visible;

    /** Last slot snapshot sent to the server, for the per-frame dirty check. */
    private String lastSentSlots;

    /** Where slot snapshots go: the server, through the multiplayer session. */
    private SlotSink slotSink = (pos, slots) ->
        com.stonebreak.network.MultiplayerSession.sendFurnaceSlots(pos.x(), pos.y(), pos.z(), slots);

    private ItemStack hoveredItemStack;

    // ── Furnace slot identifiers (for input manager) ─────────
    public static final int SLOT_INGREDIENT = 0;
    public static final int SLOT_FUEL       = 1;
    public static final int SLOT_OUTPUT     = 2;

    public FurnaceController(Game game,
                             Inventory inventory,
                             FurnaceInputManager inputManager,
                             SmeltingManager smeltingManager,
                             FurnaceRenderCoordinator renderCoordinator) {
        this.game = game;
        this.inventory = inventory;
        this.inputManager = inputManager;
        this.smeltingManager = smeltingManager;
        this.renderCoordinator = renderCoordinator;
        this.hotbarScreen = new HotbarScreen(inventory);
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
        hotbarScreen.update(deltaTime);
        // Smelting is ticked by the AUTHORITATIVE (server-world) FurnaceStateRegistry; this
        // UI is bound to the client display registry, updated by BlockStateS2C echoes.

        // Slot intent: whatever mutation path the UI took (drag, split, place, take), the
        // per-frame dirty check catches it and ships the full slot snapshot to the server.
        // BlockStateS2C echoes then confirm/correct — including the smelting results.
        //
        // Echoes are NOT intent: the server streams state every cook tick, so an echo that
        // left before our last intent arrived restores the old slots locally. Re-sending that
        // would tell the server to put back what the player just took (duplicating shift-
        // clicked output). So when an echo landed since the last frame, first flush any edit
        // made before it (the pre-echo snapshot), then adopt the echoed slots as the baseline.
        if (visible && state != null) {
            syncSlots();
        }
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
        slotSink.send(state.getPos(), slots);
    }

    /** Receives slot snapshots bound for the server. */
    @FunctionalInterface
    interface SlotSink {
        void send(BlockPos pos, String slots);
    }

    /** Test seam: capture slot snapshots instead of sending them. */
    void setSlotSink(SlotSink sink) {
        this.slotSink = sink;
    }

    /* ── Input / rendering delegates ─────────────────────── */

    public void handleInput(int screenWidth, int screenHeight) {
        if (!visible) return;
        inputManager.handleMouseInput(screenWidth, screenHeight);
    }

    public void handleCloseRequest() {
        game.closeFurnaceScreen();
    }

    public void render(int screenWidth, int screenHeight) {
        if (!visible || renderCoordinator == null) return;
        renderCoordinator.render(screenWidth, screenHeight);
    }

    public void renderWithoutTooltips(int screenWidth, int screenHeight) {
        if (!visible || renderCoordinator == null) return;
        renderCoordinator.renderWithoutTooltips(screenWidth, screenHeight);
    }

    public void renderTooltipsOnly(int screenWidth, int screenHeight) {
        if (!visible || renderCoordinator == null) return;
        renderCoordinator.renderTooltipsOnly(screenWidth, screenHeight);
    }

    public void renderDraggedItemOnly(int screenWidth, int screenHeight) {
        if (!visible || renderCoordinator == null) return;
        renderCoordinator.renderDraggedItemOnly(screenWidth, screenHeight);
    }

    public void renderHotbar(int screenWidth, int screenHeight) {
        if (renderCoordinator != null) {
            renderCoordinator.renderHotbar(screenWidth, screenHeight);
        }
    }

    public void renderHotbarWithoutTooltips(int screenWidth, int screenHeight) {
        if (renderCoordinator != null) {
            renderCoordinator.renderHotbarWithoutTooltips(screenWidth, screenHeight);
        }
    }

    public void renderHotbarTooltipsOnly(int screenWidth, int screenHeight) {
        if (renderCoordinator != null) {
            renderCoordinator.renderHotbarTooltipsOnly(screenWidth, screenHeight);
        }
    }

    /* ── Accessors ───────────────────────────────────────── */

    public HotbarScreen getHotbarScreen() { return hotbarScreen; }
    public ItemStack getHoveredItemStack() { return hoveredItemStack; }
    public void setHoveredItemStack(ItemStack itemStack) { this.hoveredItemStack = itemStack; }

    public void setRenderCoordinator(FurnaceRenderCoordinator renderCoordinator) {
        this.renderCoordinator = renderCoordinator;
    }

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
