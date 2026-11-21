package com.stonebreak.world.growth;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

import com.openmason.engine.voxel.world.CubePos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.blueberrybush.BlueberryBushState;
import com.stonebreak.blocks.blueberrybush.BlueberryBushState.Stage;
import com.stonebreak.world.chunk.Cube;

/**
 * Moves placed blueberry bushes through their {@link Stage}s: four growth stages to a mature
 * bush, then three berry stages to ripe. A ripe bush waits until it is picked
 * ({@link #harvest}), which sets it back to Mature to fruit again. The bush is never broken by
 * picking.
 *
 * <p>Timers work like {@link SaplingGrowthSystem}'s: they only count down while the bush's cube
 * is loaded. The STAGE lives in the block-state string (it selects the SBO state model, so each
 * stage change remeshes the block and is announced to the {@link StageListener} for replication);
 * the remaining TIME lives in the cube's blob section {@value #BLOB_SECTION} (same codec as the
 * saplings') — in the cubic world block states are ids in a per-world table, and a countdown in
 * the state string would register a new id every second (#447). A bush whose cube has no timer
 * for it (a wild bush, an older save) starts a fresh one for its stage.
 *
 * <p>Runs on authoritative worlds only, at 20 TPS.
 */
public final class BlueberryBushGrowthSystem {

    /** Blob section holding a cube's bush timers. */
    public static final String BLOB_SECTION = "blueberries";
    /** Bush growth stages (Sprout, Young, Bushy): 2–4 minutes each at 20 TPS. */
    static final int MIN_GROW_TICKS = 2 * 60 * 20;
    static final int GROW_TICK_RANGE = 2 * 60 * 20;
    /** Berry stages (Mature, Unripe, Ripening): 2–3 minutes each, so 6–9 minutes to ripe. */
    static final int MIN_FRUIT_TICKS = 2 * 60 * 20;
    static final int FRUIT_TICK_RANGE = 60 * 20;
    /** How often remaining time is written back to the cube for saving. */
    static final int PERSIST_INTERVAL_TICKS = 20;
    private static final int MAX_TICKS_PER_FRAME = 2;
    private static final float TICK_INTERVAL = 1.0f / 20.0f;

    /** Told when a bush's stage (and so its model) changes. Called on the tick thread. */
    @FunctionalInterface
    public interface StageListener {
        void onStageChanged(long x, long y, long z);
    }

    private final SaplingWorld world;
    private final Random random;
    /** Bushes with a running timer. Ripe bushes are not tracked: they only change on harvest. */
    private final Map<SaplingGrowthSystem.Pos, BlueberryBushState> tracked = new LinkedHashMap<>();
    private volatile StageListener stageListener;
    private float tickAccumulator;
    private long logicalTick;

    public BlueberryBushGrowthSystem(SaplingWorld world, Random random) {
        this.world = Objects.requireNonNull(world, "world");
        this.random = Objects.requireNonNull(random, "random");
        world.attach(this);
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

    /** Remaining ticks of the bush at a position, or -1 when none is tracked there. */
    public int remainingAt(long x, long y, long z) {
        BlueberryBushState s = tracked.get(new SaplingGrowthSystem.Pos(x, y, z));
        return s == null ? -1 : s.ticksLeft();
    }

    private void step() {
        if (tracked.isEmpty()) {
            return;
        }
        List<SaplingGrowthSystem.Pos> due = new ArrayList<>();
        for (Map.Entry<SaplingGrowthSystem.Pos, BlueberryBushState> e : tracked.entrySet()) {
            BlueberryBushState s = e.getValue();
            BlueberryBushState counted = new BlueberryBushState(s.stage(), s.ticksLeft() - 1);
            e.setValue(counted);
            if (counted.ticksLeft() <= 0) {
                due.add(e.getKey());
            }
        }
        Set<CubePos> touched = new HashSet<>();
        for (SaplingGrowthSystem.Pos pos : due) {
            touched.add(pos.cube());
            advance(pos);
        }
        if (logicalTick % PERSIST_INTERVAL_TICKS == 0) {
            for (SaplingGrowthSystem.Pos pos : tracked.keySet()) {
                touched.add(pos.cube());
            }
        }
        for (CubePos cube : touched) {
            persist(cube);
        }
    }

    private void advance(SaplingGrowthSystem.Pos pos) {
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
    private void enter(SaplingGrowthSystem.Pos pos, Stage stage) {
        BlueberryBushState next = new BlueberryBushState(stage, timerFor(stage));
        if (next.isRipe()) {
            tracked.remove(pos);
        } else {
            tracked.put(pos, next);
        }
        world.setState(pos.x(), pos.y(), pos.z(), next.toStageString());
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
    public boolean harvest(long x, long y, long z) {
        if (!isBush(world.getBlock(x, y, z))) {
            return false;
        }
        BlueberryBushState state = BlueberryBushState.parse(world.getState(x, y, z));
        if (state == null || !state.isRipe()) {
            return false;
        }
        SaplingGrowthSystem.Pos pos = new SaplingGrowthSystem.Pos(x, y, z);
        enter(pos, Stage.MATURE);
        persist(pos.cube());
        return true;
    }

    // ===== External triggers =====

    /** Block-change funnel (World.setBlockAt, authoritative worlds only). */
    public void onBlockChanged(long x, long y, long z, BlockType previous, BlockType next) {
        SaplingGrowthSystem.Pos pos = new SaplingGrowthSystem.Pos(x, y, z);
        if (isBush(next)) {
            enter(pos, Stage.SPROUT);
            persist(pos.cube());
        } else if (isBush(previous)) {
            if (tracked.remove(pos) != null) {
                persist(pos.cube());
            }
        }
    }

    /**
     * Resumes the bushes of a cube that became resident: every non-ripe bush state in it, with the
     * timer saved in the cube (a fresh one for its stage when none was saved — wild bushes).
     */
    public void onCubeInstalled(Cube cube) {
        Map<Integer, Integer> timers = SaplingGrowthSystem.decodeSection(
                world.readSection(cube.pos(), BLOB_SECTION));
        cube.forEachState((x, y, z, value) -> {
            BlueberryBushState state = BlueberryBushState.parse(value);
            if (state == null || state.isRipe() || !isBush(world.getBlock(x, y, z))) {
                return;
            }
            Integer saved = timers.get(Cube.cellOf(x, y, z));
            int ticks = saved != null ? saved : timerFor(state.stage());
            tracked.put(new SaplingGrowthSystem.Pos(x, y, z), new BlueberryBushState(state.stage(), ticks));
        });
    }

    /** The cube leaves next tick (or a save point runs): write its latest timers into it. */
    public void onCubeUnloadPending(CubePos cube) {
        persist(cube);
    }

    /** Stops tracking the bushes of a cube that left (their timers were written while pending). */
    public void onCubeUnloaded(Cube cube) {
        CubePos c = cube.pos();
        tracked.keySet().removeIf(p -> p.inCube(c));
    }

    private void persist(CubePos cube) {
        Map<Integer, Integer> cells = new LinkedHashMap<>();
        for (Map.Entry<SaplingGrowthSystem.Pos, BlueberryBushState> e : tracked.entrySet()) {
            SaplingGrowthSystem.Pos p = e.getKey();
            if (p.inCube(cube)) {
                cells.put(Cube.cellOf(p.x(), p.y(), p.z()), e.getValue().ticksLeft());
            }
        }
        world.writeSection(cube, BLOB_SECTION, SaplingGrowthSystem.encodeSection(cells));
    }
}
