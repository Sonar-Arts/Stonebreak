package com.stonebreak.player.combat;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.cactus.CactusContactRules;
import com.stonebreak.player.state.PhysicsState;
import com.stonebreak.world.World;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Eighth-heart cactus contact damage, ticked every 0.5 seconds in contact.
 *
 * <p>The pinned behaviour: one pulse of {@link CactusContactRules#DAMAGE_PER_PULSE}
 * (an eighth heart = 0.25 hearts = 0.5 HP) per {@link CactusContactRules#PULSE_INTERVAL}
 * a body stays in contact, the timer resetting the moment the body walks away, and the
 * same gates {@code FallDamageHandler} respects (no damage flying or under spawn
 * protection). The check itself throttles — the player's only i-frames are dodge's — so
 * the reset is what stops walking past a cactus from stacking pulses.
 */
public class CactusContactDamageTest {

    /** Player standing flush against a solid spiky cell from the left: center 1.7, feet 10.0. */
    private static PhysicsState playerAt() {
        PhysicsState state = new PhysicsState();
        state.getPosition().set(1.7f, 10.0f, 2.0f);
        return state;
    }

    private World worldWithCactus(int x, int y, int z) {
        World world = mock(World.class);
        when(world.getBlockAt(x, y, z)).thenReturn(BlockType.CACTUS);
        return world;
    }

    @Test
    public void flushContactCountsAsTouchingDiagonalAdjacencyDoesNot() {
        PhysicsState state = playerAt();

        // Flush from the left of cell (2, 10, 2) — the expanded footprint shares a face boundary.
        World flush = worldWithCactus(2, 10, 2);
        assertTrue(CactusContactRules.touchesCactus(flush,
                1.4f, 10.0f, 1.7f, 2.0f, 11.8f, 2.3f));

        // Diagonal cell (3, 10, 3): no face or edge contact in the footprint range.
        World diagonal = worldWithCactus(3, 10, 3);
        assertFalse(CactusContactRules.touchesCactus(diagonal,
                1.4f, 10.0f, 1.7f, 2.0f, 11.8f, 2.3f));

        // Resting on the tip: feet share the top face of the cell below.
        World tip = worldWithCactus(2, 9, 2);
        assertTrue(CactusContactRules.touchesCactus(tip,
                1.4f, 10.0f, 1.7f, 2.0f, 11.8f, 2.3f));
    }

    @Test
    public void pulsesAnEighthHeartEveryHalfSecondWhileInContact() {
        World world = worldWithCactus(2, 10, 2);
        HealthController health = mock(HealthController.class);
        CactusContactDamage contact = new CactusContactDamage(world, playerAt(), health);

        contact.update(0.3f, false);
        verify(health, never()).damage(0.5f);

        contact.update(0.3f, false);
        ArgumentCaptor<Float> first = ArgumentCaptor.forClass(Float.class);
        verify(health, times(1)).damage(first.capture());
        assertEquals(0.5f, first.getValue(), 1e-4f, "an eighth heart = 0.25 hearts = 0.5 HP");

        contact.update(0.5f, false);
        ArgumentCaptor<Float> second = ArgumentCaptor.forClass(Float.class);
        verify(health, times(2)).damage(second.capture());
        assertEquals(0.5f, second.getValue(), 1e-4f);
    }

    @Test
    public void walkingAwayResetsTheTimer() {
        World world = worldWithCactus(2, 10, 2);
        World empty = mock(World.class); // every cell null/air
        HealthController health = mock(HealthController.class);
        CactusContactDamage contact = new CactusContactDamage(world, playerAt(), health);

        // 0.3 s in contact (no pulse yet), then walk away for a full second — the timer
        // must reset, so the next 0.3 s of contact still does not pulse.
        contact.update(0.3f, false);
        verify(health, never()).damage(0.5f);
        contact.setWorld(empty);
        contact.update(1.0f, false);

        // Back in contact: 0.3 s still under the interval, 0.2 s more pulses once.
        contact.setWorld(world);
        contact.update(0.3f, false);
        verify(health, never()).damage(0.5f);
        contact.update(0.2f, false);
        verify(health, times(1)).damage(0.5f);
    }

    @Test
    public void noDamageWithoutContact() {
        World world = mock(World.class); // every cell null/air
        HealthController health = mock(HealthController.class);
        CactusContactDamage contact = new CactusContactDamage(world, playerAt(), health);

        for (int i = 0; i < 6; i++) {
            contact.update(0.5f, false);
        }
        verify(health, never()).damage(0.5f);
        verify(health, never()).damage(1.0f);
        verify(health, never()).damage(2.0f);
    }

    @Test
    public void noDamageFlying() {
        World world = worldWithCactus(2, 10, 2);
        HealthController health = mock(HealthController.class);
        CactusContactDamage contact = new CactusContactDamage(world, playerAt(), health);

        contact.update(2.0f, true);
        verify(health, never()).damage(0.5f);
    }

    @Test
    public void noDamageUnderSpawnProtection() {
        World world = worldWithCactus(2, 10, 2);
        HealthController health = mock(HealthController.class);
        when(health.hasSpawnProtection()).thenReturn(true);
        CactusContactDamage contact = new CactusContactDamage(world, playerAt(), health);

        contact.update(2.0f, false);
        verify(health, never()).damage(0.5f);
    }
}
