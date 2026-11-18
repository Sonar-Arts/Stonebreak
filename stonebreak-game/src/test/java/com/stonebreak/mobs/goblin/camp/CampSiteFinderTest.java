package com.stonebreak.mobs.goblin.camp;

import com.stonebreak.blocks.BlockType;
import org.joml.Vector3i;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a goblin band makes camp: a cave or overhang with a ground roof over the fire that it can
 * walk into and out of — and when there is none, one it digs through soil: a sealed cave, a soil
 * hollow widened, or a den of its own in a hillside. Never through rock.
 */
class CampSiteFinderTest {

    private static final int TOP = FakeTerrain.GROUND_TOP;

    private static CampSitePlan search(FakeTerrain terrain, int x, int z) {
        CampSiteFinder finder = new CampSiteFinder(terrain, terrain.walker(), new org.joml.Vector3i(x, TOP + 1, z),
                cell -> true);
        int guard = 0;
        while (!finder.step(200)) {
            assertTrue(++guard < 1000, "the search ends");
        }
        return finder.result();
    }

    /** A 7x7 room, floor at TOP-5, three blocks high. */
    private static void room(FakeTerrain t, int cx, int cz) {
        t.carve(cx - 3, TOP - 5, cz - 3, cx + 3, TOP - 3, cz + 3);
    }

    /** A sloping walkway from the room's east wall up to the surface. */
    private static void openTheRoomEast(FakeTerrain t, int cx, int cz) {
        int y = TOP - 5;
        for (int x = cx + 4; y <= TOP + 1; x++, y++) {
            t.carve(x, y, cz, x, y + 2, cz);
        }
    }

    @Test
    void solidGroundHasNowhereToCamp() {
        assertNull(search(new FakeTerrain(), 0, 0));
    }

    @Test
    void anOpenCaveIsChosenAndNeedsNoDigging() {
        FakeTerrain t = new FakeTerrain();
        room(t, 6, 0);
        openTheRoomEast(t, 6, 0);
        CampSitePlan plan = search(t, 0, 0);
        assertNotNull(plan);
        assertFalse(plan.needsDigging());
        Vector3i site = plan.site();
        assertTrue(t.standable(site.x, site.y, site.z) && t.covered(site.x, site.y, site.z));
        assertTrue(Math.abs(site.x - 6) <= 3 && Math.abs(site.z) <= 3, "inside the room: " + site);
    }

    /**
     * The point of asking the engine where the band can walk: the site it picks is one a goblin's
     * own route reaches, by the same search its path agent will run.
     */
    @Test
    void theChosenSiteIsOneAGoblinsRouteReaches() {
        FakeTerrain t = new FakeTerrain();
        room(t, 6, 0);
        openTheRoomEast(t, 6, 0);
        CampSitePlan plan = search(t, 0, 0);
        assertNotNull(plan);
        Vector3i site = plan.site();
        com.openmason.engine.nav.NavWorld nav = t.nav();
        long id = nav.searchGround(new com.openmason.engine.nav.NavWorld.GroundQuery(0.5f, TOP + 1, 0.5f,
                site.x + 0.5f, site.y, site.z + 0.5f, 0f, 4, 2, FakeTerrain.goblin(),
                new com.openmason.engine.nav.NavLimits(20_000, Float.MAX_VALUE, 1.0f)));
        nav.step(Integer.MAX_VALUE);
        assertTrue(nav.poll(id).reachedGoal(), "a goblin can walk to the camp it chose: " + site);
    }

    @Test
    void aSealedCaveInRockIsLeftAlone() {
        FakeTerrain t = new FakeTerrain();
        room(t, 0, 0);
        assertNull(search(t, 0, 0), "goblins dig no rock");
    }

    @Test
    void aSealedCaveUnderSoilIsDugIntoUpAStair() {
        FakeTerrain t = FakeTerrain.soil();
        room(t, 0, 0);
        CampSitePlan plan = search(t, 0, 0);
        assertNotNull(plan, "a sealed cave can still be dug into");
        assertTrue(plan.needsDigging());
        assertTrue(plan.dig().size() <= EntrancePlanner.MAX_STEPS * 3);
        // Dig it out and the band can walk in from the sky.
        for (Vector3i c : plan.dig()) {
            t.set(c.x, c.y, c.z, BlockType.AIR);
        }
        assertTrue(CampSiteFinder.reachesSky(t.walker(), t, plan.site()));
    }

    @Test
    void aTinyHoleIsNoRoomForACamp() {
        FakeTerrain t = new FakeTerrain();
        t.carve(0, TOP - 5, 0, 1, TOP - 4, 1);
        assertNull(search(t, 0, 0));
    }

