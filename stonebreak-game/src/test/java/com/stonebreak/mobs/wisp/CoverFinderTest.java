package com.stonebreak.mobs.wisp;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;
import com.stonebreak.world.generation.TerrainGenerator;
import com.stonebreak.world.generation.biomes.BiomeType;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Hiding behind trees: flat stone ground at y=63, four-block log trunks where a test plants them,
 * and a victim at the origin. No terrain generator, so every column is Shade.
 */
class CoverFinderTest {

    private static final int GROUND = 64;
    private static final Vector3f VICTIM_EYE = new Vector3f(0.5f, GROUND + 1.44f, 0.5f);

    private static CoverFinder forest(int[]... trunks) {
        World world = world(trunks);
        return new CoverFinder(world, new ShadeColumns(world));
    }

    /** Treeless flat ground, Shade only where {@code shade} says. */
    private static CoverFinder openGround(BiPredicate<Integer, Integer> shade) {
        World world = world();
        TerrainGenerator terrain = mock(TerrainGenerator.class);
        when(terrain.getBiomeAt(anyInt(), anyInt())).thenAnswer(inv ->
                shade.test(inv.getArgument(0), inv.getArgument(1)) ? BiomeType.SHADE : BiomeType.PLAINS);
        when(world.terrain()).thenReturn(terrain);
        return new CoverFinder(world, new ShadeColumns(world));
    }

    private static World world(int[]... trunks) {
        Set<Long> logs = new HashSet<>();
        for (int[] trunk : trunks) {
            logs.add(((long) trunk[0] << 32) ^ (trunk[1] & 0xFFFFFFFFL));
        }
        World world = mock(World.class);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(inv -> {
            int x = inv.getArgument(0);
            int y = inv.getArgument(1);
            int z = inv.getArgument(2);
            if (y < GROUND) {
                return BlockType.STONE;
            }
            if (y < GROUND + 4 && logs.contains(((long) x << 32) ^ (z & 0xFFFFFFFFL))) {
                return BlockType.DRY_OAK_LOG;
            }
            return BlockType.AIR;
        });
        return world;
    }

    @Test
    void theFarSideOfATrunkIsHiddenAndTheOpenIsNot() {
        CoverFinder cover = forest(new int[] {8, 0});
        assertTrue(cover.hidden(VICTIM_EYE, new Vector3f(9.9f, GROUND, 0.5f)));
        assertFalse(cover.hidden(VICTIM_EYE, new Vector3f(9.9f, GROUND, 4.5f)));
    }

    @Test
    void itHidesBehindTheTrunkFromTheVictim() {
        CoverFinder cover = forest(new int[] {8, 0});
        Vector3f spot = cover.find(new Vector3f(12f, GROUND, 0.5f), 12f, VICTIM_EYE, null, 6f, 14f,
                CoverFinder.Preference.FOREST);
        assertNotNull(spot);
        assertTrue(spot.x > 9f, "behind the trunk, away from the victim: " + spot);
        assertEquals(GROUND, spot.y, 1e-4f);
        assertTrue(cover.hidden(VICTIM_EYE, spot));
    }

    @Test
    void noTreesMeansNowhereToHide() {
        assertNull(forest().find(new Vector3f(10f, GROUND, 0f), 12f, VICTIM_EYE, null, 6f, 14f,
                CoverFinder.Preference.FOREST));
    }

    @Test
    void itPrefersTheThickOfTheForestToALoneTree() {
        // A lone tree north-east, a grove south-east, both ten blocks from the victim.
        CoverFinder cover = forest(new int[] {7, 7},
                new int[] {7, -7}, new int[] {9, -6}, new int[] {6, -9}, new int[] {9, -9}, new int[] {5, -6});
        Vector3f spot = cover.find(new Vector3f(10f, GROUND, 0.5f), 14f, VICTIM_EYE, null, 6f, 16f,
                CoverFinder.Preference.FOREST);
        assertNotNull(spot);
        assertTrue(spot.z < 0f, "chose the grove: " + spot);
    }

