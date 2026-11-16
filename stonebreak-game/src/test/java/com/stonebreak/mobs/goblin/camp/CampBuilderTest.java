package com.stonebreak.mobs.goblin.camp;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.palisade.PalisadeState;
import com.stonebreak.blocks.stairs.StairState;
import org.joml.Vector3i;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a growing camp builds, and that it never builds across its own way out. */
class CampBuilderTest {

    private static final int TOP = FakeTerrain.GROUND_TOP;
    private static final int FLOOR = TOP - 5;

    /** A 9x9 cave three high, open to the east by a sloping walkway, the fire in its middle. */
    private static FakeTerrain cave() {
        FakeTerrain t = new FakeTerrain();
        t.carve(-4, FLOOR, -4, 4, FLOOR + 2, 4);
        int y = FLOOR;
        for (int x = 5; y <= TOP + 1; x++, y++) {
            t.carve(x, y, 0, x, y + 2, 0);
        }
        t.set(0, FLOOR, 0, BlockType.CAMPFIRE);
        return t;
    }

    private static final BlockPos FIRE = new BlockPos(0, FLOOR, 0);

    /** Builds until the camp says it is done; returns what went up. */
    private static List<CampBuilder.Placement> buildOut(FakeTerrain t, int population) {
        List<CampBuilder.Placement> built = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            CampBuilder.Placement p = CampBuilder.next(t, FIRE, population, c -> false);
            if (p == null) {
                return built;
            }
            t.set(p.cell().x, p.cell().y, p.cell().z, p.block());
            built.add(p);
        }
        throw new AssertionError("never finished building: " + built);
    }

    @Test
    void aSmallCampBuildsNothing() {
        assertNull(CampBuilder.next(cave(), FIRE, CampBuilder.BEDROLL_POPULATION - 1, c -> false));
    }

    @Test
    void aCampOfFiveLaysABedrollForEachGoblin() {
        FakeTerrain t = cave();
        List<CampBuilder.Placement> built = buildOut(t, 5);
        assertEquals(5, built.size());
        assertTrue(built.stream().allMatch(p -> p.block() == BlockType.BEDROLL));
        assertEquals(5, CampBuilder.count(t, new Vector3i(0, FLOOR, 0), BlockType.BEDROLL));
    }

    @Test
    void aCampOfEightAddsARackAndStakesTheMouthButKeepsItsWayOut() {
        FakeTerrain t = cave();
        List<CampBuilder.Placement> built = buildOut(t, 8);
        assertEquals(1, built.stream().filter(p -> p.block() == BlockType.MEAT_RACK).count());
        assertTrue(built.stream().anyMatch(p -> p.block() == BlockType.PALISADE), "stakes at the mouth: " + built);
        assertNotNull(CampSiteFinder.pathToSky(t, new Vector3i(1, FLOOR, 0)), "the camp can still walk out");
    }

    @Test
    void theSpikedWallLeansOutOfTheCaveMouth() {
        List<CampBuilder.Placement> walls = buildOut(cave(), 8).stream()
                .filter(p -> p.block() == BlockType.PALISADE).toList();
        assertTrue(!walls.isEmpty(), "stakes at the mouth");
        for (CampBuilder.Placement wall : walls) {
            // The cave opens to the east, so every stake points east, away from the fire.
            assertEquals(StairState.Facing.EAST, PalisadeState.parse(wall.state()).facing(), wall.toString());
        }
    }
}
