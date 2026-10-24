package com.stonebreak.network.server;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.server.ServerConnection;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.ItemDrop;
import com.stonebreak.network.packet.NetItemStack;
import com.stonebreak.network.packet.player.GiveItemS2C;
import com.stonebreak.world.World;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issue #342 on the pickup side: a drop's state reaches the picking player's replica and the
 * {@code GiveItemS2C} that tells its client, and an overflow re-drop keeps it. Issue #341: the
 * server-thread-only give path refuses a call from any other thread.
 */
class ItemStatePickupTest {

    private static final String WATER = ItemType.BUCKET_STATE_WATER;
    private static final NetItemStack WATER_BUCKET =
            new NetItemStack(ItemType.WOODEN_BUCKET.getId(), 1, WATER);

    private static ServerPlayer player() {
        ServerPlayer sp = new ServerPlayer(mock(ServerConnection.class), 3);
        sp.updateState(1f, 64f, 1f, 0f, 0f);
        return sp;
    }

    private static List<Packet> sent(ServerPlayer sp) {
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(sp.connection(), atLeastOnce()).send(captor.capture(), anyBoolean());
        return captor.getAllValues();
    }

    @Test
    void aPickedUpWaterBucketArrivesFull() {
        ServerPlayer sp = player();

        new IntegratedServer().giveToPlayer(sp, mock(ServerWorldContext.class), WATER_BUCKET);

        ItemStack held = sp.inventory().slot(0);
        assertTrue(held.getItem() == ItemType.WOODEN_BUCKET && WATER.equals(held.getState()),
                "the replica holds a WATER bucket");
        assertTrue(sent(sp).stream().anyMatch(p -> p instanceof GiveItemS2C g
                && WATER.equals(g.stack().state())), "the client is given the state too");
    }

    @Test
    void anOverflowReDropKeepsTheState() {
        ServerPlayer sp = player();
        for (int i = 0; i < ServerInventory.TOTAL_SLOTS; i++) {
            sp.inventory().setSlot(i, new ItemStack(ItemType.STICK, 64));
        }
        ServerWorldContext ctx = mock(ServerWorldContext.class);
        World world = mock(World.class);
        EntityManager em = Mockito.spy(new EntityManager(world));
        when(ctx.world()).thenReturn(world);
        when(ctx.entityManager()).thenReturn(em);

        new IntegratedServer().giveToPlayer(sp, ctx, WATER_BUCKET);

        verify(em).addEntity(argThat(e -> e instanceof ItemDrop d
                && WATER.equals(d.getItemStack().getState())));
    }

    @Test
    void aFullInventoryHasNoRoomForAStateItCannotStackWith() {
        ServerPlayer sp = player();
        for (int i = 0; i < ServerInventory.TOTAL_SLOTS; i++) {
            sp.inventory().setSlot(i, new ItemStack(ItemType.STICK, 64));
        }
        assertEquals(0, sp.inventory().roomFor(ItemType.WOODEN_BUCKET.getId(), 1, WATER));
    }

    @Test
    void theGivePathRefusesCallsFromOffTheServerThread() throws InterruptedException {
        IntegratedServer server = new IntegratedServer();
        boolean[] onTickThread = new boolean[1];
        Thread tick = new Thread(() -> {
            server.drainInboundForShutdown(); // becomes the server's tick thread
            onTickThread[0] = server.isTickThread();
        });
        tick.start();
        tick.join();

        assertTrue(onTickThread[0], "the tick thread may give");
        assertFalse(server.isTickThread(), "the test (game) thread is not the server thread");
        assertFalse(server.requireTickThread("test"), "and is refused");
    }
}
