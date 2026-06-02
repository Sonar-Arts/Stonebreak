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
        this.state = (registry != null) ? registry.getOrCreate(pos) : new FurnaceState(pos);
        this.visible = true;
    }

    public void close() {
        if (inputManager != null) inputManager.handleCloseWithDraggedItems();
        // Do NOT dump contents or clear state — the registry owns it and keeps ticking.
        this.visible = false;
        this.state = null;
    }

    public boolean isVisible() {
        return visible;
    }

    public void update(float deltaTime) {
        hotbarScreen.update(deltaTime);
        // Smelting is ticked by FurnaceStateRegistry — no UI-side tick here.
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
