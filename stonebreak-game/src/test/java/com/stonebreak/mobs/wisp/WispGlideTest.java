package com.stonebreak.mobs.wisp;

import com.openmason.engine.wayfind.SearchLimits;
import com.openmason.engine.wayfind.voxel.NavCell;
import com.openmason.engine.wayfind.voxel.NavProfile;
import com.openmason.engine.wayfind.voxel.NavVolume;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.mobs.entities.EntityCollision;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.StubMob;
import com.stonebreak.mobs.entities.ai.nav.NavProfiles;
import com.stonebreak.mobs.entities.ai.nav.Path;
import com.stonebreak.mobs.entities.ai.nav.PathfindingService;
import com.stonebreak.world.World;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A Wisp floats down a ten-block cliff instead of dropping off it, and plans its escapes down
 * drops like that, which a goblin will not take.
 *
 * <p>The cliff: a plateau standing at y=74 west of x=3, ground at y=64 east of it.
 */
class WispGlideTest {

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
        Wisp wisp = new Wisp(world, new Vector3f(1.5f, HIGH, 0.5f));
        float fastest = walkOff(world, wisp, 1.5f);
        assertTrue(fastest <= Wisp.GLIDE_SINK_SPEED + 1e-3f, "sank at " + fastest);
        assertTrue(wisp.getPosition().y > LOW + 3f, "still on the way down after a second: " + wisp.getPosition());

        walkOff(world, wisp, 3f);
        assertEquals(LOW, wisp.getPosition().y, 0.05f, "and lands at the bottom");
    }

    @Test
    void somethingWithoutAGlideStillDrops() {
        World world = cliff();
        StubMob goblin = new StubMob(EntityType.GOBLIN, new Vector3f(1.5f, HIGH, 0.5f), world);
        assertTrue(walkOff(world, goblin, 1.5f) > 2f * Wisp.GLIDE_SINK_SPEED);
    }

    @Test
    void itsRoutesTakeTallDropsAndBarelyChargeForThem() {
        World world = mock(World.class);
        NavProfile wisp = NavProfiles.forEntity(new Wisp(world, new Vector3f()));
        NavProfile goblin = NavProfiles.forEntity(new StubMob(EntityType.GOBLIN, new Vector3f(), world));
        assertEquals(Wisp.MAX_GLIDE_DROP, wisp.maxFall(), 1e-6f);
        assertTrue(goblin.maxFall() < HIGH - LOW);
        assertTrue(wisp.fallCostPerBlock() < goblin.fallCostPerBlock());
    }

    @Test
    void itPlansAnEscapeDownTheCliffThatAGoblinCannot() {
        World world = mock(World.class);
        Vector3f top = new Vector3f(0.5f, HIGH, 0.5f);
        Vector3f bottom = new Vector3f(8.5f, LOW, 0.5f);

        Path glide = route(NavProfiles.forEntity(new Wisp(world, new Vector3f())), top, bottom);
        assertTrue(glide.isComplete(), "the Wisp glides down");
        assertEquals(LOW, glide.y(glide.size() - 1), 0.01f);

        Path walk = route(NavProfiles.forEntity(new StubMob(EntityType.GOBLIN, new Vector3f(), world)), top, bottom);
        assertFalse(walk.isComplete(), "a ten-block drop is no route for a walker");
    }

    private static Path route(NavProfile profile, Vector3f from, Vector3f to) {
        NavVolume volume = new NavVolume() {
            @Override
            public int flags(int x, int y, int z) {
                return y < 0 ? NavCell.UNKNOWN : solid(x, y) ? NavCell.SOLID : NavCell.OPEN;
            }

            @Override
            public float topSurface(int x, int y, int z) {
                return 1f;
            }
        };
        PathfindingService service = new PathfindingService(volume, Runnable::run);
        try {
            return service.submit(from, to, 1.0f, profile, SearchLimits.DEFAULT).result();
        } finally {
            service.close();
        }
    }
}
