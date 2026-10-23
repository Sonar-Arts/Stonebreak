package com.stonebreak.network.server.handlers;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.server.ServerConnection;
import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.furnace.FurnaceState;
import com.stonebreak.blocks.furnace.FurnaceStateRegistry;
import com.stonebreak.blocks.workbench.WorkbenchState;
import com.stonebreak.blocks.workbench.WorkbenchStateRegistry;
import com.stonebreak.crafting.CraftingManager;
import com.stonebreak.crafting.Recipe;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.network.packet.player.ContainerActionC2S;
import com.stonebreak.network.packet.player.InventorySyncS2C;
import com.stonebreak.network.packet.world.BlockStateS2C;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;
import com.stonebreak.world.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The server's atomic container rules (issue #334): output slots are TAKE-only with the
 * result computed server-side, and two players acting on one container can no longer
 * duplicate anything — every action is a validated debit/credit on authoritative state.
 */
class ContainerActionServerTest {

    private static final BlockPos POS = new BlockPos(10, 64, 10);
    private static final BlockPos BENCH_POS = new BlockPos(14, 64, 10);

    private ServerPlayer alice;
    private ServerPlayer bob;
    private ServerWorldContext ctx;
    private World world;
    private FurnaceState furnace;
    private WorkbenchState workbench;
    private ContainerActionProcessor processor;

    @BeforeEach
    void setUp() {
        alice = new ServerPlayer(mock(ServerConnection.class), 1);
        bob = new ServerPlayer(mock(ServerConnection.class), 2);
        alice.updateState(POS.x() + 0.5f, POS.y() + 1f, POS.z() + 0.5f, 0f, 0f);
        bob.updateState(POS.x() + 0.5f, POS.y() + 1f, POS.z() + 0.5f, 0f, 0f);

        furnace = new FurnaceState(POS);
        WorkbenchStateRegistry workbenchRegistry = new WorkbenchStateRegistry();
        workbench = workbenchRegistry.getOrCreate(BENCH_POS);

        world = mock(World.class);
        FurnaceStateRegistry furnaceRegistry = new FurnaceStateRegistry(null) {
            @Override
            public FurnaceState getOrCreate(BlockPos pos) {
                return furnace;
            }
        };
        when(world.getFurnaceRegistry()).thenReturn(furnaceRegistry);
        when(world.getWorkbenchRegistry()).thenReturn(workbenchRegistry);
        when(world.getBlockAt(POS.x(), POS.y(), POS.z())).thenReturn(BlockType.FURNACE);
        when(world.getBlockAt(BENCH_POS.x(), BENCH_POS.y(), BENCH_POS.z())).thenReturn(BlockType.WORKBENCH);

        ctx = mock(ServerWorldContext.class);
        when(ctx.world()).thenReturn(world);

        CraftingManager recipes = new CraftingManager();
        recipes.registerRecipe(new Recipe("test_planks",
                List.of(List.of(new ItemStack(BlockType.WOOD, 1))),
                new ItemStack(ItemType.STICK, 4)));
        com.stonebreak.crafting.SmeltingManager smelting = new com.stonebreak.crafting.SmeltingManager();
        smelting.registerFuel(BlockType.WOOD, 80);
        smelting.registerRecipe(new com.stonebreak.crafting.SmeltingRecipe(
                "test_smelt", new ItemStack(BlockType.WOOD, 1), new ItemStack(ItemType.STICK, 1)));
        processor = new ContainerActionProcessor(recipes, smelting);
    }

    private void seedPlayer(ServerPlayer sp, int itemId, int count) {
        sp.inventory().add(itemId, count);
        sp.inventory().selectHotbarSlot(0);
    }

    private List<Packet> sent(ServerPlayer sp) {
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(sp.connection(), atLeastOnce()).send(captor.capture(), anyBoolean());
        return captor.getAllValues();
    }

    private static ContainerActionC2S action(ServerPlayer sp, byte region, byte slot, byte act) {
        return new ContainerActionC2S(POS.x(), POS.y(), POS.z(),
                ContainerActionC2S.ContainerKind.FURNACE, region, slot, act, (short) 0, false);
    }

