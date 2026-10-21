package com.stonebreak.ui.furnace.core;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.furnace.FurnaceState;
import com.stonebreak.crafting.SmeltingManager;
import com.stonebreak.crafting.SmeltingRecipe;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.network.packet.player.ContainerActionC2S;
import com.stonebreak.ui.inventoryScreen.handlers.InventoryDragDropHandler;
import com.stonebreak.ui.runtime.contracts.ContainerSlots;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.stonebreak.ui.support.UiTestFixtures.emptyInventory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #334 (PR #410 review): in a live session every local furnace-screen mutation sends one
 * action whose SERVER outcome equals the local prediction. A capture on the sink stands in for
 * the session (the sink is then "live", as with a real session).
 */
@Tag("regression")
class FurnaceSessionActionsTest {

    private static final int W = 1920;
    private static final int H = 1080;

    private final List<ContainerActionC2S> sent = new ArrayList<>();
    private Inventory inventory;
    private FurnaceController controller;
    private FurnaceInputManager input;
    private FurnaceState furnace;

    @BeforeEach
    void setUp() {
        SmeltingManager smelting = new SmeltingManager();
        smelting.registerFuel(BlockType.COAL_ORE, 1600);
        smelting.registerFuel(BlockType.WOOD, 300);
        smelting.registerRecipe(new SmeltingRecipe("t:ore", new ItemStack(BlockType.IRON_ORE, 1),
            new ItemStack(BlockType.STONE, 1)));
        smelting.registerRecipe(new SmeltingRecipe("t:sand", new ItemStack(BlockType.SAND, 1),
            new ItemStack(BlockType.STONE, 1)));
        smelting.registerRecipe(new SmeltingRecipe("t:wood", new ItemStack(BlockType.WOOD, 1),
            new ItemStack(BlockType.STONE, 1)));
        inventory = emptyInventory();
        controller = new FurnaceController(null, null, smelting);
        input = new FurnaceInputManager(null, inventory, controller);
        controller.setInputManager(input);
        controller.setFurnaceAt(p -> true);
        furnace = new FurnaceState(new BlockPos(0, 64, 0));
        controller.bind(furnace);
        controller.actions().setCapture(sent::add);
    }

    @Test
    void aBarePanelClickPutsAFurnaceStackBackAndSendsIt() {
        furnace.setFuel(new ItemStack(BlockType.COAL_ORE, 5));
        click("fuel");
        click("panel");

        assertEquals(5, furnace.getFuel().getCount(), "back in its slot");
        assertFalse(input.getDragState().isDragging());
        assertEquals(2, sent.size(), "pickup + put-back: " + sent);
        assertAction(sent.get(1), ContainerActionC2S.Action.PUT_ALL, ContainerActionC2S.Region.FURNACE_FUEL);
    }

    @Test
    void aBarePanelClickMergesIntoAStackableFurnaceSlotAndSendsMerge() {
        furnace.setIngredient(new ItemStack(BlockType.IRON_ORE, 6));
        ContainerSlots.click(input, "ingredient", ContainerSlots.RIGHT, false, W, H); // TAKE_ONE
        click("panel");

        assertEquals(6, furnace.getIngredient().getCount(), "the one taken merges back");
        assertFalse(input.getDragState().isDragging());
        assertAction(sent.get(sent.size() - 1), ContainerActionC2S.Action.MERGE,
            ContainerActionC2S.Region.FURNACE_ING);
    }

    @Test
    void anOutputStackTossedBeyondThePanelShipsAsDropCarried() throws Exception {
        furnace.setOutput(new ItemStack(BlockType.STONE, 3));
        List<ItemStack> localDrops = new ArrayList<>();
        try (AutoCloseable ignored = InventoryDragDropHandler.redirectWorldDrops(localDrops::add)) {
            click("output");
            click("outside");
        }

        assertTrue(furnace.getOutput().isEmpty(), "the output slot is TAKE-only: never written back");
        assertFalse(input.getDragState().isDragging());
        assertTrue(localDrops.isEmpty(), "no local DropItemC2S toss (it would debit the 36 slots)");
        ContainerActionC2S last = sent.get(sent.size() - 1);
        assertEquals(ContainerActionC2S.Action.DROP_CARRIED, last.action());
        assertEquals(3, last.count());
    }

    @Test
    void closingWithAnOutputStackDepositsItOnce() {
        furnace.setOutput(new ItemStack(BlockType.STONE, 3));
        click("output");

        controller.close();

        assertTrue(furnace.getOutput().isEmpty(), "never PUT_ALL into the output (the server refuses it)");
        assertEquals(3, count(BlockType.STONE), "into the inventory (prediction of DEPOSIT_CARRIED)");
        assertEquals(List.of(ContainerActionC2S.Action.TAKE_ALL, ContainerActionC2S.Action.DEPOSIT_CARRIED),
            sent.stream().map(ContainerActionC2S::action).toList());
    }

    @Test
    void closingAfterAnyActionAlwaysEndsWithADeposit() {
        inventory.setMainInventorySlot(0, new ItemStack(BlockType.IRON_ORE, 4));
        click("main:0");
        click("ingredient");
        int before = sent.size();

        controller.close();

        assertEquals(before + 1, sent.size());
        assertEquals(ContainerActionC2S.Action.DEPOSIT_CARRIED, sent.get(sent.size() - 1).action(),
            "a refused action may have left the server cursor full");
    }

