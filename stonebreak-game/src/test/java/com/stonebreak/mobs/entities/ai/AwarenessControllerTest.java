package com.stonebreak.mobs.entities.ai;

import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.StubMob;
import com.stonebreak.mobs.entities.ai.AwarenessController.AwarenessState;
import com.stonebreak.mobs.entities.ai.behavior.PlayerLocator;
import com.stonebreak.mobs.entities.ai.perception.Loudness;
import com.stonebreak.mobs.entities.ai.perception.NoiseBus;
import com.stonebreak.mobs.entities.ai.perception.PerceptionProfile;
import com.stonebreak.mobs.entities.ai.perception.ShoutRelay;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The goblin's perception (#321): sight that fades with distance, angle and light; hearing that
 * fades with distance; one-off sounds; the alertness multiplier; the SEARCHING state and its
 * memory; the pack shout. Every rule here runs against stub players, light and sounds, so the
 * numbers are the profile's and nothing else's.
 */
class AwarenessControllerTest {

    private static final float DT = 0.05f;

    private final StubMob mob = new StubMob(EntityType.GOBLIN, new Vector3f(0, 64, 0));

    /** One player, wherever, however loud and however stealthed the test says. */
    private static final class FakePlayers implements PlayerLocator {
        Vector3f position;
        float loudness;
        boolean stealthed;

        @Override
        public Vector3f nearestPlayer(Vector3f from, Vector3f out) {
            return position == null ? null : out.set(position);
        }

        @Override
        public float nearestPlayerLoudness(Vector3f from) {
            return loudness;
        }

        @Override
        public boolean nearestPlayerStealthed(Vector3f from) {
            return stealthed;
        }
    }

    private final FakePlayers players = new FakePlayers();
    private float light = 1f;
    private long clock;
    private final NoiseBus bus = new NoiseBus(() -> clock);
    private final List<Vector3f> shouts = new ArrayList<>();
    private final ShoutRelay relay = (shouter, lastKnown, range) -> shouts.add(new Vector3f(lastKnown));

    private AwarenessController goblin() {
        return goblin(mob);
    }

    private AwarenessController goblin(StubMob who) {
        return new AwarenessController(who, PerceptionProfile.GOBLIN, players,
                (world, position) -> light, relay, bus);
    }

    private Vector3f ahead(float distance) {
        return new Vector3f(mob.getForwardDirection()).mul(distance).add(mob.getPosition());
    }

    private Vector3f behind(float distance) {
        return ahead(-distance);
    }

    /** {@code degrees} round from straight ahead, {@code distance} away. */
    private Vector3f atAngle(float degrees, float distance) {
        Vector3f f = mob.getForwardDirection();
        double a = Math.toRadians(degrees);
        float x = (float) (f.x * Math.cos(a) - f.z * Math.sin(a));
        float z = (float) (f.x * Math.sin(a) + f.z * Math.cos(a));
        return new Vector3f(x, 0, z).mul(distance).add(mob.getPosition());
    }

    private void run(AwarenessController awareness, float seconds) {
        for (float t = 0; t < seconds - 1e-4f; t += DT) {
            clock += (long) (DT * 1e9);
            awareness.update(DT);
        }
    }

    /** One perception tick's worth of time. */
    private void tick(AwarenessController awareness) {
        run(awareness, 1f / AwarenessController.PERCEPTION_HZ);
    }

    // ── Sight ─────────────────────────────────────────────────────────────────

    @Test
    void nobodyAroundMeansNothingToNotice() {
        AwarenessController awareness = goblin();
        run(awareness, 3f);
        assertEquals(AwarenessState.UNAWARE, awareness.getState());
    }

    @Test
    void aPlayerStandingInFrontInDaylightIsSeenAndAlertsTheGoblin() {
        AwarenessController awareness = goblin();
        players.position = ahead(4f);
        run(awareness, 3f);
        assertEquals(AwarenessState.ALERTED, awareness.getState());
        assertEquals(players.position.x, awareness.lastKnownPlayerPosition(new Vector3f()).x, 1e-4f);
    }

