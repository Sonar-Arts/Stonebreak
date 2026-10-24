package com.stonebreak.network.server;

import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.StubMob;
import com.stonebreak.mobs.entities.ai.behavior.AiContext;
import com.stonebreak.mobs.entities.ai.behavior.FleeBehavior;
import com.stonebreak.mobs.entities.ai.behavior.PlayerLocator;
import com.stonebreak.network.packet.player.PlayerStateFlags;
import com.stonebreak.world.TestWorld;
import com.stonebreak.world.operations.WorldConfiguration;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mobs on the authoritative world see the connected roster, not the host's client player (#340).
 * The old locator read {@code Game.getPlayer()}: from the server thread, and only ever the host, so
 * a remote player could sprint straight at a goose and never be noticed.
 */
class ServerPlayerLocatorTest {

    // Goose-like skittishness: noticed at 5 walking, 7 sprinting, calm again past 11.
    private static final float WALK_TRIGGER = 5.0f;
    private static final float SPRINT_TRIGGER = 7.0f;
    private static final float SAFE_RANGE = 11.0f;

    private final List<ServerPlayer> roster = new ArrayList<>();
    private final ServerPlayerLocator locator = new ServerPlayerLocator(roster);
    private final StubMob goose = new StubMob(EntityType.GOOSE, new Vector3f(0, 64, 0));

    private ServerPlayer joined(int id, float x, float z) {
        ServerPlayer player = new ServerPlayer(null, id);
        player.markHandshakeDone();
        player.updateState(x, 64, z, 0f, 0f);
        roster.add(player);
        return player;
    }

    private FleeBehavior skittish() {
        return new FleeBehavior(10.0f, 4.0f, 1.0f, WALK_TRIGGER, SPRINT_TRIGGER, SAFE_RANGE);
    }

    @Test
    void aRemotePlayerCloseByStartsAFlightWhileTheHostIsFarAway() {
        joined(1, 200, 200);     // the host, nowhere near
        joined(2, 3, 0);         // a remote player walking up to the goose
        AiContext context = new AiContext(goose, null, new Random(1), locator);

        assertTrue(skittish().canStart(context), "the remote player is the nearest player");
        assertEquals(3.0f, context.distanceToNearestPlayer(), 1e-4f);
    }

    @Test
    void aSprintingRemotePlayerIsNoticedFromFurtherOff() {
        joined(1, 200, 200);
        ServerPlayer remote = joined(2, 6, 0); // between the walk and sprint trigger ranges
        AiContext context = new AiContext(goose, null, new Random(1), locator);
        FleeBehavior flee = skittish();

        assertFalse(flee.canStart(context), "walking at this range goes unnoticed");

        remote.setMovementFlags((byte) PlayerStateFlags.SPRINTING);
        assertTrue(flee.canStart(context), "the reported sprint flag carries the trigger further");
        assertEquals(com.stonebreak.player.PlayerConstants.NOISE_RADIUS_RUN,
                locator.nearestPlayerNoiseRadius(goose.getPosition()), 1e-4f,
                "and a sprinter is audible to stealth awareness");
    }

    @Test
    void playersNotStandingInTheWorldAreInvisible() {
        ServerPlayer handshaking = new ServerPlayer(null, 3); // at (0,0,0), no handshake yet
        roster.add(handshaking);
        ServerPlayer dead = joined(4, 1, 0);
        dead.setDead(true);

        assertNull(locator.nearestPlayer(goose.getPosition(), new Vector3f()));

        joined(5, 9, 0);
        Vector3f nearest = locator.nearestPlayer(goose.getPosition(), new Vector3f());
        assertNotNull(nearest);
        assertEquals(9.0f, nearest.x, 1e-4f, "only the live player counts, though it is furthest");
    }

    /**
     * The production wiring: MobAI's default locator resolves through the mob's own world, which
     * the integrated server hands the roster locator. A world nobody wired sees no players at all.
     */
    @Test
    void mobsSeeWhateverTheirWorldIsGiven() {
        TestWorld world = new TestWorld(new WorldConfiguration(8, 4), 1L, true);
        StubMob mob = new StubMob(EntityType.COW, new Vector3f(0, 64, 0), world);
        PlayerLocator viaWorld = PlayerLocator.ofWorld(mob);

        assertSame(PlayerLocator.NONE, world.getPlayerLocator(), "render/test worlds see nobody");
        joined(2, 4, 0);
        assertNull(viaWorld.nearestPlayer(mob.getPosition(), new Vector3f()));

        world.setPlayerLocator(locator);
        Vector3f nearest = viaWorld.nearestPlayer(mob.getPosition(), new Vector3f());
        assertNotNull(nearest, "resolved lazily, so wiring after the mob was built still counts");
        assertEquals(4.0f, nearest.x, 1e-4f);
    }
}
