package com.stonebreak.mobs.vestige;

import com.openmason.engine.nav.GroundProfile;
import com.openmason.engine.nav.NavLimits;
import com.openmason.engine.nav.NavPath;
import com.openmason.engine.nav.NavWorld;
import com.openmason.engine.physics.PhysicsWorld;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.mobs.entities.EntityCollision;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.StubMob;
import com.stonebreak.mobs.entities.ai.nav.NavProfiles;
import com.stonebreak.mobs.entities.ai.nav.PathfindingService;
import com.stonebreak.world.World;
import com.stonebreak.world.physics.WorldPhysics;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A Vestige floats down a ten-block cliff instead of dropping off it, and plans its escapes down
 * drops like that, which a goblin will not take.
 *
 * <p>The cliff: a plateau standing at y=74 west of x=3, ground at y=64 east of it.
 */
class VestigeGlideTest {

    private static final float TICK = 0.05f;
    private static final int LOW = 64;
    private static final int HIGH = 74;
    private static final int CLIFF_X = 3;

    private static boolean solid(int x, int y) {
        return y < LOW || (x < CLIFF_X && y < HIGH);
    }

    private static World cliff() {
        World world = mock(World.class);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(inv ->
                solid(inv.getArgument(0), inv.getArgument(1)) ? BlockType.STONE : BlockType.AIR);
        return world;
    }

    /** Walks the mob east off the cliff edge; returns its fastest fall, blocks per second. */
    private static float walkOff(World world, LivingEntity mob, float seconds) {
        EntityCollision collision = new EntityCollision(world);
        float fastestFall = 0f;
        for (int i = 0; i < seconds / TICK; i++) {
            Vector3f v = mob.getVelocity();
            mob.setVelocity(new Vector3f(2f, v.y, 0f));
            collision.applyLivingEntityPhysics(mob, TICK);
            fastestFall = Math.max(fastestFall, -mob.getVelocity().y);
        }
        return fastestFall;
    }

    @Test
    void itFloatsDownACliffRatherThanDropping() {
        World world = cliff();
        Vestige vestige = new Vestige(world, new Vector3f(1.5f, HIGH, 0.5f));
        float fastest = walkOff(world, vestige, 1.5f);
        assertTrue(fastest <= Vestige.GLIDE_SINK_SPEED + 1e-3f, "sank at " + fastest);
        assertTrue(vestige.getPosition().y > LOW + 3f, "still on the way down after a second: " + vestige.getPosition());

        walkOff(world, vestige, 3f);
        assertEquals(LOW, vestige.getPosition().y, 0.05f, "and lands at the bottom");
    }

    @Test
    void somethingWithoutAGlideStillDrops() {
        World world = cliff();
        StubMob goblin = new StubMob(EntityType.GOBLIN, new Vector3f(1.5f, HIGH, 0.5f), world);
        assertTrue(walkOff(world, goblin, 1.5f) > 2f * Vestige.GLIDE_SINK_SPEED);
    }

    @Test
    void itsRoutesTakeTallDropsAndBarelyChargeForThem() {
        World world = mock(World.class);
        GroundProfile vestige = NavProfiles.forEntity(new Vestige(world, new Vector3f()));
        GroundProfile goblin = NavProfiles.forEntity(new StubMob(EntityType.GOBLIN, new Vector3f(), world));
        assertEquals(Vestige.MAX_GLIDE_DROP, vestige.maxFall(), 1e-6f);
        assertTrue(goblin.maxFall() < HIGH - LOW);
        assertTrue(vestige.fallCostPerBlock() < goblin.fallCostPerBlock());
    }

    @Test
    void itPlansAnEscapeDownTheCliffThatAGoblinCannot() {
        World world = mock(World.class);
        Vector3f top = new Vector3f(0.5f, HIGH, 0.5f);
        Vector3f bottom = new Vector3f(8.5f, LOW, 0.5f);

        NavPath glide = route(NavProfiles.forEntity(new Vestige(world, new Vector3f())), top, bottom);
        assertTrue(glide.isComplete(), "the Vestige glides down");
        assertEquals(LOW, glide.y(glide.size() - 1), 0.01f);

        NavPath walk = route(NavProfiles.forEntity(new StubMob(EntityType.GOBLIN, new Vector3f(), world)), top, bottom);
        assertFalse(walk.isComplete(), "a ten-block drop is no route for a walker");
    }

    /** A route over the cliff, planned by the native search over a physics world of it. */
    private static NavPath route(GroundProfile profile, Vector3f from, Vector3f to) {
        try (PhysicsWorld physics = PhysicsWorld.create(VestigeGlideTest::fillCliff, 4, 0, 0)) {
            physics.defineShape(1, new float[] {0, 0, 0, 1, 1, 1}, WorldPhysics.SOLID | WorldPhysics.PICK, 0);
            PathfindingService service = PathfindingService.over(physics, 256);
            try {
                long id = service.nav().searchGround(new NavWorld.GroundQuery(from.x, from.y, from.z, to.x, to.y, to.z,
                        1.0f, 4, 2, profile, new NavLimits(500, Float.MAX_VALUE, 1.0f)));
                service.step();
                return service.nav().poll(id).path();
            } finally {
                service.close();
            }
        }
    }

    private static boolean fillCliff(int x0, int y0, int z0, int sx, int sy, int sz, short[] shapes, short[] fluids) {
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    if (y0 + y >= 0 && solid(x0 + x, y0 + y)) {
                        shapes[(y * sz + z) * sx + x] = 1;
                    }
                }
            }
        }
        return true;
    }
}