    @Test
    void sightFadesWithDistanceAndEndsAtTheVisionRange() {
        float near = visionAt(ahead(3f));
        float far = visionAt(ahead(10f));
        assertTrue(near > far && far > 0f, "near " + near + " vs far " + far);
        assertEquals(0f, visionAt(ahead(PerceptionProfile.GOBLIN.visionRange() + 0.5f)));
    }

    @Test
    void sightIsFullInTheConeWeakAtTheSidesAndAbsentBehind() {
        float front = visionAt(atAngle(30f, 5f));
        float side = visionAt(atAngle(90f, 5f));
        assertEquals(front * PerceptionProfile.GOBLIN.sideVisionFactor(), side, 1e-3f);
        assertEquals(0f, visionAt(behind(5f)));
    }

    @Test
    void darknessDimsSightButGoblinsSeeFurtherInIt() {
        float lit = visionAt(ahead(5f));
        light = 0f;
        float dark = visionAt(ahead(5f));
        assertTrue(dark > 0f && dark < lit, "night vision only partly ignores darkness");
        assertTrue(visionAt(ahead(14f)) > 0f, "1.5x range in the dark reaches past the daylight 12");
        light = 1f;
        assertEquals(0f, visionAt(ahead(14f)));
    }

    @Test
    void aStealthedPlayerIsHarderToPickOut() {
        float open = visionAt(ahead(5f));
        players.stealthed = true;
        assertEquals(open * AwarenessController.STEALTHED_VISION_MULT, visionAt(ahead(5f)), 1e-3f);
    }

    private float visionAt(Vector3f where) {
        AwarenessController awareness = goblin();
        players.position = where;
        tick(awareness);
        return awareness.snapshot().vision();
    }

    // ── Hearing ───────────────────────────────────────────────────────────────

    @Test
    void sprintingIsNeverStealthyButAWalkBehindTheGoblinGoesUnheard() {
        players.position = behind(10f);
        players.loudness = Loudness.WALK;
        AwarenessController walking = goblin();
        run(walking, 3f);
        assertEquals(AwarenessState.UNAWARE, walking.getState(), "a walk carries 8 blocks to goblin ears");

        players.loudness = Loudness.SPRINT;
        AwarenessController sprinting = goblin();
        run(sprinting, 3f);
        assertTrue(sprinting.getState() != AwarenessState.UNAWARE, "a sprint carries the full 16");
    }

    @Test
    void hearingFadesWithDistance() {
        players.loudness = Loudness.SPRINT;
        players.position = behind(3f);
        AwarenessController near = goblin();
        tick(near);
        players.position = behind(12f);
        AwarenessController far = goblin();
        tick(far);
        assertTrue(near.snapshot().hearing() > far.snapshot().hearing() && far.snapshot().hearing() > 0f);
    }

    @Test
    void aBrokenBlockIsHeardOnceAndPointsTheGoblinAtIt() {
        AwarenessController awareness = goblin();
        tick(awareness); // joins the world: earlier sounds are not its business
        Vector3f smash = behind(6f);
        bus.emit(smash, Loudness.BREAK_BLOCK);
        tick(awareness);
        assertEquals(AwarenessState.SUSPICIOUS, awareness.getState());
        assertEquals(smash.z, awareness.lastKnownPlayerPosition(new Vector3f()).z, 1e-4f);

        float after = awareness.snapshot().meter();
        tick(awareness);
        assertTrue(awareness.snapshot().meter() < after, "heard once, then the meter drains");
    }

    @Test
    void aSoundMadeBeforeTheGoblinWasListeningIsNotHeard() {
        bus.emit(behind(3f), Loudness.DEATH);
        AwarenessController awareness = goblin();
        run(awareness, 1f);
        assertEquals(AwarenessState.UNAWARE, awareness.getState());
    }

    // ── States ────────────────────────────────────────────────────────────────

    @Test
    void anAlreadySuspiciousGoblinNoticesFaster() {
        players.position = ahead(8f);
        AwarenessController awareness = goblin();
        tick(awareness);
        float unawareRate = awareness.snapshot().rate();
        run(awareness, 1f); // past the low suspicion threshold
        assertEquals(AwarenessState.SUSPICIOUS, awareness.getState());
        tick(awareness);
        assertEquals(unawareRate * PerceptionProfile.GOBLIN.alertness().suspicious(),
                awareness.snapshot().rate(), 1e-2f);
    }

