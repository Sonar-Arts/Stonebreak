package com.stonebreak.world.growth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.waterSystem.handlers.FlowBlockInteraction;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.chunk.utils.LocalBlockKey;
import com.stonebreak.world.generation.trees.CypressTree;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Grows placed cypress saplings into {@link CypressTree}s after a random delay, the way a
 * Minecraft sapling matures. The delay only counts down while the sapling's chunk is loaded.
 *
 * <p>Each sapling keeps its remaining time in its block-state string ({@code grow:<ticks>}),
 * so progress is saved with the chunk and the sapling is found again on load by scanning the
 * chunk's block states, the same way {@code AnimatedBlockRegistry} does. The sapling's SBO
 * has no state by that name, so it renders with its default model.
 *
 * <p>Runs on authoritative worlds only, at 20 TPS like {@code LeafDecaySystem}.
 */
public final class SaplingGrowthSystem {

    public static final String STATE_PREFIX = "grow:";
    /** 5–10 minutes at 20 TPS. */
    static final int MIN_GROW_TICKS = 5 * 60 * 20;
    static final int GROW_TICK_RANGE = 5 * 60 * 20;
    /** Wait before retrying a sapling that was due but couldn't grow. */
    static final int RETRY_TICKS = 10 * 20;
    /** Clear cells a sapling needs above it: the shortest cypress trunk. */
    static final int REQUIRED_HEADROOM = 10;
    /** How often remaining time is written back to the block state for saving. */
    private static final int PERSIST_INTERVAL_TICKS = 20;
    private static final int MAX_TICKS_PER_FRAME = 2;
    private static final float TICK_INTERVAL = 1.0f / 20.0f;
    private static final int CHUNK_SIZE = WorldConfiguration.CHUNK_SIZE;

    private final SaplingWorld world;
    private final Random random;
    private final Map<BlockPos, Integer> remaining = new HashMap<>();
    private float tickAccumulator;
    private long logicalTick;

    public SaplingGrowthSystem(SaplingWorld world, Random random) {
        this.world = Objects.requireNonNull(world, "world");
        this.random = Objects.requireNonNull(random, "random");
    }

    /** Soil a sapling may be planted on and grow from. */
    public static boolean isSoil(BlockType block) {
        return block == BlockType.DIRT || block == BlockType.GRASS || block == BlockType.SWAMPY_GRASS;
    }

    public static boolean isSapling(BlockType block) {
        return block == BlockType.CYPRESS_SAPLING;
    }

    // ===== Tick driving =====

    /** Frame-time entry point: accumulates delta and runs logical ticks at 20 TPS. */
    public void tick(float deltaTimeSeconds) {
        float delta = Float.isFinite(deltaTimeSeconds) ? Math.max(0.0f, deltaTimeSeconds) : 0.0f;
        tickAccumulator += delta;
        int ticksToRun = 0;
        while (tickAccumulator >= TICK_INTERVAL && ticksToRun < MAX_TICKS_PER_FRAME) {
            tickAccumulator -= TICK_INTERVAL;
            ticksToRun++;
        }
        advanceTicks(ticksToRun);
    }

    /** Advances whole logical ticks directly — deterministic driver for tests. */
    public void advanceTicks(int ticks) {
        for (int i = 0; i < ticks; i++) {
            logicalTick++;
            step();
        }
    }

    public int getTrackedCount() {
        return remaining.size();
    }

    private void step() {
        if (remaining.isEmpty()) {
            return;
        }
        List<BlockPos> due = new ArrayList<>();
        for (Map.Entry<BlockPos, Integer> e : remaining.entrySet()) {
            int left = e.getValue() - 1;
            e.setValue(left);
            if (left <= 0) {
                due.add(e.getKey());
            }
        }
        // Growth writes blocks, which re-enter onBlockChanged and edit the map, so it runs
        // after the countdown pass rather than inside it.
        for (BlockPos pos : due) {
            if (tryGrow(pos)) {
                remaining.remove(pos);
            } else if (remaining.containsKey(pos)) {
                remaining.put(pos, RETRY_TICKS);
            }
        }
        if (logicalTick % PERSIST_INTERVAL_TICKS == 0) {
            remaining.forEach((pos, left) -> world.setState(pos.x(), pos.y(), pos.z(), STATE_PREFIX + left));
        }
    }

