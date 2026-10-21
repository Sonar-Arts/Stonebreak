package com.stonebreak.ui.inventoryScreen.core;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemStack;
import com.stonebreak.network.packet.player.ContainerActionC2S;
import com.stonebreak.ui.runtime.contracts.ContainerSlots;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #334 (PR #410 review): on the inventory and crafting-table screens every local slot
 * mutation sends one action whose SERVER outcome equals the prediction — swaps carry their drag
 * origin, partial merges ship as MERGE with the remainder kept on the cursor, a stack taken from
 * the grid and put back is sent, middle-click sort is sent, an origin-less cursor stack is never
 * silently deleted, and closing a crafting table deposits the server cursor.
 */
@Tag("regression")
class ContainerSessionActionsTest {

    private static final int W = 1920;
    private static final int H = 1080;

    // ── inventory screen ────────────────────────────────────────────────────

    private final List<ContainerActionC2S> sent = new ArrayList<>();

    private InventoryInputManager inventoryScreen(InventoryFixtures.Player3 p) {
        InventoryInputManager in = InventoryFixtures.input(p);
        in.getSlotManager().actions.setCapture(sent::add);
        return in;
    }

    @Test
    void aSwapCarriesItsDragOrigin() {
        InventoryFixtures.Player3 p = InventoryFixtures.player("empty");
        p.inventory().setMainInventorySlot(0, new ItemStack(BlockType.DIRT, 5));
        p.inventory().setHotbarSlot(1, new ItemStack(BlockType.STONE, 3));
        InventoryInputManager in = inventoryScreen(p);

        click(in, "main:0");
        click(in, "hotbar:1");

        assertEquals(BlockType.DIRT.getId(), p.inventory().getHotbarSlot(1).getBlockTypeId());
        assertEquals(BlockType.STONE.getId(), p.inventory().getMainInventorySlot(0).getBlockTypeId());
        assertFalse(in.getDragState().isDragging());
        ContainerActionC2S swap = sent.get(sent.size() - 1);
        assertEquals(ContainerActionC2S.Action.SWAP, swap.action());
        assertEquals(ContainerActionC2S.Region.PLAYER_HOTBAR, swap.region());
        assertEquals(ContainerActionC2S.Region.PLAYER_MAIN, swap.originRegion(),
            "captured before the swap cleared the drag state");
        assertEquals(0, swap.originSlot());
    }

    @Test
    void aPartialMergeShipsAsMergeAndKeepsTheRemainderOnTheCursor() {
        InventoryFixtures.Player3 p = InventoryFixtures.player("empty");
        p.inventory().setMainInventorySlot(0, new ItemStack(BlockType.DIRT, 10));
        p.inventory().setMainInventorySlot(1, new ItemStack(BlockType.DIRT, 60));
        InventoryInputManager in = inventoryScreen(p);

        click(in, "main:0");
        click(in, "main:1");

        assertEquals(64, p.inventory().getMainInventorySlot(1).getCount());
        assertEquals(6, in.getDragState().draggedItemStack.getCount(), "the remainder stays carried");
        assertTrue(p.inventory().getMainInventorySlot(0).isEmpty());
        assertEquals(List.of(ContainerActionC2S.Action.TAKE_ALL, ContainerActionC2S.Action.MERGE),
            actions(), "one MERGE, no PUT_ALL of the remainder");
    }

    @Test
    void aPartialMergeIntoTheGridShipsAsMerge() {
        InventoryFixtures.Player3 p = InventoryFixtures.player("empty");
        p.inventory().setMainInventorySlot(0, new ItemStack(BlockType.DIRT, 10));
        p.grid().getCraftingInputSlots()[0] = new ItemStack(BlockType.DIRT, 60);
        InventoryInputManager in = inventoryScreen(p);

        click(in, "main:0");
        click(in, "craft:0");

        assertEquals(64, p.grid().getCraftingInputSlots()[0].getCount());
        assertEquals(6, in.getDragState().draggedItemStack.getCount());
        ContainerActionC2S merge = sent.get(sent.size() - 1);
        assertEquals(ContainerActionC2S.Action.MERGE, merge.action());
        assertEquals(ContainerActionC2S.Region.INV_CRAFT_GRID, merge.region());
        assertEquals(2, sent.size());
    }

    @Test
    void aFullSameKindSlotIsNotSwapped() {
        InventoryFixtures.Player3 p = InventoryFixtures.player("empty");
        p.inventory().setMainInventorySlot(0, new ItemStack(BlockType.STONE, 10));
        p.inventory().setMainInventorySlot(1, new ItemStack(BlockType.STONE, 64));
        InventoryInputManager in = inventoryScreen(p);

        click(in, "main:0");
        click(in, "main:1");

        assertTrue(actions().stream().noneMatch(a -> a == ContainerActionC2S.Action.SWAP),
            "the server refuses a SWAP onto a full same-kind stack: " + sent);
        assertEquals(64, p.inventory().getMainInventorySlot(1).getCount());
    }

    @Test
    void aGridStackPutBackIsSentToItsCell() {
        InventoryFixtures.Player3 p = InventoryFixtures.player("empty");
        p.grid().getCraftingInputSlots()[2] = new ItemStack(BlockType.DIRT, 3);
        InventoryInputManager in = inventoryScreen(p);

        click(in, "craft:2");
        click(in, "panel");

        assertEquals(3, p.grid().getCraftingInputSlots()[2].getCount());
        ContainerActionC2S back = sent.get(sent.size() - 1);
        assertEquals(ContainerActionC2S.Action.PUT_ALL, back.action());
        assertEquals(ContainerActionC2S.Region.INV_CRAFT_GRID, back.region());
        assertEquals(2, back.slot());
    }