    @Test
    void putIntoFurnaceOutputRefused() {
        seedPlayer(alice, BlockType.STONE.getId(), 8);
        // A client that first picked a stack up (TAKE_ALL from its own hotbar) now carries
        // it and tries to write it into the output slot — the write must be refused, not
        // the vacuous empty-carried case.
        assertTrue(processor.apply(alice, action(alice,
                ContainerActionC2S.Region.PLAYER_HOTBAR, (byte) 0,
                ContainerActionC2S.Action.TAKE_ALL), ctx), "precondition: a carried stack");
        assertEquals(8, alice.inventory().carried().getCount());

        boolean accepted = processor.apply(alice, action(alice,
                ContainerActionC2S.Region.FURNACE_OUT, (byte) 2,
                ContainerActionC2S.Action.PUT_ALL), ctx);

        assertTrue(!accepted, "the output slot is TAKE-only");
        assertTrue(furnace.getOutput().isEmpty(), "nothing was written into the output");
        assertEquals(8, alice.inventory().carried().getCount(), "the carried stack survived the refusal");
        List<Packet> packets = sent(alice);
        assertTrue(packets.stream().anyMatch(p -> p instanceof InventorySyncS2C),
            "refusal syncs the sender");
        assertTrue(packets.stream().anyMatch(p -> p instanceof BlockStateS2C
                && ((BlockStateS2C) p).state().startsWith("furnace:")),
            "refusal re-sends the container state so the optimistic edit reverts");
    }

    @Test
    void mergeAndSwapIntoOutputRefused() {
        seedPlayer(alice, BlockType.STONE.getId(), 8);
        furnace.setOutput(new ItemStack(BlockType.STONE, 1));
        // Pick the output up so the client carries a stack (the only state from which the
        // write-shaped actions are even reachable), then refill the output as the smelter would.
        assertTrue(processor.apply(alice, action(alice,
                ContainerActionC2S.Region.FURNACE_OUT, (byte) 2,
                ContainerActionC2S.Action.TAKE_ALL), ctx));
        assertEquals(1, alice.inventory().carried().getCount());
        furnace.setOutput(new ItemStack(BlockType.STONE, 1));

        boolean merged = processor.apply(alice, action(alice,
                ContainerActionC2S.Region.FURNACE_OUT, (byte) 2,
                ContainerActionC2S.Action.MERGE), ctx);
        assertTrue(!merged, "MERGE cannot write the output");
        assertEquals(1, furnace.getOutput().getCount(), "the output was untouched");
        boolean swapped = processor.apply(alice, action(alice,
                ContainerActionC2S.Region.FURNACE_OUT, (byte) 2,
                ContainerActionC2S.Action.SWAP), ctx);
        assertTrue(!swapped, "SWAP cannot write the output");
        boolean dropOne = processor.apply(alice, action(alice,
                ContainerActionC2S.Region.FURNACE_OUT, (byte) 2,
                ContainerActionC2S.Action.DROP_ONE), ctx);
        assertTrue(!dropOne, "DROP_ONE cannot write the output");
    }

    @Test
    void craftOutputTakeConsumesInputsAndGrantsOnce() {
        seedPlayer(alice, BlockType.WOOD.getId(), 2);
        workbench.getSlots()[0] = new ItemStack(BlockType.WOOD, 1);

        boolean taken = processor.apply(alice, new ContainerActionC2S(BENCH_POS.x(), BENCH_POS.y(), BENCH_POS.z(),
                ContainerActionC2S.ContainerKind.WORKBENCH,
                ContainerActionC2S.Region.WORKBENCH_OUT, (byte) 0,
                ContainerActionC2S.Action.TAKE_OUTPUT, (short) 0, false), ctx);

        assertTrue(taken, "a matching recipe is taken");
        assertEquals(4, alice.inventory().carried().getCount(), "the SERVER computed the result");
        assertEquals(0, workbench.getSlots()[0].getCount(), "the inputs were consumed server-side");

        // The grid is empty now: a second take is refused — no phantom output.
        boolean second = processor.apply(alice, new ContainerActionC2S(BENCH_POS.x(), BENCH_POS.y(), BENCH_POS.z(),
                ContainerActionC2S.ContainerKind.WORKBENCH,
                ContainerActionC2S.Region.WORKBENCH_OUT, (byte) 0,
                ContainerActionC2S.Action.TAKE_OUTPUT, (short) 0, false), ctx);
        assertTrue(!second);
        assertEquals(4, alice.inventory().carried().getCount());
    }

