package com.stonebreak.mobs.entities;

import com.stonebreak.world.World;
import com.stonebreak.world.generation.TerrainGenerator;
import com.stonebreak.world.generation.biomes.BiomeType;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Goblins come out every night, seldom in the Shade; Wisps only into the Shade's full fog, for those who linger. */
class HostileSpawnRuleTest {

    /** A world whose biome is the Shade west of x=0 and plains east of it. */
    private static World halfShade(float fog) {
        World world = mock(World.class);
        TerrainGenerator terrain = mock(TerrainGenerator.class);
        when(terrain.getBiomeAt(anyInt(), anyInt())).thenAnswer(inv ->
                (int) inv.getArgument(0) < 0 ? BiomeType.SHADE : BiomeType.PLAINS);
        when(world.terrain()).thenReturn(terrain);
        when(world.getNightFog()).thenReturn(fog);
        return world;
    }

    @Test
    void goblinsKeepToTheNightAndGoAnywhere() {
        World world = halfShade(0f);
        assertTrue(HostileSpawnRule.GOBLIN.when().allows(world, true));
        assertFalse(HostileSpawnRule.GOBLIN.when().allows(world, false));
        assertTrue(HostileSpawnRule.GOBLIN.where().allows(world, new Vector3f(5, 64, 0), new Vector3f(9, 64, 0)));
        assertTrue(HostileSpawnRule.GOBLIN.chance().at(world, new Vector3f(5, 64, 0)) >= 1f,
                "a band every interval outside the Shade, as before");
    }

    @Test
    void goblinsAreRareInTheShade() {
        World world = halfShade(0f);
        assertTrue(HostileSpawnRule.GOBLIN.chance().at(world, new Vector3f(-40, 64, 0)) <= 0.1f);
    }

    @Test
    void wispsWaitForTheFullFog() {
        assertFalse(HostileSpawnRule.WISP.when().allows(halfShade(0.3f), true), "dusk is not enough");
        assertTrue(HostileSpawnRule.WISP.when().allows(halfShade(1f), true));
    }

    @Test
    void wispsSpawnOnlyInTheShadeAndOnlyNearSomeoneInIt() {
        World world = halfShade(1f);
        Vector3f shade = new Vector3f(-40, 64, 0);
        Vector3f plains = new Vector3f(40, 64, 0);
        assertTrue(HostileSpawnRule.WISP.where().allows(world, shade, new Vector3f(-5, 64, 0)));
        assertFalse(HostileSpawnRule.WISP.where().allows(world, plains, new Vector3f(-5, 64, 0)));
        assertFalse(HostileSpawnRule.WISP.where().allows(world, shade, new Vector3f(5, 64, 0)),
                "the player is outside the Shade");
    }

    @Test
    void wispsComeEveryMinuteAndAHalfInTheShade() {
        assertEquals(90f, HostileSpawnRule.WISP.shadeClockSeconds());
        assertEquals(1f, HostileSpawnRule.WISP.chance().at(halfShade(1f), new Vector3f(-40, 64, 0)),
                "once the time is served, no roll stands in the way");
        assertEquals(0f, HostileSpawnRule.GOBLIN.shadeClockSeconds(), "goblins keep the spawn interval");
    }

    @Test
    void wispsAreFew() {
        assertTrue(HostileSpawnRule.WISP.groupMin() == 1 && HostileSpawnRule.WISP.groupMax() == 2);
        assertEquals(2, HostileSpawnRule.WISP.cap(), "never more than two about");
    }
}
