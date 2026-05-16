package com.stonebreak.ui.furnace.core;

import com.stonebreak.core.Game;
import com.stonebreak.crafting.SmeltingManager;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.ui.HotbarScreen;
import com.stonebreak.ui.furnace.renderers.FurnaceRenderCoordinator;

/**
 * Coordinates furnace UI operations and smelting state.
 *
 * <p>Owns the three furnace slots (ingredient, fuel, output), the smelting
 * progress bar, and delegates rendering/input to specialised collaborators.</p>
 */
public class FurnaceController {

    private final Game game;
    private final Inventory inventory;
    private final HotbarScreen hotbarScreen;
    private FurnaceInputManager inputManager;
    private final SmeltingManager smeltingManager;
    private FurnaceRenderCoordinator renderCoordinator;

    private boolean visible;

    // ── Smelting state ───────────────────────────────────────
    private ItemStack ingredient = new ItemStack(0, 0);  // item to smelt
    private ItemStack fuel       = new ItemStack(0, 0);  // fuel source
    private ItemStack output     = new ItemStack(0, 0);  // smelted result

    private int burnTimeRemaining = 0;   // ticks of fuel left
    private int cookProgress      = 0;   // ticks toward completing current smelt
    private boolean cooking       = false;

    // ── Tooltip ──────────────────────────────────────────────
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

    // ── Visibility ───────────────────────────────────────────

    public void open() {
        this.visible = true;
    }

    public void close() {
        if (inputManager != null) inputManager.handleCloseWithDraggedItems();
        /* return unsmelted ingredient + fuel back to inventory on close */
        returnToInventory();
        this.visible = false;
        clearSlots();
    }

    public void toggleVisibility() {
        if (visible) {
            close();
        } else {
            open();
        }
    }

    public boolean isVisible() {
        return visible;
    }

    // ── Per-frame tick ──────────────────────────────────────

    public void update(float deltaTime) {
        hotbarScreen.update(deltaTime);
        tickSmelting();
    }

    /**
     * Advances smelting progress by one game tick.
     */
    private void tickSmelting() {
        // Can only cook if: we have a valid ingredient, fuel > 0,
        // a matching smelting recipe, and output slot can accept it
        boolean canCook = (!ingredient.isEmpty())
                       && (!fuel.isEmpty())
                       && (burnTimeRemaining > 0)
                       && (smeltingManager.getRecipe(ingredient) != null)
                       && canAcceptOutput();

        if (canCook) {
            cooking = true;
            cookProgress++;

            if (cookProgress >= SmeltingManager.TICKS_PER_SMELT) {
                cookProgress = 0;
                completeSmelt();
            }
        } else {
            cooking = false;
        }

        // Tick down fuel
        if (cooking && burnTimeRemaining > 0) {
            burnTimeRemaining--;
            if (burnTimeRemaining <= 0) {
                fuel.clear();
            }
        }
    }

    private boolean canAcceptOutput() {
        if (output.isEmpty()) return true;
        // If output is not full, we can stack more on top
        return true; // always accept (player will need to collect manually)
    }

    private void completeSmelt() {
        var recipe = smeltingManager.getRecipe(ingredient);
        if (recipe == null) return;

        var recipeOutput = recipe.getOutput();

        if (output.isEmpty()) {
            output = recipeOutput.copy();
        } else if (output.canStackWith(recipeOutput)) {
            int canAdd = output.getMaxStackSize() - output.getCount();
            int toAdd = Math.min(canAdd, recipeOutput.getCount());
            output.incrementCount(toAdd);
            if (toAdd < recipeOutput.getCount()) {
                // Overflow — remaining drops to world (YAGNI: skip for now)
            }
        }

        // Consume one unit of ingredient
        ingredient.decrementCount(1);
        if (ingredient.getCount() <= 0) {
            ingredient.clear();
        }
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

    public HotbarScreen getHotbarScreen() {
        return hotbarScreen;
    }

    public ItemStack getHoveredItemStack() {
        return hoveredItemStack;
    }

    public void setHoveredItemStack(ItemStack itemStack) {
        this.hoveredItemStack = itemStack;
    }

    public void setRenderCoordinator(FurnaceRenderCoordinator renderCoordinator) {
        this.renderCoordinator = renderCoordinator;
    }

    /** Wire the input manager after construction (breaks circular dependency). */
    public void setInputManager(FurnaceInputManager inputManager) {
        this.inputManager = inputManager;
    }

    public SmeltingManager getSmeltingManager() {
        return smeltingManager;
    }

    /* ── Furnace slot accessors ──────────────────────────── */

    public ItemStack getIngredientSlot() { return ingredient; }
    public void setIngredientSlot(ItemStack stack) { this.ingredient = (stack != null && !stack.isEmpty()) ? stack : new ItemStack(0, 0); }

    public ItemStack getFuelSlot() { return fuel; }
    public void setFuelSlot(ItemStack stack) { this.fuel = (stack != null && !stack.isEmpty()) ? stack : new ItemStack(0, 0); }

    public ItemStack getOutputSlot() { return output; }
    public void setOutputSlot(ItemStack stack) { this.output = (stack != null && !stack.isEmpty()) ? stack : new ItemStack(0, 0); }

    public int getBurnTimeRemaining() { return burnTimeRemaining; }
    public void setBurnTimeRemaining(int ticks) { this.burnTimeRemaining = ticks; }

    public int getCookProgress() { return cookProgress; }
    public boolean isCooking() { return cooking; }

    /** Returns 0..1 progress ratio for the progress bar. */
    public float getCookProgressRatio() {
        return (float) cookProgress / SmeltingManager.TICKS_PER_SMELT;
    }

    /** Returns 0..1 fuel ratio for the fuel bar. */
    public float getFuelRatio() {
        if (fuel.isEmpty()) return 0f;
        int maxBurn = smeltingManager.getBurnTimePerUnit(fuel.getItem());
        if (maxBurn <= 0) return 0f;
        return Math.min(1f, (float) burnTimeRemaining / (maxBurn * Math.max(fuel.getCount(), 1)));
    }

    /** Clear all furnace slots. */
    private void clearSlots() {
        ingredient.clear();
        fuel.clear();
        output.clear();
        burnTimeRemaining = 0;
        cookProgress = 0;
        cooking = false;
    }

    /** Return unsmelted ingredient and fuel stacks back to player inventory. */
    private void returnToInventory() {
        if (!ingredient.isEmpty()) {
            inventory.addItem(ingredient);
            ingredient.clear();
        }
        if (!fuel.isEmpty()) {
            inventory.addItem(fuel);
            fuel.clear();
        }
        /* output stays — player must collect it before closing */
    }
}
