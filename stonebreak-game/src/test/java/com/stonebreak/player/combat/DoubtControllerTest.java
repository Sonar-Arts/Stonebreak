package com.stonebreak.player.combat;

import static com.stonebreak.player.PlayerConstants.ILLUSIONIST_DOUBT_DECAY_TIMEOUT;
import static com.stonebreak.player.PlayerConstants.ILLUSIONIST_DOUBT_MAX_STACKS;
import static com.stonebreak.player.PlayerConstants.ILLUSIONIST_SHAKEN_ATTACK_DELAY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.StubMob;
import com.stonebreak.mobs.entities.status.StatusEffectType;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * The Illusionist's per-enemy Doubt ledger: stacks accumulate independently on every enemy that
 * strikes a decoy, clamp at Bewildered, decay stepwise once contact goes stale, and entries for
 * dead enemies are pruned so the map cannot leak entity references across a fight.
 */
class DoubtControllerTest {

    private final DoubtController doubt = new DoubtController();

    private static StubMob enemy() {
        return new StubMob(EntityType.COW, new Vector3f(0, 64, 0));
    }

    @Test
    void stacksBuildPerEnemyAndClampAtBewildered() {
        StubMob first = enemy();
        StubMob second = enemy();

        for (int i = 0; i < ILLUSIONIST_DOUBT_MAX_STACKS + 2; i++) {
            doubt.addStack(first);
        }
        doubt.addStack(second);

        assertEquals(ILLUSIONIST_DOUBT_MAX_STACKS, doubt.getStacks(first));
        assertTrue(doubt.isBewildered(first));
        assertEquals(1, doubt.getStacks(second), "each enemy keeps its own ledger");
        assertFalse(doubt.isBewildered(second));
    }

    @Test
    void shakenHesitationScalesWithStacksUntilBewildered() {
        StubMob target = enemy();

        doubt.addStack(target);
        assertEquals(1f * ILLUSIONIST_SHAKEN_ATTACK_DELAY,
                target.getStatusEffectMagnitude(StatusEffectType.SHAKEN), 1e-6f);

        doubt.addStack(target);
        doubt.addStack(target);
        assertEquals(3f * ILLUSIONIST_SHAKEN_ATTACK_DELAY,
                target.getStatusEffectMagnitude(StatusEffectType.SHAKEN), 1e-6f,
                "each stack must re-apply the SHAKEN magnitude, not freeze at the first "
                + "stack's hesitation (issue #232)");
    }

    @Test
    void anUntrackedEnemyHasNoDoubt() {
        assertEquals(0, doubt.getStacks(enemy()));
    }

    @Test
    void aDeadEnemyGainsNothing() {
        StubMob corpse = enemy();
        corpse.setAlive(false);

        doubt.addStack(corpse);
        doubt.addStack(null); // decoy-death callbacks can race despawn — must not throw

        assertEquals(0, doubt.getStacks(corpse));
    }

    @Test
    void staleContactDecaysOneStackPerInterval() {
        StubMob target = enemy();
        doubt.addStack(target);
        doubt.addStack(target);

        doubt.update(ILLUSIONIST_DOUBT_DECAY_TIMEOUT);

        assertEquals(1, doubt.getStacks(target), "the first stale interval sheds one stack");
    }

    @Test
    void freshContactResetsTheDecayClock() {
        StubMob target = enemy();
        doubt.addStack(target);
        doubt.update(ILLUSIONIST_DOUBT_DECAY_TIMEOUT - 1f);

        doubt.addStack(target); // the decoy got hit again
        doubt.update(ILLUSIONIST_DOUBT_DECAY_TIMEOUT - 1f);

        assertEquals(2, doubt.getStacks(target));
    }

    @Test
    void aFullyDecayedEnemyLeavesTheLedgerEntirely() {
        StubMob target = enemy();
        doubt.addStack(target);

        doubt.update(ILLUSIONIST_DOUBT_DECAY_TIMEOUT);

        assertEquals(0, doubt.getStacks(target));
        assertTrue(doubt.getAllDoubted().isEmpty());
    }

    @Test
    void deadEnemiesArePrunedOnUpdate() {
        StubMob target = enemy();
        doubt.addStack(target);
        target.setAlive(false);

        doubt.update(0.1f);

        assertEquals(0, doubt.getStacks(target));
        assertTrue(doubt.getAllDoubted().isEmpty(), "a dead enemy must not linger in Fracture's pool");
    }

    @Test
    void fracturesTargetPoolListsOnlyLiveDoubtedEnemies() {
        StubMob doubted = enemy();
        StubMob untouched = enemy();
        doubt.addStack(doubted);

        List<?> pool = doubt.getAllDoubted();

        assertEquals(1, pool.size());
        assertTrue(pool.contains(doubted));
        assertFalse(pool.contains(untouched));
    }

    @Test
    void fractureConsumesTheWholeEntry() {
        StubMob target = enemy();
        doubt.addStack(target);
        doubt.addStack(target);

        doubt.consumeAll(target);

        assertEquals(0, doubt.getStacks(target));
        assertTrue(doubt.getAllDoubted().isEmpty());
    }

    @Test
    void resetClearsEveryLedgerForWorldReload() {
        doubt.addStack(enemy());
        doubt.addStack(enemy());

        doubt.reset();

        assertTrue(doubt.getAllDoubted().isEmpty());
    }
}