    @Test
    void middleClickSortIsSent() {
        InventoryFixtures.Player3 p = InventoryFixtures.player("stocked");
        InventoryInputManager in = inventoryScreen(p);

        assertNull(ContainerSlots.click(in, "main:20", ContainerSlots.MIDDLE, false, W, H));

        assertEquals(List.of(ContainerActionC2S.Action.SORT), actions());
    }

    @Test
    void aCursorStackWithoutAnOriginIsKeptNotDeleted() {
        InventoryFixtures.Player3 p = InventoryFixtures.player("empty");
        InventoryInputManager in = inventoryScreen(p);
        // as InventorySyncApplier.adoptCarried leaves it: a stack with no origin slot
        in.getDragState().draggedItemStack = new ItemStack(BlockType.DIRT, 3);

        click(in, "panel");

        assertTrue(in.getDragState().isDragging(), "kept on the cursor (the server still carries it)");
        assertEquals(3, in.getDragState().draggedItemStack.getCount());
        assertTrue(sent.isEmpty(), "nothing moved, nothing sent");

        in.handleCloseWithDraggedItems();
        assertEquals(List.of(ContainerActionC2S.Action.DEPOSIT_CARRIED), actions(), "close hands it back");
    }

    // ── crafting table ──────────────────────────────────────────────────────

    private WorkbenchInputManager table(WorkbenchFixtures.Table t, WorkbenchController[] controller) {
        WorkbenchInputManager[] in = new WorkbenchInputManager[1];
        controller[0] = WorkbenchFixtures.controller(t, in);
        in[0].getSlotManager().actions.setCapture(sent::add);
        return in[0];
    }

    @Test
    void aTableGridStackPutBackIsSentToItsCell() {
        WorkbenchFixtures.Table t = WorkbenchFixtures.table("stocked"); // one wood in cell 4
        WorkbenchController[] c = new WorkbenchController[1];
        WorkbenchInputManager in = table(t, c);

        click(in, "craft:4");
        click(in, "panel");

        assertEquals(1, t.state().getSlots()[4].getCount(), "back in the table's grid");
        ContainerActionC2S back = sent.get(sent.size() - 1);
        assertEquals(ContainerActionC2S.Action.PUT_ALL, back.action());
        assertEquals(ContainerActionC2S.Region.WORKBENCH_GRID, back.region());
        assertEquals(4, back.slot());
    }

    @Test
    void aGridSourcedSwapSendsNoOriginAndKeepsTheDisplacedStackCarried() {
        WorkbenchFixtures.Table t = WorkbenchFixtures.table("stocked");
        WorkbenchController[] c = new WorkbenchController[1];
        WorkbenchInputManager in = table(t, c);

        click(in, "craft:4");
        click(in, "main:0"); // 64 dirt there

        assertEquals(BlockType.WOOD.getId(), t.inventory().getMainInventorySlot(0).getBlockTypeId());
        assertEquals(BlockType.DIRT.getId(), in.getDragState().draggedItemStack.getBlockTypeId(),
            "the server only knows player origins: the displaced stack stays carried");
        ContainerActionC2S swap = sent.get(sent.size() - 1);
        assertEquals(ContainerActionC2S.Action.SWAP, swap.action());
        assertEquals(ContainerActionC2S.Region.NONE, swap.originRegion());
        assertTrue(t.state().getSlots()[4].isEmpty(), "the grid cell is not written by the swap");
    }

    @Test
    void closingATableWithAGridStackOnTheCursorReturnsAndSendsIt() {
        WorkbenchFixtures.Table t = WorkbenchFixtures.table("stocked");
        WorkbenchController[] c = new WorkbenchController[1];
        WorkbenchInputManager in = table(t, c);

        click(in, "craft:4");
        c[0].close();

        assertEquals(1, t.state().getSlots()[4].getCount(), "the grid stays in the table");
        assertEquals(List.of(ContainerActionC2S.Action.TAKE_ALL, ContainerActionC2S.Action.PUT_ALL,
            ContainerActionC2S.Action.DEPOSIT_CARRIED), actions());
        assertEquals(ContainerActionC2S.Region.WORKBENCH_GRID, sent.get(1).region());
        assertTrue(sent.stream().noneMatch(a -> a.action() == ContainerActionC2S.Action.QUICK_MOVE),
            "the table's grid is never quick-moved out on close");
    }

    @Test
    void closingATableAfterAnyActionDepositsTheServerCursor() {
        WorkbenchFixtures.Table t = WorkbenchFixtures.table("stocked");
        WorkbenchController[] c = new WorkbenchController[1];
        WorkbenchInputManager in = table(t, c);

        click(in, "main:0");
        click(in, "main:1");
        c[0].close();

        assertEquals(List.of(ContainerActionC2S.Action.TAKE_ALL, ContainerActionC2S.Action.PUT_ALL,
            ContainerActionC2S.Action.DEPOSIT_CARRIED), actions());
    }

    @Test
    void closingAnUntouchedTableSendsNothing() {
        WorkbenchFixtures.Table t = WorkbenchFixtures.table("stocked");
        WorkbenchController[] c = new WorkbenchController[1];
        table(t, c);

        c[0].close();

        assertTrue(sent.isEmpty(), "" + sent);
    }

    private static void click(com.stonebreak.ui.inventoryScreen.handlers.ContainerSlotInput in, String slot) {
        assertNull(ContainerSlots.click(in, slot, ContainerSlots.LEFT, false, W, H), slot);
    }

    private List<Byte> actions() {
        return sent.stream().map(ContainerActionC2S::action).toList();
    }
}
