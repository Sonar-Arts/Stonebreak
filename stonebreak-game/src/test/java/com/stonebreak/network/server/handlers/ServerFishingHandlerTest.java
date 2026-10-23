package com.stonebreak.network.server.handlers;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.server.ServerConnection;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.FishingBobber;
import com.stonebreak.mobs.entities.FishingManager;
import com.stonebreak.mobs.entities.ItemDrop;
import com.stonebreak.network.packet.player.FishingRodC2S;
import com.stonebreak.network.packet.player.InventorySyncS2C;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;
import com.stonebreak.world.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issue #341: fishing is a server action. The rod use casts or reels in on the SERVER — the
 * rod state changes on the server's replica (so the sync stops reverting it), the bobber is a
 * server entity, and a catch is rolled and spawned in the SERVER world, never a client one.
 */
class ServerFishingHandlerTest {

    private static final int PLAYER_ID = 9;

    private ServerWorldContext ctx;
    private EntityManager em;
    private ServerPlayer sp;
    private ServerFishingHandler handler;

    @BeforeEach
    void anglerWithARod() {
        World world = mock(World.class);
        em = Mockito.spy(new EntityManager(world));
        ctx = mock(ServerWorldContext.class);
        when(ctx.world()).thenReturn(world);
        when(ctx.entityManager()).thenReturn(em);

        sp = new ServerPlayer(mock(ServerConnection.class), PLAYER_ID);
        sp.updateState(10f, 64f, 10f, 0f, 0f);
        when(ctx.player(PLAYER_ID)).thenReturn(sp);
        sp.inventory().setSlot(0, new ItemStack(ItemType.FISHING_ROD, 1));
        sp.inventory().selectHotbarSlot(0);

        // Each rod use arrives a second after the last, well past the spam limit.
        long[] now = {0L};
        handler = new ServerFishingHandler(new FishingManager(new Random(42)),
                () -> now[0] += 1_000_000_000L);
    }

    /** A rod use from the player's eye, looking along +X. */
    private static FishingRodC2S use() {
        return new FishingRodC2S(10f, 65.6f, 10f, 1f, 0f, 0f, 0);
    }

    private ItemStack rod() {
        return sp.inventory().slot(0);
    }

    private long syncs() {
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(sp.connection(), atLeastOnce()).send(captor.capture(), anyBoolean());
        List<Packet> sent = captor.getAllValues();
        return sent.stream().filter(p -> p instanceof InventorySyncS2C).count();
    }

    /** Lands the bobber in water and waits out the longest bite delay. */
    private static void bite(FishingBobber bobber) {
        bobber.setSubmersion(1f);
        bobber.update(20f);
        assertTrue(bobber.isFishBiting());
    }

    @Test
    void aCastSpawnsTheBobberInTheServerWorldAndCastsTheReplicaRod() {
        handler.handleRodUse(sp, use(), ctx);

        FishingBobber bobber = handler.activeBobber(sp);
        assertNotNull(bobber, "the bobber is a server entity");
        verify(em).spawnBobber(any(), any());
        assertEquals(PLAYER_ID, bobber.getOwnerPlayerId(), "replicated with its owner");
        assertEquals(ItemType.FISHING_ROD_STATE_CAST, rod().getState(),
                "the SERVER's rod is cast, so the sync confirms the cast instead of reverting it");
        assertEquals(1, syncs());
    }

    @Test
    void reelingInOnABiteSpawnsTheCatchInTheServerWorld() {
        handler.handleRodUse(sp, use(), ctx);
        FishingBobber bobber = handler.activeBobber(sp);
        bobber.setPosition(16f, 62f, 10f); // landed 6 blocks out along +X
        bite(bobber);

        handler.handleRodUse(sp, use(), ctx);

        ArgumentCaptor<ItemDrop> drop = ArgumentCaptor.forClass(ItemDrop.class);
        verify(em).addEntity(drop.capture());
        ItemType caught = drop.getValue().getItemType();
        assertTrue(caught == ItemType.BASS || caught == ItemType.STICK, "rolled from the loot table");
        assertTrue(drop.getValue().getVelocity().x < 0f, "flung back toward the angler (at -X)");
        assertFalse(bobber.isAlive(), "the bobber is retired");
        assertNull(handler.activeBobber(sp));
        assertEquals(ItemType.FISHING_ROD_STATE_REELED_IN, rod().getState());
        assertEquals(2, syncs());
    }

    @Test
    void reelingInWithoutABiteCatchesNothing() {
        handler.handleRodUse(sp, use(), ctx);
        FishingBobber bobber = handler.activeBobber(sp);

        handler.handleRodUse(sp, use(), ctx);

        verify(em, never()).addEntity(any());
        assertFalse(bobber.isAlive());
        assertEquals(ItemType.FISHING_ROD_STATE_REELED_IN, rod().getState());
    }

    @Test
    void aUseWithoutARodInHandIsRefusedWithASync() {
        sp.inventory().selectHotbarSlot(1);

        handler.handleRodUse(sp, use(), ctx);

        verify(em, never()).spawnBobber(any(), any());
        assertNull(handler.activeBobber(sp));
        assertEquals(1, syncs(), "the refusal syncs, undoing the client's predicted flip");
    }

    @Test
    void aCastFromFarAwayIsRefused() {
        handler.handleRodUse(sp, new FishingRodC2S(60f, 65f, 10f, 1f, 0f, 0f, 0), ctx);

        verify(em, never()).spawnBobber(any(), any());
        assertNull(rod().getState());
        assertEquals(1, syncs());
    }

    @Test
    void aBobberThatLeftTheWorldReelsTheRodBackIn() {
        handler.handleRodUse(sp, use(), ctx);
        handler.activeBobber(sp).setAlive(false);

        handler.tick(ctx);

        assertNull(handler.activeBobber(sp));
        assertEquals(ItemType.FISHING_ROD_STATE_REELED_IN, rod().getState());
    }

    @Test
    void rodUsesAtPacketRateAreRefused() {
        // Every use spawns or despawns a replicated bobber: a spamming client must not churn them.
        handler = new ServerFishingHandler(new FishingManager(new Random(42)), () -> 5L);
        handler.handleRodUse(sp, use(), ctx);
        FishingBobber bobber = handler.activeBobber(sp);
        handler.handleRodUse(sp, use(), ctx); // same instant: refused, the line stays out

        assertSame(bobber, handler.activeBobber(sp));
        assertEquals(ItemType.FISHING_ROD_STATE_CAST, rod().getState());
        assertEquals(2, syncs(), "a refused use is still answered, so the client undoes its flip");
    }

    @Test
    void theOwnerLeavingRetiresTheBobber() {
        handler.handleRodUse(sp, use(), ctx);
        FishingBobber bobber = handler.activeBobber(sp);
        when(ctx.player(PLAYER_ID)).thenReturn(null);

        handler.tick(ctx);

        assertFalse(bobber.isAlive());
    }
}