    @Test
    void anInventorySourcedIngredientSwapSendsTheDisplacedStackToItsEmptyOrigin() {
        furnace.setIngredient(new ItemStack(BlockType.IRON_ORE, 2));
        inventory.setMainInventorySlot(5, new ItemStack(BlockType.SAND, 3));
        click("main:5");
        click("ingredient");

        assertEquals(BlockType.SAND.getId(), furnace.getIngredient().getBlockTypeId());
        assertEquals(BlockType.IRON_ORE.getId(), inventory.getMainInventorySlot(5).getBlockTypeId(),
            "the server's swap writes the displaced stack into the empty origin");
        assertFalse(input.getDragState().isDragging(), "...and empties the cursor");
        ContainerActionC2S swap = sent.get(sent.size() - 1);
        assertAction(swap, ContainerActionC2S.Action.SWAP, ContainerActionC2S.Region.FURNACE_ING);
        assertEquals(ContainerActionC2S.Region.PLAYER_MAIN, swap.originRegion());
        assertEquals(5, swap.originSlot());
    }

    @Test
    void aFurnaceSourcedIngredientSwapKeepsTheDisplacedStackCarried() {
        furnace.setFuel(new ItemStack(BlockType.WOOD, 2));
        furnace.setIngredient(new ItemStack(BlockType.IRON_ORE, 1));
        click("fuel");
        click("ingredient");

        assertEquals(BlockType.WOOD.getId(), furnace.getIngredient().getBlockTypeId());
        assertTrue(input.getDragState().isDragging());
        assertEquals(BlockType.IRON_ORE.getId(), input.getDragState().draggedItemStack.getBlockTypeId());
        ContainerActionC2S swap = sent.get(sent.size() - 1);
        assertAction(swap, ContainerActionC2S.Action.SWAP, ContainerActionC2S.Region.FURNACE_ING);
        assertEquals(ContainerActionC2S.Region.NONE, swap.originRegion(), "no player origin: stays carried");
    }

    @Test
    void anInventorySwapNeverOverwritesAnOccupiedOrigin() {
        inventory.setMainInventorySlot(5, new ItemStack(BlockType.SAND, 4));
        inventory.setMainInventorySlot(7, new ItemStack(BlockType.DIRT, 2));
        click("main:5");
        ContainerSlots.press(input, "main:5", ContainerSlots.RIGHT, false, W, H); // one back: DROP_ONE
        ContainerSlots.release(input, W, H);
        click("main:7");

        assertEquals(1, inventory.getMainInventorySlot(5).getCount(), "the origin's sand survives");
        assertEquals(BlockType.SAND.getId(), inventory.getMainInventorySlot(5).getBlockTypeId());
        assertEquals(3, inventory.getMainInventorySlot(7).getCount());
        assertEquals(BlockType.DIRT.getId(), input.getDragState().draggedItemStack.getBlockTypeId(),
            "occupied origin: the displaced dirt stays carried, as on the server");
    }

    @Test
    void aSmeltableOnlyIngredientSlotRefusesOtherItemsWithoutSendingToIt() {
        inventory.setMainInventorySlot(0, new ItemStack(BlockType.DIRT, 4));
        click("main:0");
        click("ingredient");

        assertTrue(furnace.getIngredient().isEmpty(), "dirt is not smeltable");
        assertEquals(4, inventory.getMainInventorySlot(0).getCount(), "returned to its slot");
        assertTrue(sent.stream().noneMatch(a -> a.region() == ContainerActionC2S.Region.FURNACE_ING), "" + sent);
        assertAction(sent.get(sent.size() - 1), ContainerActionC2S.Action.PUT_ALL,
            ContainerActionC2S.Region.PLAYER_MAIN);
    }

    @Test
    void aFullSameKindSlotIsNeitherSwappedNorSent() {
        inventory.setMainInventorySlot(0, new ItemStack(BlockType.STONE, 10));
        inventory.setMainInventorySlot(3, new ItemStack(BlockType.STONE, 64));
        click("main:0");
        int before = sent.size();
        click("main:3");

        assertEquals(before, sent.size(), "no SWAP (the server refuses it) and no no-op MERGE");
        assertEquals(10, input.getDragState().draggedItemStack.getCount());
        assertEquals(64, inventory.getMainInventorySlot(3).getCount());
    }

    @Test
    void aRightDragNeverOverfillsAFullSlot() {
        inventory.setMainInventorySlot(0, new ItemStack(BlockType.STONE, 5));
        inventory.setMainInventorySlot(9, new ItemStack(BlockType.STONE, 64));
        click("main:0");
        int before = sent.size();
        ContainerSlots.press(input, "main:9", ContainerSlots.RIGHT, false, W, H);
        ContainerSlots.release(input, W, H);

        assertEquals(64, inventory.getMainInventorySlot(9).getCount(), "the server's DROP_ONE needs room");
        assertEquals(5, input.getDragState().draggedItemStack.getCount());
        assertEquals(before, sent.size(), "nothing placed, nothing sent");
    }

    private void click(String slot) {
        assertEquals(null, ContainerSlots.click(input, slot, ContainerSlots.LEFT, false, W, H));
    }

    private static void assertAction(ContainerActionC2S a, byte action, byte region) {
        assertEquals(action, a.action(), "action of " + a);
        assertEquals(region, a.region(), "region of " + a);
    }

    private int count(BlockType type) {
        int n = 0;
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            ItemStack s = inventory.getHotbarSlot(i);
            n += s.getBlockTypeId() == type.getId() ? s.getCount() : 0;
        }
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            ItemStack s = inventory.getMainInventorySlot(i);
            n += s.getBlockTypeId() == type.getId() ? s.getCount() : 0;
        }
        return n;
    }
}
