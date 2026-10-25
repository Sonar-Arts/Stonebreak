package com.stonebreak.network.server;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.mobs.entities.Arrow;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.StubMob;
import com.stonebreak.mobs.entities.ai.behavior.AiContext;
import com.stonebreak.mobs.entities.ai.behavior.FleeBehavior;
import com.stonebreak.mobs.entities.ai.behavior.PlayerLocator;
import com.stonebreak.mobs.entities.ai.perception.Loudness;
import com.stonebreak.network.packet.player.PlayerHurtS2C;
import com.stonebreak.network.packet.player.PlayerStateFlags;
import com.stonebreak.world.TestWorld;
import com.stonebreak.world.World;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
        remote.updateState(6.5f, 64, 0, 0f, 0f); // and it is actually moving
        assertEquals(Loudness.SPRINT, locator.nearestPlayerLoudness(goose.getPosition()), 1e-4f,
                "a sprinter is audible to stealth awareness");
    }

    @Test
    void theRosterReportsHowLoudAndHowHiddenThePlayerIs() {
        ServerPlayer player = joined(1, 3, 0);
        assertEquals(0f, locator.nearestPlayerLoudness(goose.getPosition()), "standing still is silent");

        player.updateState(3.5f, 64, 0, 0f, 0f);
        assertEquals(Loudness.WALK, locator.nearestPlayerLoudness(goose.getPosition()), 1e-4f);
        assertFalse(locator.nearestPlayerStealthed(goose.getPosition()));

        player.setMovementFlags((byte) PlayerStateFlags.STEALTHED);
        assertEquals(Loudness.STEALTH, locator.nearestPlayerLoudness(goose.getPosition()), 1e-4f);
        assertTrue(locator.nearestPlayerStealthed(goose.getPosition()));

        player.setMovementFlags((byte) (PlayerStateFlags.STEALTHED | PlayerStateFlags.DODGE_NOISE));
        assertEquals(Loudness.DODGE, locator.nearestPlayerLoudness(goose.getPosition()), 1e-4f,
                "a dodge's scuff gives a stealthed player away");
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
    // ── Mob blows (goblins hurting players) ────────────────────────────────

    private final List<ServerPlayer> hurtWho = new ArrayList<>();
    private final List<PlayerHurtS2C> hurts = new ArrayList<>();
    private final ServerPlayerLocator hitting = new ServerPlayerLocator(roster, null,
            (player, hurt) -> { hurtWho.add(player); hurts.add(hurt); });

    @Test
    void aSwingLandsOnTheNearestPlayerAndShovesThemAway() {
        joined(1, 40, 0);
        ServerPlayer close = joined(2, 2, 0);

        assertTrue(hitting.hurtNearestPlayer(goose.getPosition(), 3f, 6f));
        assertSame(close, hurtWho.get(0), "the player in reach, not the host far off");
        assertEquals(3f, hurts.get(0).amount(), 1e-6f);
        assertEquals(6f, hurts.get(0).knockbackX(), 1e-4f, "shoved directly away from the mob");
        assertEquals(0f, hurts.get(0).knockbackZ(), 1e-4f);
    }

    @Test
    void nobodyInTheWorldTakesNoBlow() {
        ServerPlayer dead = joined(1, 1, 0);
        dead.setDead(true);
        assertFalse(hitting.hurtNearestPlayer(goose.getPosition(), 3f, 6f));
        assertTrue(hurts.isEmpty());
    }

    @Test
    void anArrowHurtsOnlyThePlayerItIsInside() {
        joined(1, 0, 0);
        ServerPlayer target = joined(2, 10, 0);

        assertFalse(hitting.hurtPlayerAt(new Vector3f(10, 66.5f, 3), 0.1f, 4f), "a clean miss");
        assertTrue(hitting.hurtPlayerAt(new Vector3f(10.2f, 65f, 0), 0.1f, 4f));
        assertSame(target, hurtWho.get(0));
        assertEquals(0f, hurts.get(0).knockbackX(), "arrows sting, they do not shove");
    }

    @Test
    void aMobArrowOnTheServerHurtsTheConnectedPlayerItHits() {
        ServerPlayer target = joined(1, 5, 0);
        World world = mock(World.class); // getBlockAt → null: nothing to stop the arrow
        when(world.getPlayerLocator()).thenReturn(hitting);
        StubMob goblin = new StubMob(EntityType.GOBLIN, new Vector3f(0, 64, 0), world);
        Arrow arrow = new Arrow(world, new Vector3f(5, 65, 0), new Vector3f(1, 0, 0)).firedBy(goblin, 4f);

        arrow.update(0.01f);

        assertFalse(arrow.isAlive(), "spent on the hit");
        assertSame(target, hurtWho.get(0));
        assertEquals(4f, hurts.get(0).amount(), 1e-6f);
    }

    @Test
    void aWallBetweenTheArcherAndThePlayerBlocksTheShot() {
        joined(1, 6, 0);
        World world = mock(World.class);
        when(world.getAnimatedBlockRegistry()).thenReturn(new com.stonebreak.blocks.anim.AnimatedBlockRegistry());
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call ->
                (int) call.getArgument(0) == 3 ? BlockType.STONE : BlockType.AIR);
        ServerPlayerLocator sighted = new ServerPlayerLocator(roster, world, (player, hurt) -> { });
        Vector3f eye = new Vector3f(0, 65, 0);

        assertFalse(sighted.canSeeNearestPlayer(goose.getPosition(), eye));
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(BlockType.AIR);
        assertTrue(sighted.canSeeNearestPlayer(goose.getPosition(), eye));
    }
}
