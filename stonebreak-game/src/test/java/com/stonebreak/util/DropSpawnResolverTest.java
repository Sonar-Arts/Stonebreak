package com.stonebreak.util;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Issue #225: broken blocks must spit drops into the nearest adjacent passable cell
 * (air or non-collidable — flowers, water), preferably the one nearest the player,
 * and an embedded drop must escape sideways instead of surfacing block-by-block.
 */
class DropSpawnResolverTest {

    private static final int BROKEN_X = 8;
    private static final int BROKEN_Y = 64;
    private static final int BROKEN_Z = 8;

    @FunctionalInterface
    private interface BlockLayout {
        BlockType at(int x, int y, int z);
    }

    private static World worldWith(BlockLayout layout) {
        World world = mock(World.class);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(inv ->
                layout.at(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
        return world;
    }

    /** Solid everywhere except the listed passable cells. */
    private static World worldWithPassable(Vector3i... passable) {
        return worldWith((x, y, z) -> {
            for (Vector3i p : passable) {
                if (p.x == x && p.y == y && p.z == z) {
                    return BlockType.AIR;
                }
            }
            return BlockType.DIRT;
        });
    }

    private record Vector3i(int x, int y, int z) {
    }

    @Test
    void resolveSpawnPrefersNearestPassableToPreference() {
        // Broken cell (8,64,8) is air; only the +X neighbour is also passable.
        World world = worldWithPassable(
                new Vector3i(BROKEN_X, BROKEN_Y, BROKEN_Z),
                new Vector3i(BROKEN_X + 1, BROKEN_Y, BROKEN_Z));
        // Player far on the +X side: the +X neighbour (distance 11) beats the broken
        // cell itself (distance 12).
        Vector3f resolved = DropSpawnResolver.resolveSpawn(world, BROKEN_X, BROKEN_Y, BROKEN_Z,
                new Vector3f(20f, BROKEN_Y + 0.5f, BROKEN_Z + 0.5f));
        assertEquals(new Vector3f(BROKEN_X + 1.5f, BROKEN_Y + 0.5f, BROKEN_Z + 0.5f), resolved);
    }

    @Test
    void resolveSpawnKeepsBrokenCellWhenItIsNearest() {
        // All six neighbours solid; the broken cell itself is passable and nearest.
        World world = worldWithPassable(new Vector3i(BROKEN_X, BROKEN_Y, BROKEN_Z));
        Vector3f resolved = DropSpawnResolver.resolveSpawn(world, BROKEN_X, BROKEN_Y, BROKEN_Z,
                new Vector3f(BROKEN_X + 0.5f, BROKEN_Y + 0.5f, BROKEN_Z + 0.5f));
        assertEquals(new Vector3f(BROKEN_X + 0.5f, BROKEN_Y + 0.5f, BROKEN_Z + 0.5f), resolved);
    }

    @Test
    void resolveSpawnTreatsWaterAsPassable() {
        World world = worldWith((x, y, z) ->
                (x == BROKEN_X - 1 && y == BROKEN_Y && z == BROKEN_Z) ? BlockType.WATER : BlockType.DIRT);
        // Player on the -X side: the water neighbour is a valid spit-out cell.
        Vector3f resolved = DropSpawnResolver.resolveSpawn(world, BROKEN_X, BROKEN_Y, BROKEN_Z,
                new Vector3f(-5f, BROKEN_Y + 0.5f, BROKEN_Z + 0.5f));
        assertEquals(new Vector3f(BROKEN_X - 0.5f, BROKEN_Y + 0.5f, BROKEN_Z + 0.5f), resolved);
    }

    @Test
    void resolveSpawnReturnsNullWhenFullyEnclosed() {
        World world = worldWith((x, y, z) -> BlockType.DIRT);
        assertNull(DropSpawnResolver.resolveSpawn(world, BROKEN_X, BROKEN_Y, BROKEN_Z, null));
    }

    @Test
    void resolveSpawnWithoutPreferenceTakesBrokenCellFirst() {
        // Broken cell + both X neighbours passable, no preference: declaration order
        // puts the broken cell itself first.
        World world = worldWithPassable(
                new Vector3i(BROKEN_X, BROKEN_Y, BROKEN_Z),
                new Vector3i(BROKEN_X + 1, BROKEN_Y, BROKEN_Z),
                new Vector3i(BROKEN_X - 1, BROKEN_Y, BROKEN_Z));
        Vector3f resolved = DropSpawnResolver.resolveSpawn(world, BROKEN_X, BROKEN_Y, BROKEN_Z, null);
        assertEquals(new Vector3f(BROKEN_X + 0.5f, BROKEN_Y + 0.5f, BROKEN_Z + 0.5f), resolved);
    }

    @Test
    void resolveEscapeTriesBelowBeforeAbove() {
        // Embedded trunk cell with air above (the canopy gap) and air below, every side
        // solid: the down escape must win — below is in the distance-ordered candidate
        // set, up (surfacing) only when no side or down escape exists.
        World world = worldWith((x, y, z) ->
                (y == BROKEN_Y + 1 || y == BROKEN_Y - 1) && x == BROKEN_X && z == BROKEN_Z
                        ? BlockType.AIR : BlockType.DIRT);
        Vector3f escape = DropSpawnResolver.resolveEscape(world, BROKEN_X, BROKEN_Y, BROKEN_Z,
                new Vector3f(BROKEN_X + 0.5f, BROKEN_Y + 1.5f, BROKEN_Z + 0.5f));
        assertEquals(new Vector3f(BROKEN_X + 0.5f, BROKEN_Y - 0.5f, BROKEN_Z + 0.5f), escape);
    }

    @Test
    void resolveEscapePrefersComingFromBelowOverAnArbitrarySide() {
        // Embedded cell with every side and below passable; the drop came from straight
        // below (rising into a trunk). All four sides tie in distance from a point straight
        // below — below (distance 0) must win so the drop escapes back down, not out an
        // arbitrary side.
        World world = worldWith((x, y, z) -> {
            if (x == BROKEN_X && z == BROKEN_Z && y <= BROKEN_Y) {
                return BlockType.AIR;
            }
            if (y == BROKEN_Y) {
                return BlockType.AIR;
            }
            return BlockType.DIRT;
        });
        Vector3f escape = DropSpawnResolver.resolveEscape(world, BROKEN_X, BROKEN_Y, BROKEN_Z,
                new Vector3f(BROKEN_X + 0.5f, BROKEN_Y - 0.5f, BROKEN_Z + 0.5f));
        assertEquals(new Vector3f(BROKEN_X + 0.5f, BROKEN_Y - 0.5f, BROKEN_Z + 0.5f), escape);
    }

    @Test
    void resolveEscapeSurfacesOnlyAsLastResort() {
        // Fully enclosed except air above: the surfacing cell is the only option.
        World world = worldWith((x, y, z) ->
                (y == BROKEN_Y + 1 && x == BROKEN_X && z == BROKEN_Z) ? BlockType.AIR : BlockType.DIRT);
        Vector3f escape = DropSpawnResolver.resolveEscape(world, BROKEN_X, BROKEN_Y, BROKEN_Z,
                new Vector3f(BROKEN_X + 0.5f, BROKEN_Y + 0.5f, BROKEN_Z + 0.5f));
        assertEquals(new Vector3f(BROKEN_X + 0.5f, BROKEN_Y + 1.5f, BROKEN_Z + 0.5f), escape);
    }

    @Test
    void resolveEscapePrefersSideNearestPreference() {
        // Embedded cell with both X neighbours passable; player on the +X side.
        World world = worldWith((x, y, z) ->
                (y == BROKEN_Y && (x == BROKEN_X + 1 || x == BROKEN_X - 1)) ? BlockType.AIR : BlockType.DIRT);
        Vector3f escape = DropSpawnResolver.resolveEscape(world, BROKEN_X, BROKEN_Y, BROKEN_Z,
                new Vector3f(30f, BROKEN_Y + 0.5f, BROKEN_Z + 0.5f));
        assertEquals(new Vector3f(BROKEN_X + 1.5f, BROKEN_Y + 0.5f, BROKEN_Z + 0.5f), escape);
    }

    @Test
    void resolveEscapeReturnsNullWhenFullyEnclosed() {
        World world = worldWith((x, y, z) -> BlockType.DIRT);
        assertNull(DropSpawnResolver.resolveEscape(world, BROKEN_X, BROKEN_Y, BROKEN_Z, null));
    }

    @Test
    void isEmbeddedMatchesSolidCellsOnly() {
        World world = worldWith((x, y, z) ->
                (y == BROKEN_Y) ? BlockType.DIRT : BlockType.AIR);
        assertTrue(DropSpawnResolver.isEmbedded(world, BROKEN_X, BROKEN_Y, BROKEN_Z),
                "a solid cell embeds the drop");
        assertFalse(DropSpawnResolver.isEmbedded(world, BROKEN_X, BROKEN_Y + 1, BROKEN_Z),
                "air does not embed the drop");
    }

    @Test
    void isPassableAllowsAirAndWaterButNotSolid() {
        World world = worldWith((x, y, z) -> {
            if (y == BROKEN_Y) {
                return BlockType.AIR;
            }
            if (y == BROKEN_Y + 1) {
                return BlockType.WATER;
            }
            return BlockType.DIRT;
        });
        assertTrue(DropSpawnResolver.isPassable(world, BROKEN_X, BROKEN_Y, BROKEN_Z), "air is passable");
        assertTrue(DropSpawnResolver.isPassable(world, BROKEN_X, BROKEN_Y + 1, BROKEN_Z), "water is passable");
        assertFalse(DropSpawnResolver.isPassable(world, BROKEN_X, BROKEN_Y - 1, BROKEN_Z), "solid is not passable");
    }
}