    @Test
    void aPursuitsPathRunsToTheLastSightingAndAlongTheirWay() {
        CoverFinder.Pursuit pursuit = new CoverFinder.Pursuit(new Vector3f(0f, GROUND, 0f),
                new Vector3f(12f, GROUND, 0f), new Vector3f(0f, GROUND, -12f));
        assertEquals(3f, pursuit.distanceToPath(new Vector3f(6f, GROUND, 3f)), 1e-4f, "beside the gaze line");
        assertEquals(2f, pursuit.distanceToPath(new Vector3f(-2f, GROUND, -6f)), 1e-4f, "beside the walk");
        assertEquals(4f, pursuit.distanceToPath(new Vector3f(20f, GROUND, 4f)), 1e-4f, "their gaze runs on past it");
        assertEquals(5f, pursuit.distanceToPath(new Vector3f(-3f, GROUND, -16f)), 1e-4f, "the walk stops at the prediction");
    }

    @Test
    void aFlankKeepsOutOfTheWayTheVictimIsHeading() {
        // Two trunks square either side of a victim looking east: alike but for the walk south.
        CoverFinder cover = forest(new int[] {0, 14}, new int[] {0, -14});
        CoverFinder.Pursuit walkingSouth = new CoverFinder.Pursuit(new Vector3f(0.5f, GROUND, 0.5f),
                new Vector3f(12f, GROUND, 0.5f), new Vector3f(0.5f, GROUND, -15.5f));
        Vector3f spot = cover.find(new Vector3f(12f, GROUND, 0.5f), 20f, VICTIM_EYE, new Vector3f(1f, 0f, 0f),
                6f, 20f, CoverFinder.Preference.FLANK, walkingSouth);
        assertNotNull(spot);
        assertTrue(spot.z > 0f, "the north trunk, off their path: " + spot);
    }

    @Test
    void aFlankPrefersTheirSideToStraightBeyondWhereTheySawIt() {
        // One trunk on past the last sighting, along their gaze; one off to their side.
        CoverFinder cover = forest(new int[] {18, 0}, new int[] {2, 14});
        CoverFinder.Pursuit staring = new CoverFinder.Pursuit(new Vector3f(0.5f, GROUND, 0.5f),
                new Vector3f(12f, GROUND, 0.5f), new Vector3f(0.5f, GROUND, 0.5f));
        Vector3f spot = cover.find(new Vector3f(12f, GROUND, 0.5f), 20f, VICTIM_EYE, new Vector3f(1f, 0f, 0f),
                6f, 22f, CoverFinder.Preference.FLANK, staring);
        assertNotNull(spot);
        assertTrue(spot.z > 10f, "slipped round to the side: " + spot);
    }

    @Test
    void withNoCoverItRunsStraightAwayAcrossTheOpen() {
        Vector3f spot = openGround((x, z) -> true).runSpot(new Vector3f(5.5f, GROUND, 0.5f),
                new Vector3f(0.5f, GROUND, 0.5f), 10f);
        assertNotNull(spot);
        assertEquals(15.5f, spot.x, 1e-3f);
        assertEquals(0.5f, spot.z, 1e-3f);
        assertEquals(GROUND, spot.y, 1e-4f);
    }

    @Test
    void itsBackToTheEdgeOfTheShadeItRunsAlongIt() {
        // Shade ends at x=0; the victim is west, so straight away is out of the Shade.
        Vector3f spot = openGround((x, z) -> x < 0).runSpot(new Vector3f(-0.5f, GROUND, 0.5f),
                new Vector3f(-6.5f, GROUND, 0.5f), 10f);
        assertNotNull(spot);
        assertTrue(spot.x < 0f, "still in the Shade: " + spot);
        assertTrue(Math.abs(spot.z - 0.5f) > 9f, "along the edge: " + spot);
    }

    @Test
    void inACornerOfTheShadeThereIsNowhereToRun() {
        // Shade only where x < 0 and z < 0; the victim comes at it from inside the corner.
        Vector3f spot = openGround((x, z) -> x < 0 && z < 0).runSpot(new Vector3f(-0.5f, GROUND, -0.5f),
                new Vector3f(-6.5f, GROUND, -6.5f), 10f);
        assertNull(spot);
    }

    @Test
    void aPeekLeansOutWhereTheVictimCanSeeItsHead() {
        CoverFinder cover = forest(new int[] {10, 0});
        CoverFinder.PeekSetup setup = cover.findPeek(new Vector3f(12f, GROUND, 0.5f), 8f, VICTIM_EYE, 8f, 16f);
        assertNotNull(setup);
        assertTrue(cover.hidden(VICTIM_EYE, setup.hide()));
        assertFalse(cover.blocked(VICTIM_EYE, setup.peek().x, setup.peek().y + CoverFinder.HEAD, setup.peek().z),
                "leaning out shows its face");
    }
}
