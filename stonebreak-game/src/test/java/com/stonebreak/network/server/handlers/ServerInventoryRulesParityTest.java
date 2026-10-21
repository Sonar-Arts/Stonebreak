package com.stonebreak.network.server.handlers;

import com.openmason.engine.net.server.ServerConnection;
import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.furnace.FurnaceState;
import com.stonebreak.blocks.furnace.FurnaceStateRegistry;
import com.stonebreak.blocks.workbench.WorkbenchState;
import com.stonebreak.blocks.workbench.WorkbenchStateRegistry;
import com.stonebreak.crafting.CraftingManager;
import com.stonebreak.crafting.Recipe;
import com.stonebreak.crafting.SmeltingManager;
import com.stonebreak.crafting.SmeltingRecipe;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.ItemDrop;
import com.stonebreak.network.packet.player.ContainerActionC2S;
import com.stonebreak.network.packet.player.DropItemC2S;
import com.stonebreak.network.server.ServerInventory;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerPlayerDataFactory;
import com.stonebreak.network.server.ServerWorldContext;
import com.stonebreak.world.World;
import com.stonebreak.world.save.model.PlayerData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Review fixes for the server-authoritative inventory (issue #334): the server's slot rules
 * must produce exactly what the client predicts (shift-click, gather, sort, stacking), the
 * 2×2 grid and the cursor are returned on close/leave and never mutated by the periodic
 * save, a toss keeps the tossed stack's state, and no action can address a region that
 * does not belong to its container.
 */
class ServerInventoryRulesParityTest {

    private static final BlockPos FURNACE_POS = new BlockPos(10, 64, 10);
    private static final BlockPos BENCH_POS = new BlockPos(12, 64, 10);
    private static final String WATER = ItemType.BUCKET_STATE_WATER;
    private static final String EMPTY = ItemType.BUCKET_STATE_EMPTY;

    private ServerPlayer sp;
    private ServerWorldContext ctx;
    private EntityManager em;
    private FurnaceState furnace;
    private WorkbenchState workbench;
    private ServerPlayerHandler handler;
    private ContainerActionProcessor processor;

    @BeforeEach
    void setUp() {
        sp = new ServerPlayer(mock(ServerConnection.class), 7);
        sp.updateState(FURNACE_POS.x() + 0.5f, FURNACE_POS.y() + 1f, FURNACE_POS.z() + 0.5f, 0f, 0f);

        World world = mock(World.class);
        furnace = new FurnaceState(FURNACE_POS);
        FurnaceStateRegistry furnaces = new FurnaceStateRegistry(null) {
            @Override
            public FurnaceState getOrCreate(BlockPos pos) {
                return furnace;
            }
        };
        WorkbenchStateRegistry benches = new WorkbenchStateRegistry();
        workbench = benches.getOrCreate(BENCH_POS);
        when(world.getFurnaceRegistry()).thenReturn(furnaces);
        when(world.getWorkbenchRegistry()).thenReturn(benches);
        when(world.getBlockAt(FURNACE_POS.x(), FURNACE_POS.y(), FURNACE_POS.z())).thenReturn(BlockType.FURNACE);
        when(world.getBlockAt(BENCH_POS.x(), BENCH_POS.y(), BENCH_POS.z())).thenReturn(BlockType.WORKBENCH);
        em = Mockito.spy(new EntityManager(world));
        ctx = mock(ServerWorldContext.class);
        when(ctx.world()).thenReturn(world);
        when(ctx.entityManager()).thenReturn(em);

        handler = new ServerPlayerHandler();
        CraftingManager recipes = new CraftingManager();
        recipes.registerRecipe(new Recipe("test_planks",
                List.of(List.of(new ItemStack(BlockType.WOOD, 1))),
                new ItemStack(ItemType.STICK, 4)));
        SmeltingManager smelting = new SmeltingManager();
        smelting.registerFuel(BlockType.WOOD, 80);
        smelting.registerRecipe(new SmeltingRecipe(
                "test_smelt", new ItemStack(BlockType.WOOD, 1), new ItemStack(ItemType.STICK, 1)));
        processor = new ContainerActionProcessor(recipes, smelting);
    }

    private static ContainerActionC2S act(byte kind, BlockPos pos, byte region, int slot, byte action) {
        return new ContainerActionC2S(pos.x(), pos.y(), pos.z(), kind, region, (byte) slot, action,
                (short) 0, false);
    }

    private static ContainerActionC2S inv(byte region, int slot, byte action) {
        return act(ContainerActionC2S.ContainerKind.INVENTORY, new BlockPos(0, 0, 0), region, slot, action);
    }

    private static ItemStack stone(int n) {
        return new ItemStack(BlockType.STONE, n);
    }

    private void fillWithStone() {
        for (int i = 0; i < ServerInventory.TOTAL_SLOTS; i++) {
            sp.inventory().setSlot(i, stone(64));
        }
    }

    // ─── Finding 1: the 2×2 grid never strands ───────────────────────────────────

    @Test
    void quickMoveOfAStackOfOneFromTheGridEmptiesTheCell() {
        sp.inventory().setCraftSlot(2, new ItemStack(ItemType.STICK, 1));

        assertTrue(processor.apply(sp, inv(ContainerActionC2S.Region.INV_CRAFT_GRID, 2,
                ContainerActionC2S.Action.QUICK_MOVE), ctx));

        assertTrue(sp.inventory().craftSlot(2).isEmpty());
        assertEquals(1, sp.inventory().countOf(ItemType.STICK.getId()));
        verify(em, never()).addEntity(any());
    }

    @Test
    void quickMoveFromTheGridIntoAFullInventoryIsRefusedAndKeepsTheCell() {
        fillWithStone();
        sp.inventory().setCraftSlot(0, new ItemStack(ItemType.STICK, 1));

        assertFalse(processor.apply(sp, inv(ContainerActionC2S.Region.INV_CRAFT_GRID, 0,
                ContainerActionC2S.Action.QUICK_MOVE), ctx));

        assertEquals(1, sp.inventory().craftSlot(0).getCount(), "client keeps it too: nothing fit");
        verify(em, never()).addEntity(any());
    }

    @Test
    void inventoryCloseDepositReturnsCursorAndGridDroppingOnlyTheOverflow() {
        fillWithStone();
        sp.inventory().setSlot(35, new ItemStack(ItemType.STICK, 63));
        sp.inventory().setCarried(new ItemStack(ItemType.STICK, 1));      // fits (64th)
        sp.inventory().setCraftSlot(3, new ItemStack(ItemType.STICK, 1)); // stack of 1, no room

        assertTrue(processor.apply(sp, inv(ContainerActionC2S.Region.PLAYER_HOTBAR, 0,
                ContainerActionC2S.Action.DEPOSIT_CARRIED), ctx));

        assertTrue(sp.inventory().carried().isEmpty());
        assertTrue(sp.inventory().craftSlot(3).isEmpty(), "the grid was returned on close");
        assertEquals(64, sp.inventory().slot(35).getCount(), "the cursor went in first");
        verify(em).addEntity(argThat(e -> e instanceof ItemDrop d && d.getStackCount() == 1));
    }

    @Test
    void furnaceCloseDepositLeavesTheInventoryGridAlone() {
        sp.inventory().setCraftSlot(0, new ItemStack(ItemType.STICK, 2));
        sp.inventory().setCarried(new ItemStack(ItemType.STICK, 1));

        assertTrue(processor.apply(sp, act(ContainerActionC2S.ContainerKind.FURNACE, FURNACE_POS,
                ContainerActionC2S.Region.PLAYER_HOTBAR, 0, ContainerActionC2S.Action.DEPOSIT_CARRIED), ctx));

        assertEquals(2, sp.inventory().craftSlot(0).getCount());
        assertEquals(1, sp.inventory().countOf(ItemType.STICK.getId()));
    }

    @Test
    void leaveReturnHelperDrainsGridAndCursorIntoTheSlots() {
        sp.inventory().setCarried(new ItemStack(ItemType.STICK, 3));
        sp.inventory().setCraftSlot(0, new ItemStack(ItemType.STICK, 1));
        sp.inventory().setCraftSlot(1, stone(5));

        assertTrue(ServerPlayerHandler.returnHeldToInventoryOrWorld(sp, sp.inventory(), ctx));

        assertEquals(4, sp.inventory().countOf(ItemType.STICK.getId()));
        assertEquals(5, sp.inventory().countOf(BlockType.STONE.getId()));
        assertTrue(sp.inventory().carried().isEmpty());
        for (int i = 0; i < ServerInventory.CRAFT_GRID_SIZE; i++) {
            assertTrue(sp.inventory().craftSlot(i).isEmpty());
        }
        verify(em, never()).addEntity(any());
    }

    // ─── Finding 2: the periodic save does not mutate ────────────────────────────

    @Test
    void persistedBlobFoldsCursorAndGridWithoutTouchingTheLiveReplica() {
        sp.inventory().setSlot(0, stone(10));
        sp.inventory().setCarried(stone(5));
        sp.inventory().setCraftSlot(1, new ItemStack(ItemType.STICK, 1));

        PlayerData built = ServerPlayerDataFactory.build(sp, "w");

        assertEquals(15, built.getInventory()[0].getCount(), "cursor folded into the copy");
        assertEquals(ItemType.STICK.getId(), built.getInventory()[1].getBlockTypeId(),
            "grid cell folded into the copy");
        assertEquals(10, sp.inventory().slot(0).getCount(), "live slots untouched");
        assertEquals(5, sp.inventory().carried().getCount(), "live cursor untouched mid-drag");
        assertEquals(1, sp.inventory().craftSlot(1).getCount(), "live grid untouched");
        verify(em, never()).addEntity(any());
    }

    // ─── Finding 3: sort + gather match the client ───────────────────────────────

    @Test
    void sortProducesExactlyTheClientsResultAndNeverSwapsStates() {
        ItemStack[] layout = new ItemStack[ServerInventory.TOTAL_SLOTS];
        layout[0] = new ItemStack(ItemType.WOODEN_BUCKET, 1, WATER);
        layout[1] = stone(10);
        layout[2] = new ItemStack(ItemType.WOODEN_BUCKET, 1, EMPTY);
        layout[4] = new ItemStack(ItemType.STICK, 5);
        layout[9] = stone(60);                                  // main: must not cross into the hotbar
        layout[12] = new ItemStack(ItemType.WOODEN_BUCKET, 1);  // crafted (default state)
        layout[20] = new ItemStack(ItemType.WOODEN_BUCKET, 1, EMPTY);
        layout[30] = new ItemStack(ItemType.STICK, 7);

        Inventory client = new Inventory();
        for (int i = 0; i < layout.length; i++) {
            if (layout[i] == null) continue;
            sp.inventory().setSlot(i, layout[i].copy());
            if (i < Inventory.HOTBAR_SIZE) {
                client.setHotbarSlot(i, layout[i].copy());
            } else {
                client.setMainInventorySlot(i - Inventory.HOTBAR_SIZE, layout[i].copy());
            }
        }
        client.sortInventory();

        assertTrue(processor.apply(sp, inv(ContainerActionC2S.Region.PLAYER_HOTBAR, 0,
                ContainerActionC2S.Action.SORT), ctx));

        for (int i = 0; i < ServerInventory.TOTAL_SLOTS; i++) {
            ItemStack expected = i < Inventory.HOTBAR_SIZE ? client.getHotbarSlot(i)
                    : client.getMainInventorySlot(i - Inventory.HOTBAR_SIZE);
            ItemStack actual = sp.inventory().slot(i);
            assertEquals(expected.isEmpty(), actual.isEmpty(), "slot " + i);
            if (!expected.isEmpty()) {
                assertEquals(expected.getBlockTypeId(), actual.getBlockTypeId(), "slot " + i);
                assertEquals(expected.getCount(), actual.getCount(), "slot " + i);
                assertEquals(expected.getEffectiveState(), actual.getEffectiveState(), "slot " + i);
            }
        }
    }

    @Test
    void gatherDrainsMainBeforeHotbarAndTakesFromFullStacks() {
        sp.inventory().setSlot(0, stone(30));   // hotbar
        sp.inventory().setSlot(9, stone(64));   // main, full
        sp.inventory().setCarried(stone(10));

        assertTrue(processor.apply(sp, inv(ContainerActionC2S.Region.PLAYER_HOTBAR, 0,
                ContainerActionC2S.Action.GATHER), ctx));

        assertEquals(64, sp.inventory().carried().getCount());
        assertEquals(10, sp.inventory().slot(9).getCount(), "54 taken from main first");
        assertEquals(30, sp.inventory().slot(0).getCount(), "the hotbar was not needed");
    }

    // ─── Finding 4: a toss keeps its state ───────────────────────────────────────

    @Test
    void tossingAWaterBucketDropsAWaterBucket() {
        sp.inventory().setSlot(0, new ItemStack(ItemType.WOODEN_BUCKET, 1, WATER));
        sp.inventory().selectHotbarSlot(0);

        handler.handleDropItem(sp, new DropItemC2S(ItemType.WOODEN_BUCKET.getId(), 1), ctx);

        verify(em).addEntity(argThat(e -> e instanceof ItemDrop d
                && WATER.equals(d.getItemStack().getState())));
        assertTrue(sp.inventory().slot(0).isEmpty());
    }

    @Test
    void tossPrefersTheSelectedSlotOverAnEarlierStackOfAnotherState() {
        sp.inventory().setSlot(0, new ItemStack(ItemType.WOODEN_BUCKET, 1, WATER));
        sp.inventory().setSlot(3, new ItemStack(ItemType.WOODEN_BUCKET, 1, EMPTY));
        sp.inventory().selectHotbarSlot(3);

        handler.handleDropItem(sp, new DropItemC2S(ItemType.WOODEN_BUCKET.getId(), 1), ctx);

        assertTrue(sp.inventory().slot(3).isEmpty(), "the held empty bucket was tossed");
        assertEquals(WATER, sp.inventory().slot(0).getState(), "the water bucket stayed");
        verify(em).addEntity(argThat(e -> e instanceof ItemDrop d
                && EMPTY.equals(d.getItemStack().getEffectiveState())));
    }

    @Test
    void tossNeverConsumesAStackOfAnotherState() {
        sp.inventory().setSlot(0, new ItemStack(ItemType.WOODEN_BUCKET, 1, EMPTY));
        sp.inventory().setSlot(1, new ItemStack(ItemType.WOODEN_BUCKET, 1, WATER));
        sp.inventory().selectHotbarSlot(0);

        handler.handleDropItem(sp, new DropItemC2S(ItemType.WOODEN_BUCKET.getId(), 2), ctx);

        verify(em, never()).addEntity(any());
        assertEquals(1, sp.inventory().slot(0).getCount());
        assertEquals(1, sp.inventory().slot(1).getCount());
    }

    // ─── Finding 5: no furnace origin ────────────────────────────────────────────

    @Test
    void swapWithAFurnaceOriginCannotWriteDirtIntoTheFuelSlot() {
        furnace.setFuel(new ItemStack(BlockType.WOOD, 4));
        sp.inventory().setSlot(0, new ItemStack(BlockType.DIRT, 3));
        assertTrue(processor.apply(sp, act(ContainerActionC2S.ContainerKind.FURNACE, FURNACE_POS,
                ContainerActionC2S.Region.FURNACE_FUEL, 1, ContainerActionC2S.Action.TAKE_ALL), ctx));

        ContainerActionC2S swap = new ContainerActionC2S(FURNACE_POS.x(), FURNACE_POS.y(), FURNACE_POS.z(),
                ContainerActionC2S.ContainerKind.FURNACE, ContainerActionC2S.Region.PLAYER_HOTBAR, (byte) 0,
                ContainerActionC2S.Region.FURNACE_FUEL, (byte) 1, ContainerActionC2S.Action.SWAP,
                (short) 0, false, 0);
        assertFalse(processor.apply(sp, swap, ctx));

        assertTrue(furnace.getFuel().isEmpty(), "no dirt in the fuel slot");
        assertEquals(BlockType.DIRT.getId(), sp.inventory().slot(0).getBlockTypeId());
        assertEquals(4, sp.inventory().carried().getCount());
    }

    @Test
    void swapWithAnOccupiedOriginKeepsTheDisplacedStackCarried() {
        sp.inventory().setSlot(0, stone(2));            // split-pickup remainder at the origin
        sp.inventory().setSlot(5, new ItemStack(BlockType.DIRT, 3));
        sp.inventory().setCarried(stone(2));

        ContainerActionC2S swap = new ContainerActionC2S(0, 0, 0,
                ContainerActionC2S.ContainerKind.INVENTORY, ContainerActionC2S.Region.PLAYER_HOTBAR, (byte) 5,
                ContainerActionC2S.Region.PLAYER_HOTBAR, (byte) 0, ContainerActionC2S.Action.SWAP,
                (short) 0, false, 0);
        assertTrue(processor.apply(sp, swap, ctx));

        assertEquals(2, sp.inventory().slot(0).getCount(), "the origin remainder survives");
        assertEquals(BlockType.DIRT.getId(), sp.inventory().carried().getBlockTypeId());
    }

    @Test
    void swapOntoAFullStackOfTheSameKindIsRefused() {
        sp.inventory().setSlot(0, stone(64));
        sp.inventory().setCarried(stone(3));

        assertFalse(processor.apply(sp, inv(ContainerActionC2S.Region.PLAYER_HOTBAR, 0,
                ContainerActionC2S.Action.SWAP), ctx));
        assertEquals(3, sp.inventory().carried().getCount());
    }

    // ─── Finding 6: regions must belong to the container kind ────────────────────

    @Test
    void furnaceRegionUnderKindInventoryIsRefused() {
        furnace.setFuel(new ItemStack(BlockType.WOOD, 4));

        assertFalse(processor.apply(sp, act(ContainerActionC2S.ContainerKind.INVENTORY, FURNACE_POS,
                ContainerActionC2S.Region.FURNACE_FUEL, 1, ContainerActionC2S.Action.TAKE_ALL), ctx));
        assertEquals(4, furnace.getFuel().getCount());
        assertTrue(sp.inventory().carried().isEmpty());
    }

    @Test
    void inventoryGridUnderKindFurnaceIsRefused() {
        sp.inventory().setCraftSlot(0, new ItemStack(ItemType.STICK, 1));

        assertFalse(processor.apply(sp, act(ContainerActionC2S.ContainerKind.FURNACE, FURNACE_POS,
                ContainerActionC2S.Region.INV_CRAFT_GRID, 0, ContainerActionC2S.Action.TAKE_ALL), ctx));
        assertEquals(1, sp.inventory().craftSlot(0).getCount());
    }

    @Test
    void craftAllUnderKindInventoryUsesThe2x2GridNotANearbyWorkbench() {
        workbench.getSlots()[0] = new ItemStack(BlockType.WOOD, 3);

        assertFalse(processor.apply(sp, act(ContainerActionC2S.ContainerKind.INVENTORY, BENCH_POS,
                ContainerActionC2S.Region.INV_CRAFT_OUT, 0, ContainerActionC2S.Action.CRAFT_ALL), ctx),
            "the empty 2×2 grid has nothing to craft");
        assertEquals(3, workbench.getSlots()[0].getCount(), "the workbench was not touched");

        sp.inventory().setCraftSlot(0, new ItemStack(BlockType.WOOD, 2));
        assertTrue(processor.apply(sp, act(ContainerActionC2S.ContainerKind.INVENTORY, BENCH_POS,
                ContainerActionC2S.Region.INV_CRAFT_OUT, 0, ContainerActionC2S.Action.CRAFT_ALL), ctx));
        assertEquals(8, sp.inventory().countOf(ItemType.STICK.getId()));
        assertEquals(3, workbench.getSlots()[0].getCount());
    }

    // ─── Finding 7: the client's stacking identity ───────────────────────────────

    @Test
    void craftedAndEmptiedBucketsMergeOnTheServer() {
        assertNotNull(ItemType.WOODEN_BUCKET.getDefaultState(), "precondition: buckets have states");
        sp.inventory().add(ItemType.WOODEN_BUCKET.getId(), 1, null);
        sp.inventory().add(ItemType.WOODEN_BUCKET.getId(), 1, EMPTY);

        assertEquals(2, sp.inventory().slot(0).getCount(), "one stack, as on the client");
        assertTrue(sp.inventory().slot(1).isEmpty());
    }

    @Test
    void balanceIncludesAFullCellLikeTheClient() {
        sp.inventory().setCraftSlot(0, stone(64));
        sp.inventory().setCraftSlot(1, stone(2));

        assertTrue(processor.apply(sp, inv(ContainerActionC2S.Region.INV_CRAFT_GRID, 1,
                ContainerActionC2S.Action.BALANCE), ctx));

        assertEquals(33, sp.inventory().craftSlot(0).getCount());
        assertEquals(33, sp.inventory().craftSlot(1).getCount());
    }

    @Test
    void placementDebitPrefersTheSelectedSlotLikeInventoryRemoveItem() {
        sp.inventory().setSlot(0, stone(5));
        sp.inventory().setSlot(4, stone(5));
        sp.inventory().selectHotbarSlot(4);

        assertTrue(sp.inventory().debit(BlockType.STONE.getId(), 1));

        assertEquals(5, sp.inventory().slot(0).getCount());
        assertEquals(4, sp.inventory().slot(4).getCount());
    }
}
