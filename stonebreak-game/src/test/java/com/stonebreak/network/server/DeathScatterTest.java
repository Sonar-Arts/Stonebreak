package com.stonebreak.network.server;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.server.ServerConnection;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.network.packet.player.DropItemC2S;
import com.stonebreak.network.packet.player.InventorySyncS2C;
import com.stonebreak.network.packet.player.PlayerHealthS2C;
import com.stonebreak.network.packet.player.PlayerLifeC2S;
import com.stonebreak.network.server.handlers.ServerHealthHandler;
import com.stonebreak.network.server.handlers.ServerPlayerHandler;
import com.stonebreak.world.World;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The death scatter (issues #334, #343): at the death the SERVER decides, its own replica — 36
 * slots, craft grid, carried cursor — is scattered regardless of the toss rate limit (a death
 * legitimately exceeds the toss budget); nothing is drained when it cannot be spawned. A death
 * the client claims is honoured at most once per toss window (#420).
 */
class DeathScatterTest {

    private record Fakes(ServerWorldContext ctx, World world, EntityManager em) {}

    private final ServerHealthHandler health = new ServerHealthHandler();

    private static Fakes fakes() {
        ServerWorldContext ctx = mock(ServerWorldContext.class);
        World world = mock(World.class);
        EntityManager em = Mockito.spy(new EntityManager(world));
        when(ctx.world()).thenReturn(world);
        when(ctx.entityManager()).thenReturn(em);
        when(ctx.spawn()).thenReturn(new Vector3f(0f, 80f, 0f));
        return new Fakes(ctx, world, em);
    }

    private static ServerPlayer newPlayer() {
        ServerPlayer sp = new ServerPlayer(mock(ServerConnection.class), 7);
        sp.markHandshakeDone();
        sp.updateState(10.5f, 65f, 10.5f, 0f, 0f); // a scatter needs a reported position
        return sp;
    }

    private void killWithABlow(ServerPlayer sp, ServerWorldContext ctx) {
        health.hurt(sp, 1000f, PlayerHealthS2C.CAUSE_MOB, System.nanoTime(), ctx);
    }

    private static long syncCount(ServerPlayer sp) {
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(sp.connection(), atLeastOnce()).send(captor.capture(), anyBoolean());
        return captor.getAllValues().stream().filter(p -> p instanceof InventorySyncS2C).count();
    }

    @Test
    void aDeathScattersEveryStackRegardlessOfTheTossRateLimit() {
        Fakes f = fakes();
        ServerPlayer sp = newPlayer();
        while (sp.allowDrop()) {
            // exhaust the toss budget exactly as a spamming client would
        }
        for (int i = 0; i < ServerInventory.TOTAL_SLOTS; i++) {
            sp.inventory().setSlot(i, new ItemStack(ItemType.STICK, i + 1));
        }

        killWithABlow(sp, f.ctx());

        assertTrue(sp.isDead());
        verify(f.em(), times(ServerInventory.TOTAL_SLOTS)).addEntity(any());
        for (int i = 0; i < ServerInventory.TOTAL_SLOTS; i++) {
            assertTrue(sp.inventory().slot(i).isEmpty(), "slot " + i + " was scattered");
        }
        assertTrue(syncCount(sp) >= 1, "the scatter syncs the cleared mirror");
    }

    @Test
    void aDeathScattersTheCarriedStackAndCraftGridToo() {
        Fakes f = fakes();
        ServerPlayer sp = newPlayer();
        for (int i = 0; i < ServerInventory.TOTAL_SLOTS; i++) {
            sp.inventory().setSlot(i, new ItemStack(ItemType.STICK, 1));
        }
        sp.inventory().setCarried(new ItemStack(ItemType.STICK, 5));
        sp.inventory().setCraftSlot(2, new ItemStack(ItemType.STICK, 7));

        killWithABlow(sp, f.ctx());

        // 36 slots + carried cursor + one craft-grid cell
        verify(f.em(), times(ServerInventory.TOTAL_SLOTS + 2)).addEntity(any());
        assertTrue(sp.inventory().carried().isEmpty(), "the carried stack was scattered");
        for (int i = 0; i < ServerInventory.CRAFT_GRID_SIZE; i++) {
            assertTrue(sp.inventory().craftSlot(i).isEmpty(), "grid cell " + i + " was scattered");
        }
    }

    @Test
    void aWorldThatCannotSpawnDropsKeepsTheInventory() {
        ServerWorldContext ctx = mock(ServerWorldContext.class); // world() == null
        ServerPlayer sp = newPlayer();
        sp.inventory().add(ItemType.STICK.getId(), 10);

        ServerPlayerHandler.scatterInventory(sp, ctx);

        assertEquals(10, sp.inventory().countOf(ItemType.STICK.getId()), "no debit without a spawn");
        assertEquals(1, syncCount(sp), "the intact inventory was re-asserted");
    }

    @Test
    void claimedDeathsAreThrottledByTheTossWindow() {
        Fakes f = fakes();
        ServerPlayer sp = newPlayer();
        sp.inventory().add(ItemType.STICK.getId(), 1);
        health.handleLife(sp, new PlayerLifeC2S(true, sp.x(), sp.y(), sp.z()), f.ctx(), System.nanoTime());
        assertTrue(sp.isDead(), "a client may give its life up");

        // Respawn and "die" again straight away, as a modified client could.
        health.handleLife(sp, new PlayerLifeC2S(false, 0f, 0f, 0f), f.ctx(), System.nanoTime());
        sp.inventory().add(ItemType.STICK.getId(), 3);
        health.handleLife(sp, new PlayerLifeC2S(true, sp.x(), sp.y(), sp.z()), f.ctx(), System.nanoTime());

        assertTrue(sp.isLiveInWorld(), "the second claim inside the toss window was refused");
        verify(f.em(), times(1)).addEntity(any());
        assertEquals(3, sp.inventory().countOf(ItemType.STICK.getId()));
    }

    @Test
    void ordinaryTossesStayRateLimitedNextToTheDeathPath() {
        Fakes f = fakes();
        ServerPlayer sp = newPlayer();
        ServerPlayerHandler handler = new ServerPlayerHandler();
        sp.inventory().add(ItemType.STICK.getId(), 1000);
        for (int i = 0; i < 25; i++) {
            handler.handleDropItem(sp, new DropItemC2S(ItemType.STICK.getId(), 1), f.ctx());
        }

        assertEquals(980, sp.inventory().countOf(ItemType.STICK.getId()),
            "the death scatter did not loosen the toss budget");
    }
}