    @Test
    void aSiteTheCallerRulesOutIsNotChosen() {
        FakeTerrain t = new FakeTerrain();
        room(t, 6, 0);
        openTheRoomEast(t, 6, 0);
        CampSiteFinder finder = new CampSiteFinder(t, t.walker(), new org.joml.Vector3i(0, TOP + 1, 0), cell -> false);
        while (!finder.step(500)) { }
        assertNull(finder.result());
    }

    @Test
    void theEntranceDigsNoWaterOrBedrock() {
        FakeTerrain t = FakeTerrain.soil();
        Vector3i site = new Vector3i(0, TOP - 5, 0);
        t.carve(0, TOP - 5, 0, 0, TOP - 4, 0);
        // Water on the second step of every staircase but the westward one.
        t.set(2, TOP - 5 + 2, 0, BlockType.WATER);
        t.set(0, TOP - 5 + 2, 2, BlockType.WATER);
        t.set(0, TOP - 5 + 2, -2, BlockType.WATER);
        var cells = EntrancePlanner.plan(t, site);
        assertNotNull(cells, "west is still dry");
        for (Vector3i c : cells) {
            assertTrue(c.x < 0 && c.z == 0, "only west: " + c);
        }
        // Bedrock that way too, and there is no way in at all.
        t.set(-2, TOP - 5 + 2, 0, BlockType.BEDROCK);
        assertNull(EntrancePlanner.plan(t, site));
    }

    @Test
    void theEntranceDigsNoRock() {
        FakeTerrain t = FakeTerrain.soil();
        Vector3i site = new Vector3i(0, TOP - 5, 0);
        t.carve(0, TOP - 5, 0, 0, TOP - 4, 0);
        // A seam of stone on the second step of every staircase but the westward one.
        t.set(2, TOP - 5 + 2, 0, BlockType.STONE);
        t.set(0, TOP - 5 + 2, 2, BlockType.IRON_ORE);
        t.set(0, TOP - 5 + 2, -2, BlockType.SANDSTONE);
        var cells = EntrancePlanner.plan(t, site);
        assertNotNull(cells, "west is all soil");
        for (Vector3i c : cells) {
            assertTrue(c.x < 0 && c.z == 0, "only west: " + c);
            assertTrue(NaturalGround.isDiggable(t.block(c.x, c.y, c.z)), "soil only: " + c);
        }
    }

    @Test
    void aRoofedHouseIsNoCamp() {
        FakeTerrain t = new FakeTerrain();
        room(t, 6, 0);
        openTheRoomEast(t, 6, 0);
        // The same room, but someone boarded its ceiling: a cellar, not a cave.
        for (int x = 3; x <= 9; x++) {
            for (int z = -3; z <= 3; z++) {
                t.set(x, TOP - 2, z, BlockType.WOOD_PLANKS);
            }
        }
        assertNull(search(t, 0, 0));
    }

    @Test
    void theEntranceDigsThroughNothingBuilt() {
        FakeTerrain t = FakeTerrain.soil();
        Vector3i site = new Vector3i(0, TOP - 5, 0);
        t.carve(0, TOP - 5, 0, 0, TOP - 4, 0);
        // Somebody's cellar wall on the second step of every staircase but the westward one.
        t.set(2, TOP - 5 + 2, 0, BlockType.COBBLESTONE);
        t.set(0, TOP - 5 + 2, 2, BlockType.WOOD_PLANKS);
        t.set(0, TOP - 5 + 2, -2, BlockType.CHEST);
        var cells = EntrancePlanner.plan(t, site);
        assertNotNull(cells, "west is plain soil");
        for (Vector3i c : cells) {
            assertTrue(c.x < 0 && c.z == 0, "only west: " + c);
        }
        t.set(-2, TOP - 5 + 2, 0, BlockType.FURNACE);
        assertNull(EntrancePlanner.plan(t, site));
    }

