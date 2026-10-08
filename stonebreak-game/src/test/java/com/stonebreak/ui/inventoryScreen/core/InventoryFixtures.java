package com.stonebreak.ui.inventoryScreen.core;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.crafting.CraftingManager;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.player.CharacterStats;
import com.stonebreak.player.Player;
import com.stonebreak.ui.TabStripLayout;
import com.stonebreak.ui.support.UiTestFixtures;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The player both sides of the inventory screen's fidelity gate (#300) open: an inventory, a 2x2
 * crafting grid and a character per variant, plus the legacy geometry oracle. Block items only (their
 * icons are both renderers' GL phase, ledger hard visual 2).
 *
 * <p>Contents: {@code empty} (a fresh level 1 character, no vitals); {@code stocked} (stacks with
 * counts, the hotbar's selected slot 2, one wood in the grid making planks so Craft All shows, a
 * level 3 character with 40 xp, vitals 17/20 HP, 6/10 MP, 30/40 SP and background bonuses on its
 * ability scores). Not a test class.
 */
public final class InventoryFixtures {

    private InventoryFixtures() {
    }

    /** One player: what the screen is bound to. */
    public record Player3(Inventory inventory, InventoryCraftingManager grid, CharacterStats stats) {
    }

    public static Player3 player(String content) {
        Inventory inv = UiTestFixtures.emptyInventory();
        InventoryCraftingManager grid = new InventoryCraftingManager(recipes());
        CharacterStats stats;
        switch (content) {
            case "empty" -> stats = new CharacterStats(null);
            case "stocked" -> {
                inv.setMainInventorySlot(0, new ItemStack(BlockType.DIRT, 64));
                inv.setMainInventorySlot(4, new ItemStack(BlockType.STONE, 7));
                inv.setMainInventorySlot(13, new ItemStack(BlockType.SAND, 1));
                inv.setMainInventorySlot(26, new ItemStack(BlockType.COBBLESTONE, 32));
                inv.setHotbarSlot(0, new ItemStack(BlockType.WOOD, 12));
                inv.setHotbarSlot(2, new ItemStack(BlockType.GRAVEL, 5));
                inv.setHotbarSlot(8, new ItemStack(BlockType.DIRT, 2));
                inv.setSelectedHotbarSlotIndex(2);
                grid.getCraftingInputSlots()[3] = new ItemStack(BlockType.WOOD, 1);
                Player p = mock(Player.class);
                when(p.getHealth()).thenReturn(17f);
                when(p.getMaxHealth()).thenReturn(20f);
                when(p.getMana()).thenReturn(6f);
                when(p.getMaxMana()).thenReturn(10f);
                when(p.getStamina()).thenReturn(30f);
                when(p.getMaxStamina()).thenReturn(40f);
                stats = new CharacterStats(p);
                stats.applyBackgroundBonuses(new int[]{4, 2, 3, 0, -2, 1});
                stats.addXp(40);
            }
            default -> throw new IllegalArgumentException("inventory content must be empty or stocked: " + content);
        }
        grid.updateCraftingOutput();
        return new Player3(inv, grid, stats);
    }

    /** One wood makes four planks (the same recipe as the crafting table's fixture). */
    public static CraftingManager recipes() {
        return WorkbenchFixtures.recipes();
    }

    /** The screen's slot rules over {@code p}. */
    public static InventoryInputManager input(Player3 p) {
        return new InventoryInputManager(null, p.inventory(), new InventorySlotManager(p.inventory(), p.grid()), p.grid());
    }

    /**
     * The legacy renderer's geometry at the current UI scale: {@code panel} (all three columns),
     * {@code tab0..4}, {@code craft0..3}, {@code output}, {@code main0..26}, {@code hot0..8}, the buttons
     * {@code recipes}, {@code sort} and (while the grid makes something) {@code craftall}, and the left
     * column's {@code equip0..9} and {@code status0..5}.
     */
    public static Map<String, float[]> rects(int w, int h, boolean craftAll) {
        float s = com.stonebreak.config.Settings.getInstance().getUiScale();
        InventoryLayoutCalculator.InventoryLayout3Col l3 = InventoryLayoutCalculator.calculateThreeColumnLayout(w, h);
        InventoryLayoutCalculator.InventoryLayout l = l3.center;
        int ss = InventoryLayoutCalculator.getSlotSize();
        int pad = InventoryLayoutCalculator.getSlotPadding();
        int pitch = ss + pad;
        Map<String, float[]> out = new LinkedHashMap<>();
        out.put("panel", new float[]{l3.panelStartX, l3.panelStartY, l3.totalPanelWidth, l3.totalPanelHeight});
        int tw = TabStripLayout.tabWidth();
        int th = TabStripLayout.tabHeight();
        for (int i = 0; i < TabStripLayout.TAB_COUNT; i++) {
            out.put("tab" + i, new float[]{TabStripLayout.startX(w) + i * TabStripLayout.stride(),
                TabStripLayout.tabY(l3.panelStartY), tw, th});
        }
        for (int i = 0; i < 4; i++) {
            out.put("craft" + i, new float[]{l.craftingElementsStartX + (i % 2) * pitch,
                l.craftingGridStartY + (i / 2) * pitch, ss, ss});
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
        leftColumn(out, l3, s);
        return out;
    }

    /** The left column's slots, transcribing InventoryRenderCoordinator.drawLeftColumn's float cursor. */
    private static void leftColumn(Map<String, float[]> out, InventoryLayoutCalculator.InventoryLayout3Col l3, float s) {
        float padX = 12f * s;
        float innerW = l3.leftColW - padX * 2;
        float y = l3.leftColY + 14f * s;
        y = y + 14f * s + 4f * s + Math.round(10 * s);          // level widget: label line, gap, bar
        y += 10f * s;
        y = y + 14f * s + 4f * s + 8f * s;                       // EQUIPMENT header
        int slot = Math.round(28 * s);
        int gap = Math.round(8 * s);
        int stride = Math.round(48 * s);
        float gridX = l3.leftColX + (l3.leftColW - (2 * slot + gap)) / 2f;
        for (int i = 0; i < 10; i++) {
            out.put("equip" + i, new float[]{gridX + (i % 2) * (slot + gap), y + (i / 2) * stride, slot, slot});
        }
        y = y + 5 * stride + 2f * s;
        y += 10f * s;
        y = y + 14f * s + 4f * s + 8f * s;                       // VITALS header
        int barH = Math.round(20 * s);
        int barGap = Math.round(6 * s);
        y = y + barH + barGap + barH + barGap + barH;
        y += 10f * s;
        y = y + 14f * s + 4f * s + 8f * s;                       // STATUS header
        int sGap = Math.round(4 * s);
        int sPx = Math.min(Math.round(28 * s), (int) ((innerW - 5 * sGap) / 6f));
        for (int i = 0; i < 6; i++) {
            out.put("status" + i, new float[]{l3.leftColX + padX + i * (sPx + sGap), y, sPx, sPx});
        }
    }

    /** {@code <content>[-hover-<target>]} → the content. */
    public static String content(String variant) {
        return WorkbenchFixtures.content(variant);
    }

    public static String hover(String variant) {
        return WorkbenchFixtures.hover(variant);
    }
}
