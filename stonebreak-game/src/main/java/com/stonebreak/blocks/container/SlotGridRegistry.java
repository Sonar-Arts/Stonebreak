package com.stonebreak.blocks.container;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Cube;

import java.util.function.Function;
import java.util.function.Predicate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * World-scoped registry of one kind of {@link SlotGridState} (crafting tables, chests), one per
 * block that has (or had) items in it. PER-WORLD: the authoritative server world's registry owns
 * the real grids and writes every change straight into the world's block states (so the autosave
 * captures it); each client render world holds a display copy hydrated from streamed cube states
 * and live {@code BlockStateS2C} echoes.
 *
 * <p>There is no tick: a grid only changes when a player edits it ({@link #persist}) or the block
 * is placed/broken.
 */
public class SlotGridRegistry<S extends SlotGridState> {

    // Concurrent: slot intents + chunk load/unload run on the server thread while the
    // container UI and block placement run on the main thread.
    private final Map<BlockPos, S> states = new ConcurrentHashMap<>();
    private final Function<BlockPos, S> fresh;
    private final Predicate<String> ownsState;

    /**
     * @param fresh     an empty grid at a position
     * @param ownsState whether a raw block-state string is this kind's
     */
    public SlotGridRegistry(Function<BlockPos, S> fresh, Predicate<String> ownsState) {
        this.fresh = fresh;
        this.ownsState = ownsState;
    }

    /**
     * Fired whenever a grid's persisted state string actually changes. The integrated
     * server installs this on the authoritative world's registry to broadcast
     * {@code BlockStateS2C}; null elsewhere.
     */
    @FunctionalInterface
    public interface StateChangeListener {
        void onStateChanged(BlockPos pos, String stateString);
    }

    private volatile StateChangeListener stateChangeListener;

    public void setStateChangeListener(StateChangeListener listener) {
        this.stateChangeListener = listener;
    }

    /** Look up the grid at {@code pos}, or {@code null} if none is tracked. */
    public S get(BlockPos pos) {
        return states.get(pos);
    }

    /** Every tracked grid, live and read-only — for a renderer drawing what they hold. */
    public java.util.Collection<S> all() {
        return java.util.Collections.unmodifiableCollection(states.values());
    }

    /** Look up the grid at {@code pos}, creating an empty grid if missing. */
    public S getOrCreate(BlockPos pos) {
        return states.computeIfAbsent(pos, fresh);
    }

    /**
     * Applies an authoritative server state (from {@code BlockStateS2C}) onto the LOCAL entry
     * in place, so an open container UI sees the change live. {@code ack} is the echo's
     * acknowledgement of our own slot intents ({@link SlotGridState#applyStateString(String, int)}).
     * Client-side only.
     */
    public void applyAuthoritativeState(BlockPos pos, String stateString, int ack) {
        getOrCreate(pos).applyStateString(stateString, ack);
    }

    /**
     * Persists the CURRENT grid of {@code pos} into its chunk and echoes it if it changed
     * (issue #334): the atomic container actions mutate the state in place, so persistence +
     * echo is an explicit follow-up step instead of a side effect of a slot snapshot.
     */
    public void persist(World world, BlockPos pos) {
        S s = states.get(pos);
        if (s != null) {
            writeChunkState(world, pos, s.toStateString());
        }
    }

    /* ── Lifecycle hooks ─────────────────────────────────────── */

    /**
     * A grid block was placed: start it with an empty grid. REPLACES any stale entry left
     * at this position by a previous block (a client display registry never sees the break
     * itself), and — on the server — echoes the empty grid so every client resets too.
     */
    public void onBlockPlaced(World world, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        S created = fresh.apply(pos);
        S previous = states.putIfAbsent(pos, created);
        S s = previous != null ? previous : created;
        if (previous != null) {
            s.loadStateString(null); // keep identity: an open UI may still be bound to it
        }
        writeChunkState(world, pos, s.toStateString());
    }

    /**
     * The block was broken: drop its grid at the block. Server-side (authoritative);
     * {@code breaker} biases where the drops spawn and may be null.
     */
    public void onBlockBroken(World world, int x, int y, int z, org.joml.Vector3dc breaker) {
        S state = states.remove(new BlockPos(x, y, z));
        if (state == null) return;
        state.dropContentsAt(world, new org.joml.Vector3d(x + 0.5, y + 0.5, z + 0.5), breaker);
        // Setting the cell to AIR already cleared its block state.
    }

    /** Discovers this kind's grids in a cube that entered the world. */
    public void onCubeInstalled(Cube cube) {
        cube.forEachState((x, y, z, value) -> {
            if (!ownsState.test(value)) return;
            BlockPos pos = new BlockPos(Math.toIntExact(x), Math.toIntExact(y), Math.toIntExact(z));
            // In place when already tracked (a client re-stream) so an open UI stays bound to the
            // same grid; treated like an echo so the UI re-baselines. A re-stream carries no ack, so
            // it is ignored while one of our snapshots is in flight.
            states.compute(pos, (p, existing) -> {
                if (existing == null) {
                    S created = fresh.apply(p);
                    created.loadStateString(value);
                    return created;
                }
                existing.applyStateString(value);
                return existing;
            });
        });
    }

    /**
     * Writes every tracked grid of the cube back into the world (before its blob is saved): the
     * server already wrote each change through; this also covers grids created without a write,
     * e.g. a UI that was opened but never edited.
     */
    public void flushToWorld(Cube cube) {
        World world = cube.world();
        for (Map.Entry<BlockPos, S> entry : states.entrySet()) {
            BlockPos p = entry.getKey();
            if (!cube.contains(p.x(), p.y(), p.z())) continue;
            S s = entry.getValue();
            String existing = world.getBlockStateAt(p.x(), p.y(), p.z());
            // Never write a grid over a cell that no longer holds this kind of grid (broken and
            // replaced while tracked) — only refresh our own entry or record a new one.
            if (existing == null || ownsState.test(existing)) {
                if (!s.isEmpty() || existing != null) {
                    String current = s.toStateString();
                    if (!current.equals(existing)) {
                        world.setBlockStateAt(p.x(), p.y(), p.z(), current);
                    }
                }
            }
        }
    }

    public void onCubeUnloadPending(Cube cube) {
        flushToWorld(cube);
    }

    public void onCubeUnloaded(Cube cube) {
        states.keySet().removeIf(p -> cube.contains(p.x(), p.y(), p.z()));
    }

    /* ── Helpers ─────────────────────────────────────────────── */

    private void writeChunkState(World world, BlockPos pos, String stateString) {
        if (world == null || stateString == null) return;
        if (!world.isLoaded(pos.x(), pos.y(), pos.z())) return;
        String previous = world.getBlockStateAt(pos.x(), pos.y(), pos.z());
        if (!stateString.equals(previous)) {
            world.setBlockStateAt(pos.x(), pos.y(), pos.z(), stateString);
            StateChangeListener l = stateChangeListener;
            if (l != null) {
                l.onStateChanged(pos, stateString);
            }
        }
    }
}
