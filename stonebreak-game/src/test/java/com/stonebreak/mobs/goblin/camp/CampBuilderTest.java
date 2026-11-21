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

    /** A 3x3 cave, too cramped for a bedroll more than a step from the fire, with the same way out. */
    private static FakeTerrain cramped() {
        FakeTerrain t = new FakeTerrain();
        t.carve(-1, FLOOR, -1, 1, FLOOR + 2, 1);
        int y = FLOOR;
        for (int x = 2; y <= TOP + 1; x++, y++) {
            t.carve(x, y, 0, x, y + 2, 0);
        }
        t.set(0, FLOOR, 0, BlockType.CAMPFIRE);
        return t;
    }

    private static long stakes(List<CampBuilder.Placement> built) {
        return built.stream().filter(p -> p.block() == BlockType.PALISADE).count();
    }

    /** Builds until the camp says it is done; returns what went up. */
    private static List<CampBuilder.Placement> buildOut(FakeTerrain t, int population) {
        List<CampBuilder.Placement> built = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            CampBuilder.Placement p = CampBuilder.next(t, t.walker(), FIRE, population, c -> false);
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
        FakeTerrain small = cave();
        assertNull(CampBuilder.next(small, small.walker(), FIRE, CampBuilder.BEDROLL_POPULATION - 1, c -> false));
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
        assertNotNull(CampSiteFinder.pathToSky(t.walker(), t, new Vector3i(1, FLOOR, 0)), "the camp can still walk out");
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

    @Test
    void theMouthIsWalledAtSixGoblinsAndNoWiderThanNine() {
        assertEquals(0, stakes(buildOut(cave(), CampBuilder.WALL_POPULATION - 1)));
        long mouth = stakes(buildOut(cave(), CampBuilder.WALL_POPULATION));
        assertTrue(mouth > 0, "a wall across the mouth");
        assertTrue(mouth <= 2 * CampBuilder.WALL_REACH, "four either side of the gap at most: " + mouth);
    }

    @Test
    void theWallsGrowWithTheCamp() {
        long six = stakes(buildOut(cave(), CampBuilder.WALL_POPULATION));
        long nine = stakes(buildOut(cave(), CampBuilder.SECOND_ROW_POPULATION));
        long twelve = stakes(buildOut(cave(), CampBuilder.YARD_POPULATION));
        assertTrue(six < nine && nine < twelve, six + " < " + nine + " < " + twelve);
    }

    @Test
    void theYardFencesLeanOutSidewaysAndTheWayOutStaysOpen() {
        FakeTerrain t = cave();
        List<CampBuilder.Placement> built = buildOut(t, CampBuilder.YARD_POPULATION);
        assertTrue(built.stream().anyMatch(p -> p.block() == BlockType.PALISADE
                && PalisadeState.parse(p.state()).facing() == StairState.Facing.NORTH), "a side fence: " + built);
        assertTrue(built.stream().anyMatch(p -> p.block() == BlockType.PALISADE
                && PalisadeState.parse(p.state()).facing() == StairState.Facing.SOUTH), "and the other: " + built);
        List<Vector3i> out = CampSiteFinder.pathToSky(t.walker(), t, new Vector3i(1, FLOOR, 0));
        assertNotNull(out, "the camp can still walk out");
    }

    @Test
    void stakesAreNeverStackedOnStakes() {
        List<CampBuilder.Placement> walls = buildOut(cave(), CampBuilder.YARD_POPULATION).stream()
                .filter(p -> p.block() == BlockType.PALISADE).toList();
        long columns = walls.stream().map(p -> p.cell().x + "," + p.cell().z).distinct().count();
        assertEquals(walls.size(), columns, "one stake per column: " + walls);
    }

    @Test
    void aCaveTooCrampedForBedrollsStillGetsItsWall() {
        List<CampBuilder.Placement> built = buildOut(cramped(), CampBuilder.WALL_POPULATION);
        assertTrue(stakes(built) > 0, "bedrolls that fit nowhere hold nothing up: " + built);
    }

    @Test
    void theSurveyListsTheStakesAlreadyStanding() {
        FakeTerrain t = cave();
        List<CampBuilder.Placement> built = buildOut(t, CampBuilder.WALL_POPULATION);
        CampBuilder.Survey survey = CampBuilder.survey(t, t.walker(), FIRE, CampBuilder.WALL_POPULATION, c -> false);
        assertNull(survey.next());
        for (CampBuilder.Placement p : built) {
            assertTrue(survey.standing().contains(p.cell()), "watched for breaking: " + p);
        }
    }
}
