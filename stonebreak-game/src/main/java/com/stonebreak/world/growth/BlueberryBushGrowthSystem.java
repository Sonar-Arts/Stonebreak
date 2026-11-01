package com.stonebreak.world.growth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.blueberrybush.BlueberryBushState;
import com.stonebreak.blocks.blueberrybush.BlueberryBushState.Stage;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.chunk.utils.LocalBlockKey;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Moves placed blueberry bushes through their {@link Stage}s: four growth stages to a mature
 * bush, then three berry stages to ripe. A ripe bush waits until it is picked
 * ({@link #harvest}), which sets it back to Mature to fruit again. The bush is never broken by
 * picking.
 *
 * <p>Timers work like {@link SaplingGrowthSystem}'s: they only count down while the bush's chunk
 * is loaded, and they live in the block-state string so they save with the chunk and resume on
 * load. The stage in that string selects the SBO state model, so each stage change remeshes the
 * block and is announced to the {@link StageListener} for replication.
 *
 * <p>Runs on authoritative worlds only, at 20 TPS.
 */
public final class BlueberryBushGrowthSystem {

    /** Bush growth stages (Sprout, Young, Bushy): 2–4 minutes each at 20 TPS. */
    static final int MIN_GROW_TICKS = 2 * 60 * 20;
    static final int GROW_TICK_RANGE = 2 * 60 * 20;
    /** Berry stages (Mature, Unripe, Ripening): 2–3 minutes each, so 6–9 minutes to ripe. */
    static final int MIN_FRUIT_TICKS = 2 * 60 * 20;
    static final int FRUIT_TICK_RANGE = 60 * 20;
    /** How often remaining time is written back to the block state for saving. */
    private static final int PERSIST_INTERVAL_TICKS = 20;
    private static final int MAX_TICKS_PER_FRAME = 2;
    private static final float TICK_INTERVAL = 1.0f / 20.0f;
    private static final int CHUNK_SIZE = WorldConfiguration.CHUNK_SIZE;

    /** Told when a bush's stage (and so its model) changes. Called on the tick thread. */
    @FunctionalInterface
    public interface StageListener {
        void onStageChanged(int x, int y, int z);
    }

    private final SaplingWorld world;
    private final Random random;
    /** Bushes with a running timer. Ripe bushes are not tracked: they only change on harvest. */
    private final Map<BlockPos, BlueberryBushState> tracked = new HashMap<>();
    private volatile StageListener stageListener;
    private float tickAccumulator;
    private long logicalTick;

    public BlueberryBushGrowthSystem(SaplingWorld world, Random random) {
        this.world = Objects.requireNonNull(world, "world");
        this.random = Objects.requireNonNull(random, "random");
    }

    public void setStageListener(StageListener listener) {
        this.stageListener = listener;
    }

    public static boolean isBush(BlockType block) {
        return block == BlockType.BLUEBERRY_BUSH;
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
        return tracked.size();
    }

    private void step() {
        if (tracked.isEmpty()) {
            return;
        }
        List<BlockPos> due = new ArrayList<>();
        for (Map.Entry<BlockPos, BlueberryBushState> e : tracked.entrySet()) {
            BlueberryBushState s = e.getValue();
            BlueberryBushState counted = new BlueberryBushState(s.stage(), s.ticksLeft() - 1);
            e.setValue(counted);
            if (counted.ticksLeft() <= 0) {
                due.add(e.getKey());
            }
        }
        for (BlockPos pos : due) {
            advance(pos);
        }
        if (logicalTick % PERSIST_INTERVAL_TICKS == 0) {
            tracked.forEach((pos, s) -> world.setState(pos.x(), pos.y(), pos.z(), s.toStateString()));
        }
    }

    private void advance(BlockPos pos) {
        BlueberryBushState current = tracked.get(pos);
        if (current == null) {
            return;
        }
        if (!isBush(world.getBlock(pos.x(), pos.y(), pos.z()))) {
            tracked.remove(pos); // gone without a funnel notice — stop tracking it
            return;
        }
        enter(pos, current.stage().next());
    }

    /** Writes {@code stage} with a fresh timer, tracks or untracks it, and announces it. */
    private void enter(BlockPos pos, Stage stage) {
        BlueberryBushState next = new BlueberryBushState(stage, timerFor(stage));
        if (next.isRipe()) {
            tracked.remove(pos);
        } else {
            tracked.put(pos, next);
        }
        world.setState(pos.x(), pos.y(), pos.z(), next.toStateString());
        StageListener l = stageListener;
        if (l != null) {
            l.onStageChanged(pos.x(), pos.y(), pos.z());
        }
    }

    private int timerFor(Stage stage) {
        return timerFor(stage, random.nextFloat());
    }

    /**
     * Time a bush spends in {@code stage}, picked by {@code roll} in [0, 1). Public so world
     * generation can give wild bushes timers from its deterministic rolls.
     */
    public static int timerFor(Stage stage, float roll) {
        if (stage.next() == null) {
            return BlueberryBushState.NO_TIMER;
        }
        float r = Math.min(Math.max(roll, 0f), 0.999999f);
        return stage.isGrowing()
            ? MIN_GROW_TICKS + (int) (r * GROW_TICK_RANGE)
            : MIN_FRUIT_TICKS + (int) (r * FRUIT_TICK_RANGE);
    }

    // ===== Harvest =====

    /**
     * Picks a ripe bush: it goes back to Mature and starts fruiting again. Returns {@code false},
     * changing nothing, when the block is not a ripe blueberry bush. Spawning the berries is
     * the caller's job ({@code BlueberryBushHarvest}).
     */
    public boolean harvest(int x, int y, int z) {
        if (!isBush(world.getBlock(x, y, z))) {
            return false;
        }
        BlueberryBushState state = BlueberryBushState.parse(world.getState(x, y, z));
        if (state == null || !state.isRipe()) {
            return false;
        }
        enter(new BlockPos(x, y, z), Stage.MATURE);
        return true;
    }

    // ===== External triggers =====

    /** Block-change funnel (World.setBlockAt, authoritative worlds only). */
    public void onBlockChanged(int x, int y, int z, BlockType previous, BlockType next) {
        if (isBush(next)) {
            enter(new BlockPos(x, y, z), Stage.SPROUT);
        } else if (isBush(previous)) {
            tracked.remove(new BlockPos(x, y, z));
        }
    }

    /** Resumes bushes saved with this chunk. */
    public void onChunkLoaded(Chunk chunk) {
        int baseX = chunk.getChunkX() * CHUNK_SIZE;
        int baseZ = chunk.getChunkZ() * CHUNK_SIZE;
        for (Map.Entry<Integer, String> e : chunk.getBlockStates().entrySet()) {
            BlueberryBushState state = BlueberryBushState.parse(e.getValue());
            if (state == null || state.isRipe()) {
                continue;
            }
            int key = e.getKey();
            int lx = LocalBlockKey.x(key);
            int ly = LocalBlockKey.y(key);
            int lz = LocalBlockKey.z(key);
            if (isBush(chunk.getBlock(lx, ly, lz))) {
                tracked.put(new BlockPos(baseX + lx, ly, baseZ + lz), state);
            }
        }
    }

    public void onChunkUnloaded(Chunk chunk) {
        int chunkX = chunk.getChunkX();
        int chunkZ = chunk.getChunkZ();
        tracked.entrySet().removeIf(e -> {
            BlockPos p = e.getKey();
            if (Math.floorDiv(p.x(), CHUNK_SIZE) != chunkX || Math.floorDiv(p.z(), CHUNK_SIZE) != chunkZ) {
                return false;
            }
            // Straight onto the chunk: it may already be out of the world's store.
            chunk.setBlockState(Math.floorMod(p.x(), CHUNK_SIZE), p.y(), Math.floorMod(p.z(), CHUNK_SIZE),
                e.getValue().toStateString());
            return true;
        });
    }
}