    // ===== External triggers =====

    /** Block-change funnel (World.setBlockAt, authoritative worlds only). */
    public void onBlockChanged(int x, int y, int z, BlockType previous, BlockType next) {
        if (isSapling(next)) {
            int ticks = MIN_GROW_TICKS + random.nextInt(GROW_TICK_RANGE);
            remaining.put(new BlockPos(x, y, z), ticks);
            world.setState(x, y, z, STATE_PREFIX + ticks);
        } else if (isSapling(previous)) {
            remaining.remove(new BlockPos(x, y, z));
        }
    }

    /** Resumes saplings saved with this chunk. */
    public void onChunkLoaded(Chunk chunk) {
        int baseX = chunk.getChunkX() * CHUNK_SIZE;
        int baseZ = chunk.getChunkZ() * CHUNK_SIZE;
        for (Map.Entry<Integer, String> e : chunk.getBlockStates().entrySet()) {
            int ticks = parseRemaining(e.getValue());
            if (ticks < 0) {
                continue;
            }
            int key = e.getKey();
            int lx = LocalBlockKey.x(key);
            int ly = LocalBlockKey.y(key);
            int lz = LocalBlockKey.z(key);
            if (isSapling(chunk.getBlock(lx, ly, lz))) {
                remaining.put(new BlockPos(baseX + lx, ly, baseZ + lz), ticks);
            }
        }
    }

    public void onChunkUnloaded(Chunk chunk) {
        int chunkX = chunk.getChunkX();
        int chunkZ = chunk.getChunkZ();
        remaining.entrySet().removeIf(e -> {
            BlockPos p = e.getKey();
            if (Math.floorDiv(p.x(), CHUNK_SIZE) != chunkX || Math.floorDiv(p.z(), CHUNK_SIZE) != chunkZ) {
                return false;
            }
            // Straight onto the chunk: it may already be out of the world's store.
            chunk.setBlockState(Math.floorMod(p.x(), CHUNK_SIZE), p.y(), Math.floorMod(p.z(), CHUNK_SIZE),
                STATE_PREFIX + e.getValue());
            return true;
        });
    }

    static int parseRemaining(String state) {
        if (state == null || !state.startsWith(STATE_PREFIX)) {
            return -1;
        }
        try {
            return Math.max(0, Integer.parseInt(state.substring(STATE_PREFIX.length())));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ===== Growth =====

    private boolean tryGrow(BlockPos pos) {
        int x = pos.x();
        int y = pos.y();
        int z = pos.z();
        if (!isSapling(world.getBlock(x, y, z))) {
            return true; // gone without a funnel notice — just stop tracking it
        }
        if (!isSoil(world.getBlock(x, y - 1, z))
                || y + CypressTree.MAX_HEIGHT >= WorldConfiguration.WORLD_HEIGHT
                || !world.isAreaLoaded(x, z, CypressTree.LEAF_RADIUS)) {
            return false;
        }
        for (int dy = 1; dy <= REQUIRED_HEADROOM; dy++) {
            if (!isReplaceable(world.getBlock(x, y + dy, z))) {
                return false;
            }
        }
        CypressTree.place((bx, by, bz, type) -> {
            if (isReplaceable(world.getBlock(bx, by, bz))) {
                world.setBlock(bx, by, bz, type);
            }
        }, x, y, z);
        return true;
    }

    /** A growing tree may only fill space that nothing else holds — never a player's build. */
    static boolean isReplaceable(BlockType block) {
        return block == BlockType.AIR || block == BlockType.WATER || block.isLeaves()
            || isSapling(block) || FlowBlockInteraction.isFragile(block);
    }
}