    @Test
    void goblinsDigOnlySoil() {
        for (BlockType soil : new BlockType[] {BlockType.DIRT, BlockType.GRASS, BlockType.SNOWY_DIRT,
                BlockType.SWAMPY_GRASS, BlockType.DRY_GRASS, BlockType.SAND, BlockType.RED_SAND,
                BlockType.GRAVEL, BlockType.CLAY}) {
            assertTrue(NaturalGround.isDiggable(soil), soil.name());
        }
        for (BlockType rock : new BlockType[] {BlockType.STONE, BlockType.IRON_ORE, BlockType.SANDSTONE,
                BlockType.LIMESTONE, BlockType.ICE, BlockType.LEAVES, BlockType.BEDROCK}) {
            assertFalse(NaturalGround.isDiggable(rock), rock.name());
        }
        assertFalse(NaturalGround.isDiggable(BlockType.AIR));
        assertFalse(NaturalGround.isDiggable(BlockType.CHEST));
        assertFalse(NaturalGround.isDiggable(BlockType.WOOD_PLANKS));
        assertFalse(NaturalGround.isDiggable(BlockType.COBBLESTONE));
        assertFalse(NaturalGround.isDiggable(null), "unloaded ground");
        assertTrue(NaturalGround.isNatural(BlockType.WATER));
        assertFalse(NaturalGround.isNatural(BlockType.TORCH_PLACED));
    }

    /** A one-block-thick ledge of {@code roof} over open ground east of the band, three up. */
    private static void ledge(FakeTerrain t, BlockType roof) {
        t.fill(2, TOP + 3, -2, 6, TOP + 3, 2, roof);
    }

    @Test
    void anOverhangIsACampSite() {
        FakeTerrain t = new FakeTerrain();
        ledge(t, BlockType.STONE);
        CampSitePlan plan = search(t, 0, 0);
        assertNotNull(plan, "a ledge overhead is roof enough");
        assertFalse(plan.needsDigging());
        Vector3i site = plan.site();
        assertTrue(t.roofed(site.x, site.y, site.z), "the fire is under the ledge: " + site);
    }

    @Test
    void aTreeCanopyIsNoRoof() {
        FakeTerrain t = new FakeTerrain();
        ledge(t, BlockType.LEAVES);
        assertNull(search(t, 0, 0), "leaves overhead are no camp");
    }

    @Test
    void aWalkInCaveBeatsDigging() {
        FakeTerrain t = FakeTerrain.soil();
        room(t, 6, 0);
        openTheRoomEast(t, 6, 0);
        CampSitePlan plan = search(t, 0, 0);
        assertNotNull(plan);
        assertFalse(plan.needsDigging(), "the soil all round could be dug, but the cave is open");
    }

    /** A soil hill east of the band: x 3..10, five blocks over the ground. */
    private static void hill(FakeTerrain t, BlockType b, int x0) {
        t.fill(x0, TOP + 1, -4, x0 + 7, TOP + 5, 4, b);
    }

    @Test
    void aDenIsDugIntoASoilHillside() {
        FakeTerrain t = FakeTerrain.soil();
        hill(t, BlockType.DIRT, 3);
        CampSitePlan plan = search(t, 0, 0);
        assertNotNull(plan, "no cave about, so they dig a den");
        assertTrue(plan.needsDigging());
        for (Vector3i c : plan.dig()) {
            assertTrue(NaturalGround.isDiggable(t.block(c.x, c.y, c.z)), "soil only: " + c);
            t.set(c.x, c.y, c.z, BlockType.AIR);
        }
        Vector3i site = plan.site();
        assertTrue(t.standable(site.x, site.y, site.z) && t.roofed(site.x, site.y, site.z), "a roofed den: " + site);
        assertTrue(CampSiteFinder.reachesSky(t.walker(), t, site), "and they can walk back out");
    }

    @Test
    void aRockHillGetsNoDen() {
        FakeTerrain t = FakeTerrain.soil();
        hill(t, BlockType.STONE, 3);
        assertNull(search(t, 0, 0), "goblins dig no rock");
    }

    @Test
    void aSoilHollowIsWidenedBeforeAFreshHillsideIsOpened() {
        FakeTerrain t = FakeTerrain.soil();
        hill(t, BlockType.DIRT, 3);
        hill(t, BlockType.DIRT, -10);
        // The west hill already has a hollow in its face — too low to stand in, but half a den dug.
        t.carve(-6, TOP + 2, -1, -3, TOP + 2, 1);
        CampSitePlan plan = search(t, 0, 0);
        assertNotNull(plan);
        assertTrue(plan.needsDigging());
        assertTrue(plan.site().x < 0, "the hollow is cheaper to dig: " + plan.site());
    }

    @Test
    void aDenIsNeverDugBesideWater() {
        FakeTerrain t = FakeTerrain.soil();
        hill(t, BlockType.DIRT, 3);
        // A spring inside the hill, right where any den's room would go.
        t.fill(4, TOP + 1, -4, 9, TOP + 2, 4, BlockType.WATER);
        t.fill(3, TOP + 1, -4, 3, TOP + 2, 4, BlockType.DIRT);
        assertNull(search(t, 0, 0));
    }
}
