package com.stonebreak.mobs.wisp;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.mobs.entities.EntityCollision;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.StubMob;
import com.stonebreak.mobs.entities.ai.nav.NavProfiles;
import com.stonebreak.world.World;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A Wisp runs straight up a one-block rise — no stop, no hop — where anything with feet still has
 * to jump; a two-block wall, or a step with no room above it, still stops it.
 *
 * <p>Flat stone up to y=63; from x=3 on, the terrain is whatever {@code solidFromX3} says for each
 * height. Physics only — the mob is pushed east at a run, as its steering would.
 */
class WispStepTest {

    private static final float TICK = 0.05f;
    private static final int GROUND = 64;
    private static final float RUN = 7f;

    private static World terrain(IntPredicate solidFromX3) {
        World world = mock(World.class);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(inv -> {
            int x = inv.getArgument(0);
            int y = inv.getArgument(1);
            boolean solid = y < GROUND || (x >= 3 && solidFromX3.test(y));
            return solid ? BlockType.STONE : BlockType.AIR;
        });
        return world;
    }

    /** Settles the mob onto the ground, then runs it east for two seconds. */
    private static void runEast(World world, LivingEntity mob) {
        EntityCollision collision = new EntityCollision(world);
        for (int i = 0; i < 10; i++) {
            collision.applyLivingEntityPhysics(mob, TICK);
        }
        for (int i = 0; i < 40; i++) {
            Vector3f v = mob.getVelocity();
            mob.setVelocity(new Vector3f(RUN, v.y, 0f));
            collision.applyLivingEntityPhysics(mob, TICK);
        }
    }

    @Test
    void itRunsStraightUpAOneBlockRise() {
        World world = terrain(y -> y == GROUND);
        Wisp wisp = new Wisp(world, new Vector3f(0.5f, GROUND, 0.5f));
        runEast(world, wisp);
        assertTrue(wisp.getPosition().x > 6f, "kept running: " + wisp.getPosition());
        assertEquals(GROUND + 1, wisp.getPosition().y, 0.05f);
    }

    @Test
    void somethingWithFeetStillHasToJumpIt() {
        World world = terrain(y -> y == GROUND);
        StubMob goblin = new StubMob(EntityType.GOBLIN, new Vector3f(0.5f, GROUND, 0.5f), world);
        runEast(world, goblin);
        assertTrue(goblin.getPosition().x < 3f, "walked into the step: " + goblin.getPosition());
    }

    @Test
    void aTwoBlockWallStopsIt() {
        World world = terrain(y -> y == GROUND || y == GROUND + 1);
        Wisp wisp = new Wisp(world, new Vector3f(0.5f, GROUND, 0.5f));
        runEast(world, wisp);
        assertTrue(wisp.getPosition().x < 3f, "went through or over the wall: " + wisp.getPosition());
    }

    @Test
    void aStepWithNoRoomAboveItStopsIt() {
        // One block up, but a ceiling two blocks above the step: no room for a two-block body.
        World world = terrain(y -> y == GROUND || y == GROUND + 2);
        Wisp wisp = new Wisp(world, new Vector3f(0.5f, GROUND, 0.5f));
        runEast(world, wisp);
        assertTrue(wisp.getPosition().x < 3f, "squeezed under the ceiling: " + wisp.getPosition());
        assertEquals(GROUND, wisp.getPosition().y, 0.05f);
    }

    @Test
    void itsRoutesTreatAWholeBlockAsAStepNotAJump() {
        World world = terrain(y -> false);
        assertEquals(Wisp.STEP_UP, NavProfiles.forEntity(new Wisp(world, new Vector3f())).maxStepUp(), 1e-6f);
        assertEquals(NavProfiles.MAX_STEP_UP,
                NavProfiles.forEntity(new StubMob(EntityType.GOBLIN, new Vector3f(), world)).maxStepUp(), 1e-6f);
    }

    @Test
    void itOutrunsASprintingPlayerWhenItFlees() {
        float sprint = 20f * 1.5f / 5f; // PlayerConstants.MOVE_SPEED × SPRINT_MULTIPLIER ÷ ground friction
        assertTrue(Wisp.RUN_SPEED * WispVanishBehavior.FLEE_SPEED > sprint);
        assertTrue(Wisp.RUN_SPEED * WispWooshBehavior.ESCAPE_SPEED > sprint);
    }
}
