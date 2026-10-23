package com.stonebreak.network.server.handlers;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.server.ServerConnection;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.ItemDrop;
import com.stonebreak.network.packet.player.ContainerActionC2S;
import com.stonebreak.network.packet.player.InventorySyncS2C;
import com.stonebreak.network.server.ServerInventory;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;
import com.stonebreak.world.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Closing the inventory screen returns the 2×2 craft grid THROUGH the server (issue #334):
 * the client sends one QUICK_MOVE per non-empty grid cell, the server moves the cell into
 * its replica's 36 slots, and overflow drops server-side — the ephemeral grid never
 * strands items that the next InventorySyncS2C would otherwise wipe.
 */
class InventoryCloseReturnTest {

    private ServerPlayer sp;
    private ServerWorldContext ctx;
    private EntityManager em;
    private ServerPlayerHandler handler;

    @BeforeEach
    void setUp() {
        sp = new ServerPlayer(mock(ServerConnection.class), 7);
        sp.updateState(10.5f, 65f, 10.5f, 0f, 0f);
        World world = mock(World.class);
        em = Mockito.spy(new EntityManager(world));
        ctx = mock(ServerWorldContext.class);
        when(ctx.world()).thenReturn(world);
        when(ctx.entityManager()).thenReturn(em);
        handler = new ServerPlayerHandler();
    }

    private ContainerActionC2S gridMove(int cell) {
        return new ContainerActionC2S(0, 0, 0, ContainerActionC2S.ContainerKind.INVENTORY,
                ContainerActionC2S.Region.INV_CRAFT_GRID, (byte) cell,
                ContainerActionC2S.Action.QUICK_MOVE, (short) 0, false);
    }

    private List<Packet> sent() {
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(sp.connection(), atLeastOnce()).send(captor.capture(), anyBoolean());
        return captor.getAllValues();
    }

    @Test
    void gridCellsLandInTheReplicas36SlotsOnClose() {
        sp.inventory().setCraftSlot(0, new ItemStack(ItemType.STICK, 3));
        sp.inventory().setCraftSlot(3, new ItemStack(ItemType.STICK, 2));

        handler.handleContainerAction(sp, gridMove(0), ctx);
        handler.handleContainerAction(sp, gridMove(3), ctx);
        sp.flushInventoryResync(); // a drain's accepted actions share one sync

        assertEquals(5, sp.inventory().countOf(ItemType.STICK.getId()),
            "both grid cells were deposited into the 36 slots");
        for (int i = 0; i < ServerInventory.CRAFT_GRID_SIZE; i++) {
            assertTrue(sp.inventory().craftSlot(i).isEmpty(), "grid cell " + i + " drained");
        }
        assertTrue(sp.inventory().carried().isEmpty(), "the cursor stayed out of it");
        assertTrue(sent().stream().anyMatch(p -> p instanceof InventorySyncS2C),
            "every accepted close op synced the mirror");
    }

    @Test
    void gridOverflowKeepsItsRemainderThenTheCloseDepositDropsIt() {
        // 35 full stone slots + one part-filled stick slot: a 10-stick grid cell stacks 4
        // into the stick slot. QUICK_MOVE keeps the 6 left over in the cell, exactly like the
        // client's shift-click; the close's DEPOSIT_CARRIED then drops them, never strands.
        for (int i = 0; i < ServerInventory.TOTAL_SLOTS - 1; i++) {
            sp.inventory().setSlot(i, new ItemStack(com.stonebreak.blocks.BlockType.STONE.getId(), 64));
        }
        sp.inventory().setSlot(ServerInventory.TOTAL_SLOTS - 1, new ItemStack(ItemType.STICK, 60));
        sp.inventory().setCraftSlot(1, new ItemStack(ItemType.STICK, 10));

        handler.handleContainerAction(sp, gridMove(1), ctx);

        assertEquals(6, sp.inventory().craftSlot(1).getCount(),
            "the remainder stays in the cell (client tryShiftClickCraftingInput rule)");
        assertEquals(64, sp.inventory().slot(ServerInventory.TOTAL_SLOTS - 1).getCount(),
            "the fit portion stacked into the replica slot");

        handler.handleContainerAction(sp, new ContainerActionC2S(0, 0, 0,
                ContainerActionC2S.ContainerKind.INVENTORY, ContainerActionC2S.Region.PLAYER_HOTBAR,
                (byte) 0, ContainerActionC2S.Action.DEPOSIT_CARRIED, (short) 0, false), ctx);

        verify(em).addEntity(argThat(e -> e instanceof ItemDrop d && d.getStackCount() == 6));
        assertTrue(sp.inventory().craftSlot(1).isEmpty(),
            "closing the inventory returns the ephemeral grid — nothing strands there");
    }

    @Test
    void quickMoveOnAnEmptyGridCellRefusesAndSyncs() {
        handler.handleContainerAction(sp, gridMove(2), ctx);

        assertEquals(0, sp.inventory().countOf(ItemType.STICK.getId()), "nothing appeared");
        assertTrue(sent().stream().anyMatch(p -> p instanceof InventorySyncS2C),
            "the refusal re-asserts the replica so the prediction reverts");
    }
}
