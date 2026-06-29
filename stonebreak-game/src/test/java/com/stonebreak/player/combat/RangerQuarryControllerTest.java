package com.stonebreak.player.combat;

import static com.stonebreak.player.PlayerConstants.RANGER_STUDY_DECAY_INTERVAL;
import static com.stonebreak.player.PlayerConstants.RANGER_STUDY_DECAY_TIMEOUT;
import static com.stonebreak.player.PlayerConstants.RANGER_STUDY_MAX_STACKS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stonebreak.items.ItemStack;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.player.Player;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the Ranger's Quarry/Study resource: marking, stacking, decay,
 * replacement, and the Marked Prey / low-HP predicates.
 */
class RangerQuarryControllerTest {

    /** Minimal living entity — no world or AI needed for QuarryController logic. */
    private static final class StubEntity extends LivingEntity {
        StubEntity() {
            super(null, new Vector3f(0f, 0f, 0f), EntityType.COW);
        }

        @Override
        public void onInteract(Player player) {}

        @Override
        public void onDamage(float damage, DamageSource source) {}

        @Override
        protected void onDeath() {}

        @Override
        public ItemStack[] getDrops() { return new ItemStack[0]; }

        @Override
        public void render(com.stonebreak.rendering.Renderer renderer) {}

        @Override
        public EntityType getType() { return EntityType.COW; }
    }

    private QuarryController controller;
    private StubEntity cow;

    @BeforeEach
    void setUp() {
        controller = new QuarryController();
        cow = new StubEntity();
    }

    @Test
    void firstHit_marksQuarryAtZeroStacks() {
        controller.onPlayerHit(cow);

        assertSame(cow, controller.getQuarry());
        assertEquals(0, controller.getStudyStacks());
    }

    @Test
    void subsequentHits_buildStacksUpToCap() {
        controller.onPlayerHit(cow);
        for (int i = 0; i < RANGER_STUDY_MAX_STACKS + 2; i++) {
            controller.onPlayerHit(cow);
        }

        assertEquals(RANGER_STUDY_MAX_STACKS, controller.getStudyStacks());
        assertTrue(controller.isMarkedPrey());
    }

    @Test
    void hittingNewTarget_replacesQuarryAndResetsStacks() {
        controller.onPlayerHit(cow);
        controller.onPlayerHit(cow);
        assertEquals(1, controller.getStudyStacks());

        StubEntity other = new StubEntity();
        controller.onPlayerHit(other);

        assertSame(other, controller.getQuarry());
        assertEquals(0, controller.getStudyStacks());
    }

    @Test
    void addStacks_marksAndAddsInOneStep() {
        controller.addStacks(cow, 2);

        assertSame(cow, controller.getQuarry());
        assertEquals(2, controller.getStudyStacks());
    }

    @Test
    void addStacks_clampsAtMax() {
        controller.onPlayerHit(cow);
        controller.onPlayerHit(cow);
        controller.onPlayerHit(cow);
        controller.addStacks(cow, 2);

        assertEquals(RANGER_STUDY_MAX_STACKS, controller.getStudyStacks());
    }

    @Test
    void decay_losesStacksStepwiseThenClearsMark() {
        controller.addStacks(cow, 2);

        // First decay tick fires once the timeout elapses
        controller.update(RANGER_STUDY_DECAY_TIMEOUT);
        assertEquals(1, controller.getStudyStacks());

        // One more interval drops the next stack
        controller.update(RANGER_STUDY_DECAY_INTERVAL);
        assertEquals(0, controller.getStudyStacks());

        // A decay tick at zero stacks clears the mark entirely
        controller.update(RANGER_STUDY_DECAY_INTERVAL);
        assertNull(controller.getQuarry());
    }

    @Test
    void hittingQuarry_resetsDecayClock() {
        controller.addStacks(cow, 2);
        controller.update(RANGER_STUDY_DECAY_TIMEOUT - 0.5f);
        controller.onPlayerHit(cow); // 3 stacks, clock reset

        controller.update(RANGER_STUDY_DECAY_TIMEOUT - 0.5f);
        assertEquals(RANGER_STUDY_MAX_STACKS, controller.getStudyStacks());
    }

    @Test
    void deadQuarry_isClearedOnUpdate() {
        controller.addStacks(cow, 3);
        cow.damage(1000f);
        controller.update(0.016f);

        assertNull(controller.getQuarry());
        assertEquals(0, controller.getStudyStacks());
        assertFalse(controller.isMarkedPrey());
    }

    @Test
    void deadTarget_isNeverMarked() {
        cow.damage(1000f);
        controller.onPlayerHit(cow);

        assertNull(controller.getQuarry());
    }

    @Test
    void isPreyLowHp_tracksHealthFractionThreshold() {
        controller.addStacks(cow, 3);
        assertFalse(controller.isPreyLowHp());

        // COW max health is 10; drop below 30%
        cow.damage(8f);
        assertTrue(controller.isPreyLowHp());
    }

    @Test
    void reset_clearsEverything() {
        controller.addStacks(cow, 3);
        controller.reset();

        assertNull(controller.getQuarry());
        assertEquals(0, controller.getStudyStacks());
        assertFalse(controller.isMarkedPrey());
        assertEquals(0f, controller.getDecayProgress());
    }
}
