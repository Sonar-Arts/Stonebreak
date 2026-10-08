package com.stonebreak.ui.inventoryScreen.core;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.workbench.WorkbenchState;
import com.stonebreak.crafting.CraftingManager;
import com.stonebreak.crafting.Recipe;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.ui.support.UiTestFixtures;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The crafting table both sides of the workbench's fidelity gate (#300) open: an inventory, a table
 * grid and a crafting manager per variant, plus the legacy geometry oracle. Block items only: their
 * icons are the GL phase of both renderers (ledger hard visual 2), so the pixels compared are the
 * chrome, slot frames, counts, buttons and tooltip.
 *
 * <p>Contents: {@code empty}; {@code stocked} (stacks with counts across the inventory, the
 * hotbar's selected slot 2, and one wood in the table: its planks show in the output slot, which
 * also shows the Craft All button). Must live in this package for {@link WorkbenchController#bind}.
 * Not a test class.
 */
public final class WorkbenchFixtures {

    private WorkbenchFixtures() {
    }

    /** One table: what the screen is bound to. */
    public record Table(Inventory inventory, WorkbenchState state, CraftingManager recipes) {
    }

    /** A crafting manager that knows one recipe: one wood makes four planks. */
    public static CraftingManager recipes() {
        CraftingManager m = new CraftingManager();
        m.registerRecipe(new Recipe("planks", List.of(List.of(new ItemStack(BlockType.WOOD, 1))),
            new ItemStack(BlockType.WOOD_PLANKS, 4)));
        return m;
    }

    public static Table table(String content) {
        Inventory inv = UiTestFixtures.emptyInventory();
        WorkbenchState state = new WorkbenchState(new BlockPos(0, 64, 0));
        switch (content) {
            case "empty" -> { }
            case "stocked" -> {
                inv.setMainInventorySlot(0, new ItemStack(BlockType.DIRT, 64));
                inv.setMainInventorySlot(4, new ItemStack(BlockType.STONE, 7));
                inv.setMainInventorySlot(13, new ItemStack(BlockType.SAND, 1));
                inv.setMainInventorySlot(26, new ItemStack(BlockType.COBBLESTONE, 32));
                inv.setHotbarSlot(0, new ItemStack(BlockType.WOOD, 12));
                inv.setHotbarSlot(2, new ItemStack(BlockType.GRAVEL, 5));
                inv.setHotbarSlot(8, new ItemStack(BlockType.DIRT, 2));
                inv.setSelectedHotbarSlotIndex(2);
                state.getSlots()[4] = new ItemStack(BlockType.WOOD, 1);
            }
            default -> throw new IllegalArgumentException("workbench content must be empty or stocked: " + content);
        }
        return new Table(inv, state, recipes());
    }

    /** A screen controller bound to {@code t}: its crafting output already computed. */
    public static WorkbenchController controller(Table t, WorkbenchInputManager[] input) {
        InventoryCraftingManager grid = new InventoryCraftingManager(t.recipes(),
            InventoryLayoutCalculator.getWorkbenchCraftingGridSize());
        WorkbenchInputManager in = new WorkbenchInputManager(null, t.inventory(),
            new InventorySlotManager(t.inventory(), grid), grid);
        WorkbenchController c = new WorkbenchController(null, t.inventory(), in, grid, null);
        c.bind(t.state());
        grid.updateCraftingOutput();
        input[0] = in;
        return c;
    }

    /** {@code <content>[-hover-<target>]} → the content. */
    public static String content(String variant) {
        int i = variant.indexOf("-hover-");
        return i < 0 ? variant : variant.substring(0, i);
    }

    /** The hover target of a variant ({@code craft4}, {@code output}, {@code mainN}, {@code hotN}, a button), or "". */
    public static String hover(String variant) {
        int i = variant.indexOf("-hover-");
        return i < 0 ? "" : variant.substring(i + "-hover-".length());
    }

    /**
     * The legacy renderer's geometry at the current UI scale: {@code panel}, {@code craft0..8},
     * {@code output}, {@code main0..26}, {@code hot0..8} and the buttons {@code recipes},
     * {@code sort} and (while the grid makes something) {@code craftall}.
     */
    public static Map<String, float[]> rects(int w, int h, boolean craftAll) {
        InventoryLayoutCalculator.InventoryLayout l = InventoryLayoutCalculator.calculateWorkbenchLayout(w, h);
        int ss = InventoryLayoutCalculator.getSlotSize();
        int pad = InventoryLayoutCalculator.getSlotPadding();
        int pitch = ss + pad;
        Map<String, float[]> out = new LinkedHashMap<>();
        out.put("panel", new float[]{l.panelStartX, l.panelStartY, l.inventoryPanelWidth, l.inventoryPanelHeight});
        for (int i = 0; i < 9; i++) {
            out.put("craft" + i, new float[]{l.craftingElementsStartX + (i % 3) * pitch,
                l.craftingGridStartY + (i / 3) * pitch, ss, ss});
        }
        out.put("output", new float[]{l.outputSlotX, l.outputSlotY, ss, ss});
        int gx = InventoryLayoutCalculator.gridStartX(l);
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            out.put("main" + i, new float[]{gx + (i % 9) * pitch, l.mainInvContentStartY + pad + (i / 9) * pitch, ss, ss});
        }
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            out.put("hot" + i, new float[]{gx + i * pitch, l.hotbarRowY, ss, ss});
        }
        InventoryInputManager buttons = new InventoryInputManager(null, null, null, null);
        buttons.updateRecipeButtonBoundsForRendering(l);
        out.put("recipes", new float[]{buttons.getRecipeButtonX(), buttons.getRecipeButtonY(),
            buttons.getRecipeButtonWidth(), buttons.getRecipeButtonHeight()});
        buttons.updateSortButtonBoundsForRendering(l);
        out.put("sort", new float[]{buttons.getSortButtonX(), buttons.getSortButtonY(),
            buttons.getSortButtonWidth(), buttons.getSortButtonHeight()});
        if (craftAll) {
            buttons.updateCraftAllButtonBoundsForRendering(l);
            out.put("craftall", new float[]{buttons.getCraftAllButtonX(), buttons.getCraftAllButtonY(),
                buttons.getCraftAllButtonWidth(), buttons.getCraftAllButtonHeight()});
        }
        return out;
    }
}