    @Test
    void twoPlayersOneFurnaceNoDuplication() {
        furnace.setOutput(new ItemStack(BlockType.STONE, 3));
        seedPlayer(alice, 0, 0);
        seedPlayer(bob, 0, 0);

        // Alice takes the output: it moves to her carried stack and the slot empties.
        assertTrue(processor.apply(alice, action(alice,
                ContainerActionC2S.Region.FURNACE_OUT, (byte) 2,
                ContainerActionC2S.Action.TAKE_ALL), ctx));
        assertEquals(3, alice.inventory().carried().getCount());
        assertTrue(furnace.getOutput().isEmpty(), "the server slot is now empty");

        // Bob's client still SHOWS the old output and asks for it too: refused, nothing granted.
        boolean bobTook = processor.apply(bob, action(bob,
                ContainerActionC2S.Region.FURNACE_OUT, (byte) 2,
                ContainerActionC2S.Action.TAKE_ALL), ctx);
        assertTrue(!bobTook, "the last-write-wins duplication is gone");
        assertEquals(0, bob.inventory().carried().getCount());
        assertTrue(sent(bob).stream().anyMatch(p -> p instanceof InventorySyncS2C));
    }

    @Test
    void quickMoveValidatedAgainstServerStock() {
        // Shift-click from an EMPTY furnace slot: refused, nothing appears in the inventory.
        int before = alice.inventory().countOf(BlockType.STONE.getId());
        boolean moved = processor.apply(alice, action(alice,
                ContainerActionC2S.Region.FURNACE_FUEL, (byte) 1,
                ContainerActionC2S.Action.QUICK_MOVE), ctx);
        assertTrue(!moved);
        assertEquals(before, alice.inventory().countOf(BlockType.STONE.getId()));

        // With stock in the slot it moves into the player's replica.
        furnace.setFuel(new ItemStack(BlockType.STONE, 5));
        assertTrue(processor.apply(alice, action(alice,
                ContainerActionC2S.Region.FURNACE_FUEL, (byte) 1,
                ContainerActionC2S.Action.QUICK_MOVE), ctx));
        assertEquals(before + 5, alice.inventory().countOf(BlockType.STONE.getId()));
        assertTrue(furnace.getFuel().isEmpty());
    }

    @Test
    void nonFuelRejectedFromFurnaceFuelSlot() {
        // STONE is not a registered fuel: the deposit is refused before any mutation.
        seedPlayer(alice, BlockType.STONE.getId(), 4);
        assertTrue(processor.apply(alice, action(alice,
                ContainerActionC2S.Region.PLAYER_HOTBAR, (byte) 0,
                ContainerActionC2S.Action.TAKE_ALL), ctx), "precondition: a carried stack");
        boolean put = processor.apply(alice, action(alice,
                ContainerActionC2S.Region.FURNACE_FUEL, (byte) 1,
                ContainerActionC2S.Action.PUT_ALL), ctx);
        assertTrue(!put, "only fuels enter the fuel slot");
        assertTrue(furnace.getFuel().isEmpty());
        assertEquals(4, alice.inventory().carried().getCount(), "the carried stack survived");
    }

    @Test
    void registeredFuelAcceptedIntoFurnaceFuelSlot() {
        seedPlayer(alice, BlockType.WOOD.getId(), 4);
        assertTrue(processor.apply(alice, action(alice,
                ContainerActionC2S.Region.PLAYER_HOTBAR, (byte) 0,
                ContainerActionC2S.Action.TAKE_ALL), ctx));
        assertTrue(processor.apply(alice, action(alice,
                ContainerActionC2S.Region.FURNACE_FUEL, (byte) 1,
                ContainerActionC2S.Action.PUT_ALL), ctx));
        assertEquals(4, furnace.getFuel().getCount());
        assertTrue(alice.inventory().carried().isEmpty());
    }

    @Test
    void nonSmeltableRejectedFromFurnaceIngredientSlot() {
        // STONE matches no smelting recipe: the deposit is refused.
        seedPlayer(alice, BlockType.STONE.getId(), 4);
        assertTrue(processor.apply(alice, action(alice,
                ContainerActionC2S.Region.PLAYER_HOTBAR, (byte) 0,
                ContainerActionC2S.Action.TAKE_ALL), ctx));
        boolean put = processor.apply(alice, action(alice,
                ContainerActionC2S.Region.FURNACE_ING, (byte) 0,
                ContainerActionC2S.Action.PUT_ALL), ctx);
        assertTrue(!put, "only smeltables enter the ingredient slot");
        assertTrue(furnace.getIngredient().isEmpty());
        assertEquals(4, alice.inventory().carried().getCount());
    }

