package com.stonebreak.world.growth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.chunk.Chunk;

class SaplingGrowthSystemTest {

    private static final int X = 5;
    private static final int Y = 80;
    private static final int Z = 6;
    private static final int LONGEST_WAIT =
        SaplingGrowthSystem.MIN_GROW_TICKS + SaplingGrowthSystem.GROW_TICK_RANGE;

    /** In-memory world whose writes re-enter the system, like World.setBlockAt's funnel. */
    private static final class FakeWorld implements SaplingWorld {
        final Map<BlockPos, BlockType> blocks = new HashMap<>();
        final Map<BlockPos, String> states = new HashMap<>();
        SaplingGrowthSystem system;
        boolean loaded = true;

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
            return loaded;
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
        int ticks = SaplingGrowthSystem.parseRemaining(world.getState(X, Y, Z));
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
        int start = SaplingGrowthSystem.parseRemaining(world.getState(X, Y, Z));
        world.system.advanceTicks(100);
        assertEquals(start - 100, SaplingGrowthSystem.parseRemaining(world.getState(X, Y, Z)));

        // Unload writes the latest progress onto the chunk; a fresh system resumes from it.
        Chunk chunk = new Chunk(0, 0);
        chunk.setBlock(X, Y, Z, BlockType.CYPRESS_SAPLING);
        world.system.advanceTicks(7);
        world.system.onChunkUnloaded(chunk);
        assertEquals(0, world.system.getTrackedCount());
        assertEquals(start - 107, SaplingGrowthSystem.parseRemaining(chunk.getBlockState(X, Y, Z)));

        FakeWorld reloaded = new FakeWorld();
        reloaded.system = new SaplingGrowthSystem(reloaded, new Random(1));
        reloaded.blocks.put(new BlockPos(X, Y - 1, Z), BlockType.GRASS);
        reloaded.blocks.put(new BlockPos(X, Y, Z), BlockType.CYPRESS_SAPLING);
        reloaded.system.onChunkLoaded(chunk);
        assertEquals(1, reloaded.system.getTrackedCount());
        reloaded.system.advanceTicks(start - 108);
        assertEquals(BlockType.CYPRESS_SAPLING, reloaded.getBlock(X, Y, Z), "one tick early");
        reloaded.system.advanceTicks(1);
        assertEquals(BlockType.CYPRESS_LOG, reloaded.getBlock(X, Y, Z));
    }
}
