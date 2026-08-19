package com.stonebreak.mobs.entities.ai.behavior;

import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.StubMob;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The trigger and hysteresis rules that decide when a mob panics and when it calms down. The
 * scheduler around them is covered by {@link BehaviorControllerTest}; what was unpinned is the
 * contract that makes fleeing feel right — noticed at the trigger range (further for a sprinting
 * player), armed by damage regardless of distance, and, once running, not released until the
 * player is properly clear, so a mob at the threshold does not stutter in and out of panic.
 */
class FleeBehaviorTest {

    private static final float WALK_TRIGGER = 6.0f;
    private static final float SPRINT_TRIGGER = 12.0f;
    private static final float SAFE_RANGE = 16.0f;

    /** A player wherever the test puts one, or nowhere. */
    private static final class FakePlayers implements PlayerLocator {
        Vector3f position;
        boolean sprinting;

        @Override
        public Vector3f nearestPlayer(Vector3f from, Vector3f out) {
            return position == null ? null : out.set(position);
        }

        @Override
        public boolean nearestPlayerSprinting(Vector3f from) {
            return sprinting;
        }
    }

    private final FakePlayers players = new FakePlayers();
    private final StubMob mob = new StubMob(EntityType.COW, new Vector3f(0, 64, 0));
    private final AiContext context = new AiContext(mob, null, new Random(1), players);

    private FleeBehavior skittish() {
        return new FleeBehavior(16.0f, 5.0f, 1.4f, WALK_TRIGGER, SPRINT_TRIGGER, SAFE_RANGE);
    }

    private void playerAt(float distance) {
        players.position = new Vector3f(distance, 64, 0);
    }

    @Test
    void aDistantPlayerDoesNotStartAFlight() {
        playerAt(SAFE_RANGE + 10.0f);

        assertFalse(skittish().canStart(context));
    }

    @Test
    void aPlayerInsideTheTriggerRangeDoes() {
        playerAt(WALK_TRIGGER - 1.0f);

        assertTrue(skittish().canStart(context));
    }

    @Test
    void aSprintingPlayerIsNoticedFromFurtherAway() {
        playerAt((WALK_TRIGGER + SPRINT_TRIGGER) / 2.0f); // too far for a walker, not for a sprinter

        FleeBehavior flee = skittish();
        assertFalse(flee.canStart(context), "a walking player at this range goes unnoticed");

        players.sprinting = true;
        assertTrue(flee.canStart(context), "the same distance sprinting starts the flight");
    }

    /**
     * The hysteresis band: between the trigger and the safe range, a flight that is not running
     * does not start — but one that is running does not stop. Without the gap, a mob at the
     * threshold would flicker between fleeing and grazing every tick.
     */
    @Test
    void betweenTriggerAndSafeRangeAFlightContinuesButNeverStarts() {
        playerAt((WALK_TRIGGER + SAFE_RANGE) / 2.0f);

        FleeBehavior flee = skittish();
        assertFalse(flee.canStart(context));
        assertTrue(flee.shouldContinue(context),
                "a running flight holds until the player is past the safe range");
    }

    @Test
    void aFlightEndsOnceThePlayerIsProperlyClear() {
        playerAt(SAFE_RANGE + 5.0f);

        assertFalse(skittish().shouldContinue(context));
    }

    @Test
    void livestockOnlyFleeWhenActuallyHurt() {
        FleeBehavior placid = new FleeBehavior(16.0f, 5.0f, 1.4f); // no proximity triggers
        playerAt(1.0f);

        assertFalse(placid.canStart(context), "standing right next to it is fine");

        placid.onDamaged(context, 2.0f);
        assertTrue(placid.canStart(context), "being hit arms the flight");
        assertTrue(placid.shouldContinue(context), "and the panic timer keeps it running");
    }

    @Test
    void damageFromAnUnseenAttackerStillArmsTheFlight() {
        players.position = null; // hurt by something with nobody around to blame

        FleeBehavior placid = new FleeBehavior(16.0f, 5.0f, 1.4f);
        placid.onDamaged(context, 2.0f);

        assertTrue(placid.canStart(context),
                "a mob hurt by nothing it can see still runs somewhere");
    }
}