    @Test
    void depositCarriedDropsWhatTheFullInventoryCannotTake() {
        // Closing a screen with a carried stack while the 36 slots are full: the SERVER
        // deposits nothing and drops the whole carried stack at the player — the cursor
        // ends empty, so the stack can neither strand on save/rejoin nor vanish (#307).
        com.stonebreak.mobs.entities.EntityManager em = org.mockito.Mockito.spy(
                new com.stonebreak.mobs.entities.EntityManager(mock(World.class)));
        org.mockito.Mockito.when(ctx.entityManager()).thenReturn(em);
        for (int i = 0; i < com.stonebreak.network.server.ServerInventory.TOTAL_SLOTS; i++) {
            alice.inventory().setSlot(i, new ItemStack(BlockType.STONE, 64));
        }
        alice.inventory().setCarried(new ItemStack(ItemType.STICK, 5));

        boolean accepted = processor.apply(alice, action(alice,
                ContainerActionC2S.Region.PLAYER_HOTBAR, (byte) 0,
                ContainerActionC2S.Action.DEPOSIT_CARRIED), ctx);

        assertTrue(accepted, "a carried stack was present");
        org.mockito.Mockito.verify(em, org.mockito.Mockito.times(1))
                .addEntity(org.mockito.ArgumentMatchers.any());
        assertTrue(alice.inventory().carried().isEmpty(), "the cursor was drained");
        assertEquals(36 * 64, alice.inventory().countOf(BlockType.STONE.getId()),
            "the full slots were untouched");
    }

    @Test
    void depositCarriedIntoFreeSlotsLeavesNothingCarriedAndDropsNothing() {
        seedPlayer(alice, BlockType.STONE.getId(), 8);
        assertTrue(processor.apply(alice, action(alice,
                ContainerActionC2S.Region.PLAYER_HOTBAR, (byte) 0,
                ContainerActionC2S.Action.TAKE_ALL), ctx), "precondition: a carried stack");
        com.stonebreak.mobs.entities.EntityManager em = org.mockito.Mockito.spy(
                new com.stonebreak.mobs.entities.EntityManager(mock(World.class)));
        org.mockito.Mockito.when(ctx.entityManager()).thenReturn(em);

        boolean accepted = processor.apply(alice, action(alice,
                ContainerActionC2S.Region.PLAYER_HOTBAR, (byte) 0,
                ContainerActionC2S.Action.DEPOSIT_CARRIED), ctx);

        assertTrue(accepted);
        org.mockito.Mockito.verify(em, org.mockito.Mockito.never())
                .addEntity(org.mockito.ArgumentMatchers.any());
        assertTrue(alice.inventory().carried().isEmpty(), "the stack went into a slot");
        assertEquals(8, alice.inventory().countOf(BlockType.STONE.getId()), "nothing was lost");
    }

    // ─── Container broken mid-edit (issue #407) ───────────────────────────────────

    private static ContainerActionC2S benchAction(byte region, int slot, byte act, int seq) {
        return new ContainerActionC2S(BENCH_POS.x(), BENCH_POS.y(), BENCH_POS.z(),
                ContainerActionC2S.ContainerKind.WORKBENCH, region, (byte) slot,
                ContainerActionC2S.Region.NONE, (byte) -1, act, (short) 0, false, seq);
    }

    private static ContainerActionC2S furnaceAction(byte region, int slot, byte act, int seq) {
        return new ContainerActionC2S(POS.x(), POS.y(), POS.z(),
                ContainerActionC2S.ContainerKind.FURNACE, region, (byte) slot,
                ContainerActionC2S.Region.NONE, (byte) -1, act, (short) 0, false, seq);
    }

    /** What the server's break drops: the grid it holds at that moment. Then the cell is air. */
    private int breakWorkbench() {
        int dropped = 0;
        for (ItemStack s : workbench.getSlots()) {
            dropped += s.isEmpty() ? 0 : s.getCount();
        }
        when(world.getBlockAt(BENCH_POS.x(), BENCH_POS.y(), BENCH_POS.z())).thenReturn(BlockType.AIR);
        return dropped;
    }

