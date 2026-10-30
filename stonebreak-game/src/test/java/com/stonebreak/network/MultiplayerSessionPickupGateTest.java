package com.stonebreak.network;

import com.openmason.engine.net.server.ServerConnection;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.network.packet.player.PlayerLifeC2S;
import com.stonebreak.network.server.ServerInventory;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.packet.player.PlayerHealthS2C;
import com.stonebreak.network.server.handlers.ServerHealthHandler;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

/**
 * Dead players must not receive drop pickups (#357): the server decides deaths itself (#343)
 * and the nearest-player selection in
 * {@code MultiplayerSession.tryServerPickup} skips dead players, so death drops stay in the
 * world until the player respawns (away from them). A player with no room for the item is
 * skipped too (#372): it no longer takes the drop and throws the overflow ahead of itself.
 */
class MultiplayerSessionPickupGateTest {

    private static final Vector3f DROP = new Vector3f(0f, 0f, 0f);
    private static final int STICK = ItemType.STICK.getId();

    private static ServerPlayer newPlayer(int id, String name, float x) {
        ServerPlayer sp = new ServerPlayer(mock(ServerConnection.class), -1);
        sp.setUsername(name);
        sp.assignPlayerId(id);
        sp.markHandshakeDone();
        sp.updateState(x, 0f, 0f, 0f, 0f);
        return sp;
    }

    /** The world spawn the respawn test lands at (the server puts a respawn there, #343). */
    private static final Vector3f SPAWN = new Vector3f(50f, 0f, 0f);

    private static com.stonebreak.network.server.ServerWorldContext ctx() {
        com.stonebreak.network.server.ServerWorldContext ctx =
                mock(com.stonebreak.network.server.ServerWorldContext.class);
        org.mockito.Mockito.when(ctx.spawn()).thenReturn(SPAWN);
        return ctx;
    }

    private static void kill(ServerPlayer sp) {
        new ServerHealthHandler().hurt(sp, sp.health(), PlayerHealthS2C.CAUSE_MOB, System.nanoTime(), ctx());
    }

    @Test
    void deadNearestPlayerIsSkipped() {
        ServerPlayer dead = newPlayer(1, "Dead", 0f);
        kill(dead);
        ServerPlayer alive = newPlayer(2, "Alive", 1f); // farther, but alive

        assertSame(alive, MultiplayerSession.nearestEligiblePickupCandidate(
                List.of(dead, alive), DROP, 1.5f, STICK));
    }

    @Test
    void allDeadYieldsNoCandidate() {
        ServerPlayer dead = newPlayer(1, "Dead", 0f);
        kill(dead);

        assertNull(MultiplayerSession.nearestEligiblePickupCandidate(List.of(dead), DROP, 1.5f, STICK));
    }

    @Test
    void respawnedPlayerIsEligibleAgainAtItsNewPosition() {
        ServerPlayer sp = newPlayer(1, "Back", 0f);
        kill(sp);
        // The server respawns the player at the world spawn, whatever the request says.
        new ServerHealthHandler().handleLife(sp, new PlayerLifeC2S(false, 0f, 0f, 0f), ctx(), System.nanoTime());

        assertNull(MultiplayerSession.nearestEligiblePickupCandidate(List.of(sp), DROP, 1.5f, STICK),
                "respawned away from the corpse, so the death drop is out of range");
        assertSame(sp, MultiplayerSession.nearestEligiblePickupCandidate(
                List.of(sp), new Vector3f(50f, 0f, 0f), 1.5f, STICK));
    }

    @Test
    void nearestAlivePlayerStillSelected() {
        ServerPlayer a = newPlayer(1, "A", 1f);
        ServerPlayer b = newPlayer(2, "B", 0.5f); // nearest

        assertSame(b, MultiplayerSession.nearestEligiblePickupCandidate(List.of(a, b), DROP, 1.5f, STICK));
    }

    @Test
    void unreportedPlayerNeverWinsAtTheDefaultOrigin() {
        ServerPlayer sp = new ServerPlayer(mock(ServerConnection.class), 1);
        sp.markHandshakeDone(); // no PlayerStateC2S yet: sits at (0,0,0) by default

        assertNull(MultiplayerSession.nearestEligiblePickupCandidate(List.of(sp), DROP, 1.5f, STICK));
    }

    private static void fill(ServerPlayer sp) {
        for (int i = 0; i < ServerInventory.TOTAL_SLOTS; i++) {
            sp.inventory().setSlot(i, new ItemStack(ItemType.BANANA, 1));
        }
    }

    @Test
    void fullNearestPlayerGivesWayToOneWithRoom() {
        ServerPlayer full = newPlayer(1, "Full", 0f);
        fill(full);
        ServerPlayer roomy = newPlayer(2, "Roomy", 1f); // farther, but has room

        assertSame(roomy, MultiplayerSession.nearestEligiblePickupCandidate(
                List.of(full, roomy), DROP, 1.5f, STICK));
    }

    @Test
    void fullPlayerAloneLeavesTheDropWhereItLies() {
        ServerPlayer full = newPlayer(1, "Full", 0f);
        fill(full);

        assertNull(MultiplayerSession.nearestEligiblePickupCandidate(List.of(full), DROP, 1.5f, STICK));
    }

    @Test
    void fullInventoryWithAPartialMatchingStackStillQualifies() {
        ServerPlayer sp = newPlayer(1, "Almost", 0f);
        fill(sp);
        sp.inventory().setSlot(5, new ItemStack(ItemType.STICK, 60));

        assertSame(sp, MultiplayerSession.nearestEligiblePickupCandidate(List.of(sp), DROP, 1.5f, STICK));
        assertEquals(4, sp.inventory().roomFor(STICK, 10), "only what tops up the stick stack fits");
    }
}
