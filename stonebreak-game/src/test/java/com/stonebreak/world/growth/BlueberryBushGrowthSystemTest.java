package com.stonebreak.world.growth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.blueberrybush.BlueberryBushState;
import com.stonebreak.blocks.blueberrybush.BlueberryBushState.Stage;

class BlueberryBushGrowthSystemTest {

    private static final int X = 3;
    private static final int Y = 70;
    private static final int Z = -4;
    private static final int LONGEST_GROW =
        BlueberryBushGrowthSystem.MIN_GROW_TICKS + BlueberryBushGrowthSystem.GROW_TICK_RANGE;
    private static final int LONGEST_FRUIT =
        BlueberryBushGrowthSystem.MIN_FRUIT_TICKS + BlueberryBushGrowthSystem.FRUIT_TICK_RANGE;

    /** In-memory world whose block writes re-enter the system, like World.setBlockAt's funnel. */
    private static final class FakeWorld implements SaplingWorld {
        final Map<BlockPos, BlockType> blocks = new HashMap<>();
        final Map<BlockPos, String> states = new HashMap<>();
        final List<BlockPos> announced = new ArrayList<>();
        final BlueberryBushGrowthSystem system = new BlueberryBushGrowthSystem(this, new Random(7));

        FakeWorld() {
            system.setStageListener((x, y, z) -> announced.add(new BlockPos(x, y, z)));
        }

        @Override
        public BlockType getBlock(int x, int y, int z) {
            return blocks.getOrDefault(new BlockPos(x, y, z), BlockType.AIR);
        }

        @Override
        public void setBlock(int x, int y, int z, BlockType type) {
            BlockType previous = getBlock(x, y, z);
            blocks.put(new BlockPos(x, y, z), type);
            states.remove(new BlockPos(x, y, z));
            system.onBlockChanged(x, y, z, previous, type);
        }

        @Override
        public String getState(int x, int y, int z) {
            return states.get(new BlockPos(x, y, z));
        }

        @Override
        public void setState(int x, int y, int z, String state) {
            states.put(new BlockPos(x, y, z), state);
        }

        @Override
        public boolean isAreaLoaded(int x, int z, int radius) {
            return true;
        }

        BlueberryBushState bush() {
            return BlueberryBushState.parse(getState(X, Y, Z));
        }
    }

    /** A ripe bush loaded from disk: ripe bushes carry no timer, so nothing tracks them. */
    private static FakeWorld worldWithRipeBush() {
        FakeWorld world = new FakeWorld();
        world.blocks.put(new BlockPos(X, Y, Z), BlockType.BLUEBERRY_BUSH);
        world.setState(X, Y, Z, new BlueberryBushState(Stage.BERRIES_RIPE, 0).toStateString());
        return world;
    }

    private static FakeWorld worldWithBush() {
        FakeWorld world = new FakeWorld();
        world.setBlock(X, Y, Z, BlockType.BLUEBERRY_BUSH);
        return world;
    }

    @Test
    void placedBushStartsAsASproutWithAGrowthTimer() {
        FakeWorld world = worldWithBush();
        BlueberryBushState s = world.bush();
        assertEquals(Stage.SPROUT, s.stage());
        assertTrue(s.ticksLeft() >= BlueberryBushGrowthSystem.MIN_GROW_TICKS && s.ticksLeft() < LONGEST_GROW,
            "timer " + s.ticksLeft());
        assertEquals(1, world.system.getTrackedCount());
        assertEquals(List.of(new BlockPos(X, Y, Z)), world.announced);
    }

    @Test
    void growsOneStageAtATimeToRipe() {
        FakeWorld world = worldWithBush();
        Stage[] expected = Stage.values();
        for (int i = 1; i < expected.length; i++) {
            Stage from = expected[i - 1];
            int limit = from.isGrowing() ? LONGEST_GROW : LONGEST_FRUIT;
            int ticks = 0;
            while (world.bush().stage() == from && ticks <= limit) {
                world.system.advanceTicks(1);
                ticks++;
            }
            assertEquals(expected[i], world.bush().stage(), "after " + from);
            assertTrue(ticks <= limit, from + " took " + ticks + " ticks");
        }
        assertTrue(world.bush().isRipe());
        assertEquals(0, world.system.getTrackedCount(), "a ripe bush waits for a harvest");
        assertEquals(expected.length, world.announced.size(), "placement + one announcement per stage");
    }

    @Test
    void ripeBushStaysRipeUntilPicked() {
        FakeWorld world = worldWithRipeBush();
        world.system.advanceTicks(LONGEST_FRUIT * 3);
        assertTrue(world.bush().isRipe());
    }

    @Test
    void berryStagesLastTwoToThreeMinutes() {
        int twoMinutes = 2 * 60 * 20;
        int threeMinutes = 3 * 60 * 20;
        for (Stage stage : List.of(Stage.MATURE, Stage.BERRIES_UNRIPE, Stage.BERRIES_RIPENING)) {
            assertEquals(twoMinutes, BlueberryBushGrowthSystem.timerFor(stage, 0f), stage.name());
            int longest = BlueberryBushGrowthSystem.timerFor(stage, 1f);
            assertTrue(longest < threeMinutes && longest >= threeMinutes - 1, stage + " longest " + longest);
        }
    }

    @Test
    void harvestSendsARipeBushBackToMatureAndKeepsTheBlock() {
        FakeWorld world = worldWithRipeBush();

        assertTrue(world.system.harvest(X, Y, Z));
        assertEquals(BlockType.BLUEBERRY_BUSH, world.getBlock(X, Y, Z));
        BlueberryBushState s = world.bush();
        assertEquals(Stage.MATURE, s.stage());
        assertTrue(s.ticksLeft() >= BlueberryBushGrowthSystem.MIN_FRUIT_TICKS && s.ticksLeft() < LONGEST_FRUIT,
            "fruit timer " + s.ticksLeft());
        assertEquals(1, world.system.getTrackedCount());
        assertEquals(1, world.announced.size());

        world.system.advanceTicks(s.ticksLeft());
        assertEquals(Stage.BERRIES_UNRIPE, world.bush().stage(), "fruits again after picking");
    }

    @Test
    void harvestRefusesUnripeBushesAndOtherBlocks() {
        FakeWorld world = worldWithBush();
        assertFalse(world.system.harvest(X, Y, Z));
        assertEquals(Stage.SPROUT, world.bush().stage());

        world.blocks.put(new BlockPos(0, Y, 0), BlockType.DIRT);
        world.setState(0, Y, 0, new BlueberryBushState(Stage.BERRIES_RIPE, 0).toStateString());
        assertFalse(world.system.harvest(0, Y, 0));
    }

    @Test
    void breakingTheBushStopsTrackingIt() {
        FakeWorld world = worldWithBush();
        world.setBlock(X, Y, Z, BlockType.AIR);
        assertEquals(0, world.system.getTrackedCount());
        world.system.advanceTicks(LONGEST_GROW);
        assertNull(world.getState(X, Y, Z));
    }

    @Test
    void timerOnlyRewritesDoNotAnnounce() {
        FakeWorld world = worldWithBush();
        world.announced.clear();
        world.system.advanceTicks(BlueberryBushGrowthSystem.MIN_GROW_TICKS - 1);
        assertTrue(world.announced.isEmpty());
        assertTrue(world.bush().ticksLeft() < LONGEST_GROW - 20, "remaining time was persisted");
    }
}