    @Test
    void workbenchBrokenMidDragAccountsForEveryItemOnce() {
        // Bob right-drags 8 wood across the grid; Alice breaks the table after his first two
        // cells landed. The last two DROP_ONEs were in flight and arrive at a dead cell.
        seedPlayer(bob, BlockType.WOOD.getId(), 8);
        int seq = 0;
        assertTrue(processor.apply(bob, benchAction(ContainerActionC2S.Region.PLAYER_HOTBAR, 0,
                ContainerActionC2S.Action.TAKE_ALL, ++seq), ctx));
        assertTrue(processor.apply(bob, benchAction(ContainerActionC2S.Region.WORKBENCH_GRID, 0,
                ContainerActionC2S.Action.DROP_ONE, ++seq), ctx));
        assertTrue(processor.apply(bob, benchAction(ContainerActionC2S.Region.WORKBENCH_GRID, 1,
                ContainerActionC2S.Action.DROP_ONE, ++seq), ctx));

        int dropped = breakWorkbench();

        assertTrue(!processor.apply(bob, benchAction(ContainerActionC2S.Region.WORKBENCH_GRID, 2,
                ContainerActionC2S.Action.DROP_ONE, ++seq), ctx), "a dead table takes nothing");
        assertTrue(!processor.apply(bob, benchAction(ContainerActionC2S.Region.WORKBENCH_GRID, 3,
                ContainerActionC2S.Action.DROP_ONE, ++seq), ctx));
        assertEquals(6, bob.inventory().carried().getCount(),
            "a refused deposit never left the server's cursor");

        // The client abandons the screen: the cursor goes home, the dead grid cells are
        // quick-moved (refused), and the close ends with DEPOSIT_CARRIED.
        processor.apply(bob, benchAction(ContainerActionC2S.Region.PLAYER_HOTBAR, 0,
                ContainerActionC2S.Action.PUT_ALL, ++seq), ctx);
        processor.apply(bob, benchAction(ContainerActionC2S.Region.WORKBENCH_GRID, 2,
                ContainerActionC2S.Action.QUICK_MOVE, ++seq), ctx);
        processor.apply(bob, benchAction(ContainerActionC2S.Region.PLAYER_HOTBAR, 0,
                ContainerActionC2S.Action.DEPOSIT_CARRIED, ++seq), ctx);

        assertEquals(2, dropped, "the break dropped exactly the cells the server applied");
        assertTrue(bob.inventory().carried().isEmpty(), "nothing strands on the server cursor");
        assertEquals(8, bob.inventory().countOf(BlockType.WOOD.getId()) + dropped,
            "every item is either dropped or back in Bob's inventory, exactly once");
    }

    @Test
    void workbenchBrokenWhileTakingFromTheGridNeverDuplicates() {
        // The mirror case: Bob takes from the grid. The take that landed is his; the one that
        // arrived after the break is refused, because the server dropped that cell.
        seedPlayer(bob, 0, 0);
        workbench.getSlots()[0] = new ItemStack(BlockType.WOOD, 3);
        workbench.getSlots()[1] = new ItemStack(BlockType.WOOD, 5);
        int seq = 0;
        assertTrue(processor.apply(bob, benchAction(ContainerActionC2S.Region.WORKBENCH_GRID, 0,
                ContainerActionC2S.Action.TAKE_ALL, ++seq), ctx));

        int dropped = breakWorkbench();

        assertTrue(!processor.apply(bob, benchAction(ContainerActionC2S.Region.WORKBENCH_GRID, 1,
                ContainerActionC2S.Action.TAKE_ONE, ++seq), ctx));
        // Abandon: the cursor "returns" to its grid origin (refused), then is deposited.
        processor.apply(bob, benchAction(ContainerActionC2S.Region.WORKBENCH_GRID, 0,
                ContainerActionC2S.Action.PUT_ALL, ++seq), ctx);
        processor.apply(bob, benchAction(ContainerActionC2S.Region.PLAYER_HOTBAR, 0,
                ContainerActionC2S.Action.DEPOSIT_CARRIED, ++seq), ctx);

        assertEquals(5, dropped);
        assertEquals(3, bob.inventory().countOf(BlockType.WOOD.getId()));
        assertTrue(bob.inventory().carried().isEmpty());
        assertEquals(8, bob.inventory().countOf(BlockType.WOOD.getId()) + dropped,
            "no stack is both dropped and given back");
    }

    @Test
    void furnaceBrokenWithAnUnappliedDepositGivesTheStackBack() {
        seedPlayer(bob, BlockType.WOOD.getId(), 8);
        int seq = 0;
        assertTrue(processor.apply(bob, furnaceAction(ContainerActionC2S.Region.PLAYER_HOTBAR, 0,
                ContainerActionC2S.Action.TAKE_ALL, ++seq), ctx));

        when(world.getBlockAt(POS.x(), POS.y(), POS.z())).thenReturn(BlockType.AIR);

        assertTrue(!processor.apply(bob, furnaceAction(ContainerActionC2S.Region.FURNACE_FUEL, 1,
                ContainerActionC2S.Action.PUT_ALL, ++seq), ctx), "the fuel never reached a furnace");
        processor.apply(bob, furnaceAction(ContainerActionC2S.Region.PLAYER_HOTBAR, 0,
                ContainerActionC2S.Action.DEPOSIT_CARRIED, ++seq), ctx);

        assertTrue(furnace.getFuel().isEmpty(), "nothing was written into the broken furnace");
        assertTrue(bob.inventory().carried().isEmpty());
        assertEquals(8, bob.inventory().countOf(BlockType.WOOD.getId()), "the stack is Bob's again");
    }
}
