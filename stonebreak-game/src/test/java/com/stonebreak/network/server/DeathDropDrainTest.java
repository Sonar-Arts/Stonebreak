package com.stonebreak.network.server;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.server.ServerConnection;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.network.packet.player.DeathDropC2S;
import com.stonebreak.network.packet.player.DropItemC2S;
import com.stonebreak.network.packet.player.InventorySyncS2C;
import com.stonebreak.network.packet.player.PlayerLifeC2S;
import com.stonebreak.network.server.handlers.ServerPlayerHandler;
import com.stonebreak.world.World;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The death drain (issue #334): one {@code DeathDropC2S} scatters the server's OWN replica
 * — 36 slots, craft grid, carried cursor — regardless of the toss rate limit (a death
 * legitimately exceeds the toss budget), and nothing is drained when it cannot be spawned.
 * Only a player the server holds dead may drain, once per death and at most once per toss
 * window (#420).
 */
class DeathDropDrainTest {

    private record Fakes(ServerWorldContext ctx, World world, EntityManager em) {}

    private static Fakes fakes() {
        ServerWorldContext ctx = mock(ServerWorldContext.class);
        World world = mock(World.class);
        EntityManager em = Mockito.spy(new EntityManager(world));
        when(ctx.world()).thenReturn(world);
        when(ctx.entityManager()).thenReturn(em);
        return new Fakes(ctx, world, em);
    }

    private static ServerPlayer newPlayer() {
        ServerPlayer sp = new ServerPlayer(mock(ServerConnection.class), 7);
        sp.updateState(10.5f, 65f, 10.5f, 0f, 0f); // handleDeathDrop needs a reported position
        return sp;
    }

    /** The client's reliable death edge, which it sends before {@code DeathDropC2S}. */
    private static ServerPlayer deadPlayer() {
        ServerPlayer sp = newPlayer();
        new ServerPlayerHandler().handlePlayerLife(sp, new PlayerLifeC2S(true, sp.x(), sp.y(), sp.z()));
        return sp;
    }

    private static List<Packet> sent(ServerPlayer sp) {
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(sp.connection(), atLeastOnce()).send(captor.capture(), anyBoolean());
        return captor.getAllValues();
    }

    private static long syncCount(ServerPlayer sp) {
        return sent(sp).stream().filter(p -> p instanceof InventorySyncS2C).count();
    }

    @Test
    void deathDrainsEveryStackRegardlessOfTheTossRateLimit() {
        Fakes f = fakes();
        ServerPlayer sp = deadPlayer();
        ServerPlayerHandler handler = new ServerPlayerHandler();
        while (sp.allowDrop()) {
            // exhaust the toss budget exactly as a spamming client would
        }
        for (int i = 0; i < ServerInventory.TOTAL_SLOTS; i++) {
            sp.inventory().setSlot(i, new ItemStack(ItemType.STICK, i + 1));
        }

        handler.handleDeathDrop(sp, new DeathDropC2S(), f.ctx());

        verify(f.em(), times(ServerInventory.TOTAL_SLOTS)).addEntity(any());
        for (int i = 0; i < ServerInventory.TOTAL_SLOTS; i++) {
            assertTrue(sp.inventory().slot(i).isEmpty(), "slot " + i + " was drained");
        }
        assertTrue(syncCount(sp) >= 1, "the drain syncs the cleared mirror");
    }

    @Test
    void deathDrainsCarriedAndCraftGridToo() {
        Fakes f = fakes();
        ServerPlayer sp = deadPlayer();
        for (int i = 0; i < ServerInventory.TOTAL_SLOTS; i++) {
            sp.inventory().setSlot(i, new ItemStack(ItemType.STICK, 1));
        }
        sp.inventory().setCarried(new ItemStack(ItemType.STICK, 5));
        sp.inventory().setCraftSlot(2, new ItemStack(ItemType.STICK, 7));

        new ServerPlayerHandler().handleDeathDrop(sp, new DeathDropC2S(), f.ctx());

        // 36 slots + carried cursor + one craft-grid cell
        verify(f.em(), times(ServerInventory.TOTAL_SLOTS + 2)).addEntity(any());
        for (int i = 0; i < ServerInventory.TOTAL_SLOTS; i++) {
            assertTrue(sp.inventory().slot(i).isEmpty());
        }
        assertTrue(sp.inventory().carried().isEmpty(), "the carried stack was drained");
        for (int i = 0; i < ServerInventory.CRAFT_GRID_SIZE; i++) {
            assertTrue(sp.inventory().craftSlot(i).isEmpty(), "grid cell " + i + " was drained");
        }
    }

    @Test
    void deathDropWithoutASpawnableWorldKeepsTheInventory() {
        ServerWorldContext ctx = mock(ServerWorldContext.class); // world() == null
        ServerPlayer sp = deadPlayer();
        sp.inventory().add(ItemType.STICK.getId(), 10);

        new ServerPlayerHandler().handleDeathDrop(sp, new DeathDropC2S(), ctx);

        assertEquals(10, sp.inventory().countOf(ItemType.STICK.getId()),
            "nothing drained — the respawned player keeps the items");
        assertEquals(1, syncCount(sp), "the intact inventory was re-asserted");

        // The refused drain did not use up this death's claim.
        Fakes f = fakes();
        new ServerPlayerHandler().handleDeathDrop(sp, new DeathDropC2S(), f.ctx());
        assertEquals(0, sp.inventory().countOf(ItemType.STICK.getId()), "drained once spawnable");
    }

    @Test
    void livingPlayerCannotDrainItsInventory() {
        Fakes f = fakes();
        ServerPlayer sp = newPlayer(); // never reported a death
        sp.inventory().add(ItemType.STICK.getId(), 10);

        new ServerPlayerHandler().handleDeathDrop(sp, new DeathDropC2S(), f.ctx());

        verify(f.em(), never()).addEntity(any());
        assertEquals(10, sp.inventory().countOf(ItemType.STICK.getId()), "nothing scattered");
        assertEquals(1, syncCount(sp), "the refusal re-asserts the inventory");
    }

    @Test
    void oneDrainPerDeath() {
        Fakes f = fakes();
        ServerPlayer sp = deadPlayer();
        ServerPlayerHandler handler = new ServerPlayerHandler();
        sp.inventory().add(ItemType.STICK.getId(), 1);
        handler.handleDeathDrop(sp, new DeathDropC2S(), f.ctx());

        // Still dead: the replica refills (e.g. a later sync), and a second request arrives.
        sp.inventory().add(ItemType.STICK.getId(), 4);
        handler.handleDeathDrop(sp, new DeathDropC2S(), f.ctx());

        verify(f.em(), times(1)).addEntity(any());
        assertEquals(4, sp.inventory().countOf(ItemType.STICK.getId()), "the second request was refused");
    }

    @Test
    void togglingDeadAndAliveIsThrottledByTheTossWindow() {
        Fakes f = fakes();
        ServerPlayer sp = deadPlayer();
        ServerPlayerHandler handler = new ServerPlayerHandler();
        sp.inventory().add(ItemType.STICK.getId(), 1);
        handler.handleDeathDrop(sp, new DeathDropC2S(), f.ctx());

        // Respawn and "die" again straight away, as a modified client could.
        handler.handlePlayerLife(sp, new PlayerLifeC2S(false, sp.x(), sp.y(), sp.z()));
        handler.handlePlayerLife(sp, new PlayerLifeC2S(true, sp.x(), sp.y(), sp.z()));
        sp.inventory().add(ItemType.STICK.getId(), 3);
        handler.handleDeathDrop(sp, new DeathDropC2S(), f.ctx());

        verify(f.em(), times(1)).addEntity(any());
        assertEquals(3, sp.inventory().countOf(ItemType.STICK.getId()),
            "a second drain inside the toss window is refused");
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
            "the death drain did not loosen the toss budget");
    }
}
