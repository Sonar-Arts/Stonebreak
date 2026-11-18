package com.stonebreak.world.growth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.openmason.engine.voxel.world.CubePos;

class SaplingGrowthSystemTest {

    private static final int X = 5;
    private static final int Y = 80;
    private static final int Z = 6;
    private static final int LONGEST_WAIT =
        SaplingGrowthSystem.MIN_GROW_TICKS + SaplingGrowthSystem.GROW_TICK_RANGE;

    /** In-memory world whose writes re-enter the system, like World.setBlockAt's funnel. */
    private static final class FakeWorld implements SaplingWorld {
        final Map<BlockPos, BlockType> blocks = new HashMap<>();
        final Map<CubePos, byte[]> sections = new HashMap<>();
        SaplingGrowthSystem system;
        boolean loaded = true;

        private static BlockPos at(long x, long y, long z) {
            return new BlockPos(Math.toIntExact(x), Math.toIntExact(y), Math.toIntExact(z));
        }

        @Override
        public BlockType getBlock(long x, long y, long z) {
            return blocks.getOrDefault(at(x, y, z), BlockType.AIR);
        }

        @Override
        public void setBlock(long x, long y, long z, BlockType type) {
            BlockType previous = getBlock(x, y, z);
            blocks.put(at(x, y, z), type);
            system.onBlockChanged(x, y, z, previous, type);
        }

        @Override
        public boolean isAreaLoaded(long x, long y, long z, int radius, int height) {
            return loaded;
        }

        @Override
        public byte[] readSaplings(CubePos cube) {
            return sections.get(cube);
        }

        @Override
        public void writeSaplings(CubePos cube, byte[] section) {
            if (section == null) {
                sections.remove(cube);
            } else {
                sections.put(cube, section);
            }
        }
    }

    private static FakeWorld worldWithSaplingOn(BlockType ground) {
        FakeWorld world = new FakeWorld();
        world.system = new SaplingGrowthSystem(world, new Random(42));
        world.blocks.put(new BlockPos(X, Y - 1, Z), ground);
        world.setBlock(X, Y, Z, BlockType.CYPRESS_SAPLING);
        return world;
    }

    @Test
    void placedSaplingGetsAFiveToTenMinuteTimer() {
        FakeWorld world = worldWithSaplingOn(BlockType.SWAMPY_GRASS);
        int ticks = world.system.remainingAt(X, Y, Z);
        assertTrue(ticks >= 5 * 60 * 20 && ticks < 10 * 60 * 20, "timer " + ticks);
        assertEquals(1, world.system.getTrackedCount());
    }

    @Test
    void saplingGrowsIntoACypressWhenDue() {
        FakeWorld world = worldWithSaplingOn(BlockType.GRASS);
        world.system.advanceTicks(LONGEST_WAIT);
        assertEquals(BlockType.CYPRESS_LOG, world.getBlock(X, Y, Z));
        assertTrue(world.blocks.containsValue(BlockType.CYPRESS_LEAVES));
        assertEquals(0, world.system.getTrackedCount());
    }

    @Test
    void doesNotGrowWithoutSoil() {
        FakeWorld world = worldWithSaplingOn(BlockType.STONE);
        world.system.advanceTicks(LONGEST_WAIT + 100);
        assertEquals(BlockType.CYPRESS_SAPLING, world.getBlock(X, Y, Z));
        assertEquals(1, world.system.getTrackedCount(), "keeps retrying");
    }

    @Test
    void waitsWhileBlockedOrUnloadedThenGrows() {
        FakeWorld world = worldWithSaplingOn(BlockType.DIRT);
        world.blocks.put(new BlockPos(X, Y + 4, Z), BlockType.STONE);
        world.loaded = false;
        world.system.advanceTicks(LONGEST_WAIT);
        assertEquals(BlockType.CYPRESS_SAPLING, world.getBlock(X, Y, Z));

        world.loaded = true;
        world.system.advanceTicks(SaplingGrowthSystem.RETRY_TICKS);
        assertEquals(BlockType.CYPRESS_SAPLING, world.getBlock(X, Y, Z), "still blocked overhead");

        world.blocks.remove(new BlockPos(X, Y + 4, Z));
        world.system.advanceTicks(SaplingGrowthSystem.RETRY_TICKS);
        assertEquals(BlockType.CYPRESS_LOG, world.getBlock(X, Y, Z));
    }

    @Test
    void growthNeverOverwritesPlayerBlocks() {
        FakeWorld world = worldWithSaplingOn(BlockType.GRASS);
        // A wall beside the trunk, through the whole canopy height.
        for (int y = Y; y < Y + 30; y++) {
            world.blocks.put(new BlockPos(X + 2, y, Z), BlockType.STONE_BRICKS);
        }
        world.system.advanceTicks(LONGEST_WAIT);
        assertEquals(BlockType.CYPRESS_LOG, world.getBlock(X, Y, Z));
        for (int y = Y; y < Y + 30; y++) {
            assertEquals(BlockType.STONE_BRICKS, world.getBlock(X + 2, y, Z));
        }
    }

    @Test
    void brokenSaplingStopsBeingTracked() {
        FakeWorld world = worldWithSaplingOn(BlockType.GRASS);
        world.setBlock(X, Y, Z, BlockType.AIR);
        assertEquals(0, world.system.getTrackedCount());
    }

    @Test
    void progressIsSavedAndResumesAfterReload() {
        FakeWorld world = worldWithSaplingOn(BlockType.GRASS);
        CubePos cube = CubePos.ofBlock(X, Y, Z);
        int start = world.system.remainingAt(X, Y, Z);
        // Persisted with the cube every PERSIST_INTERVAL_TICKS.
        world.system.advanceTicks(SaplingGrowthSystem.PERSIST_INTERVAL_TICKS * 5);
        assertEquals(start - SaplingGrowthSystem.PERSIST_INTERVAL_TICKS * 5,
            SaplingGrowthSystem.decodeSection(world.sections.get(cube)).values().iterator().next());

        // Unload-pending writes the latest progress into the cube; a fresh system resumes from it.
        world.system.advanceTicks(7);
        world.system.onCubeUnloadPending(cube);
        world.system.onCubeUnloaded(cube);
        assertEquals(0, world.system.getTrackedCount());
        int saved = start - SaplingGrowthSystem.PERSIST_INTERVAL_TICKS * 5 - 7;
        assertEquals(saved, SaplingGrowthSystem.decodeSection(world.sections.get(cube)).values().iterator().next());

        FakeWorld reloaded = new FakeWorld();
        reloaded.system = new SaplingGrowthSystem(reloaded, new Random(1));
        reloaded.blocks.put(new BlockPos(X, Y - 1, Z), BlockType.GRASS);
        reloaded.blocks.put(new BlockPos(X, Y, Z), BlockType.CYPRESS_SAPLING);
        reloaded.sections.put(cube, world.sections.get(cube));
        reloaded.system.onCubeLoaded(cube);
        assertEquals(1, reloaded.system.getTrackedCount());
        reloaded.system.advanceTicks(saved - 1);
        assertEquals(BlockType.CYPRESS_SAPLING, reloaded.getBlock(X, Y, Z), "one tick early");
        reloaded.system.advanceTicks(1);
        assertEquals(BlockType.CYPRESS_LOG, reloaded.getBlock(X, Y, Z));
    }
}