    @Test
    void losingThePlayerTurnsAnAlertIntoASearchThatRunsOut() {
        players.position = ahead(4f);
        AwarenessController awareness = goblin();
        run(awareness, 3f);
        assertEquals(AwarenessState.ALERTED, awareness.getState());

        players.position = null; // gone round a corner, out of sight and earshot
        run(awareness, AwarenessController.LOST_TRACK_SECONDS + 0.3f);
        assertEquals(AwarenessState.SEARCHING, awareness.getState());
        assertNotNull(awareness.lastKnownPlayerPosition(new Vector3f()), "searches where it lost them");

        run(awareness, PerceptionProfile.GOBLIN.memorySeconds() + 0.5f);
        assertEquals(AwarenessState.UNAWARE, awareness.getState(), "memory expired: back to its business");
        assertNull(awareness.lastKnownPlayerPosition(new Vector3f()));
    }

    @Test
    void aSearchingGoblinThatSpotsThePlayerIsAlertedAgainAtOnce() {
        players.position = ahead(4f);
        AwarenessController awareness = goblin();
        run(awareness, 3f);
        players.position = null;
        run(awareness, 2f);
        assertEquals(AwarenessState.SEARCHING, awareness.getState());

        players.position = ahead(10f);
        tick(awareness);
        assertEquals(AwarenessState.ALERTED, awareness.getState());
    }

    @Test
    void beingHitAlertsAtOnceTowardTheAttacker() {
        AwarenessController awareness = goblin();
        Vector3f attacker = behind(2f);
        awareness.onAttacked(attacker);
        assertEquals(AwarenessState.ALERTED, awareness.getState());
        assertEquals(attacker.z, awareness.lastKnownPlayerPosition(new Vector3f()).z, 1e-4f);
    }

    @Test
    void perceptionRunsAtFiveHertzNotEveryFrame() {
        players.position = ahead(4f);
        AwarenessController awareness = goblin();
        awareness.update(0.1f);
        assertEquals(0f, awareness.snapshot().meter(), "half a period: nothing perceived yet");
        awareness.update(0.1f);
        assertTrue(awareness.snapshot().meter() > 0f);
    }

    @Test
    void listenersHearEveryChangeAndReplicationTellsThemNothing() {
        AwarenessController awareness = goblin();
        List<AwarenessState> seen = new ArrayList<>();
        awareness.addListener((who, from, to, byShout) -> seen.add(to));
        players.position = ahead(4f);
        run(awareness, 3f);
        assertEquals(List.of(AwarenessState.SUSPICIOUS, AwarenessState.ALERTED), seen);

        awareness.applyReplicatedState(AwarenessState.SEARCHING);
        assertEquals(AwarenessState.SEARCHING, awareness.getState());
        assertEquals(2, seen.size());
    }

    // ── The pack ──────────────────────────────────────────────────────────────

    @Test
    void anAlertedGoblinShoutsWhereThePlayerWas() {
        players.position = ahead(4f);
        AwarenessController awareness = goblin();
        run(awareness, 3f);
        assertEquals(1, shouts.size());
        assertEquals(players.position.z, shouts.get(0).z, 1e-4f);
    }

    @Test
    void aPackmateThatCannotSeeThePlayerComesToLookAndDoesNotShoutOn() {
        AwarenessController mate = goblin();
        players.position = behind(6f); // behind the mate: it cannot see them
        Vector3f reported = new Vector3f(1, 64, 9);
        mate.hearShout(reported);
        assertEquals(AwarenessState.SUSPICIOUS, mate.getState());
        assertEquals(reported.z, mate.lastKnownPlayerPosition(new Vector3f()).z, 1e-4f);
        assertTrue(shouts.isEmpty());
    }

    @Test
    void aPackmateThatCanSeeThePlayerIsAlertedButTheShoutStopsThere() {
        AwarenessController mate = goblin();
        players.position = ahead(5f);
        mate.hearShout(new Vector3f(1, 64, 9));
        assertEquals(AwarenessState.ALERTED, mate.getState());
        assertTrue(shouts.isEmpty(), "a shout-made alert does not chain across the map");
    }
}
