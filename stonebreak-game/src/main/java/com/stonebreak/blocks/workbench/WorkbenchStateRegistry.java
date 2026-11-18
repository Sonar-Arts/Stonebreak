package com.stonebreak.blocks.workbench;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Cube;
import org.joml.Vector3f;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * World-scoped registry of {@link WorkbenchState}s, one per crafting table that has (or had)
 * items in its grid. Mirrors {@code FurnaceStateRegistry}: PER-WORLD — the authoritative
 * server world's registry owns the real grids and writes every change straight into the
 * chunk's block states (so the autosave captures it); each client render world holds a
 * display copy hydrated from streamed chunk states and live {@code BlockStateS2C} echoes.
 *
 * <p>Unlike the furnace there is no tick: the grid only changes when a player edits it
 * ({@link #applySlots}) or the block is placed/broken.
 */
public class WorkbenchStateRegistry {


    // Concurrent: slot intents + chunk load/unload run on the server thread while the
    // workbench UI and block placement run on the main thread.
    private final Map<BlockPos, WorkbenchState> states = new ConcurrentHashMap<>();

    /**
     * Fired whenever a workbench's persisted state string actually changes. The integrated
     * server installs this on the authoritative world's registry to broadcast
     * {@code BlockStateS2C}; null elsewhere.
     */
    @FunctionalInterface
    public interface StateChangeListener {
        void onWorkbenchStateChanged(BlockPos pos, String stateString);
    }

    private volatile StateChangeListener stateChangeListener;

    public void setStateChangeListener(StateChangeListener listener) {
        this.stateChangeListener = listener;
    }

    /** Look up the workbench at {@code pos}, or {@code null} if none is tracked. */
    public WorkbenchState get(BlockPos pos) {
        return states.get(pos);
    }

    /** Look up the workbench at {@code pos}, creating an empty grid if missing. */
    public WorkbenchState getOrCreate(BlockPos pos) {
        return states.computeIfAbsent(pos, WorkbenchState::new);
    }

    /**
     * Applies an authoritative server state (from {@code BlockStateS2C}) onto the LOCAL entry
     * in place, so an open workbench UI sees the change live. {@code ack} is the echo's
     * acknowledgement of our own slot intents ({@link WorkbenchState#applyStateString(String, int)}).
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
        WorkbenchState s = states.get(pos);
        if (s != null) {
            writeChunkState(world, pos, s.toStateString());
        }
    }

    /* ── Lifecycle hooks ─────────────────────────────────────── */

    /**
     * A crafting table was placed: start it with an empty grid. REPLACES any stale entry left
     * at this position by a previous table (a client display registry never sees the break
     * itself), and — on the server — echoes the empty grid so every client resets too.
     */
    public void onBlockPlaced(World world, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        WorkbenchState fresh = new WorkbenchState(pos);
        WorkbenchState previous = states.putIfAbsent(pos, fresh);
        WorkbenchState s = previous != null ? previous : fresh;
        if (previous != null) {
            s.loadStateString(null); // keep identity: an open UI may still be bound to it
        }
        writeChunkState(world, pos, s.toStateString());
    }

    /**
     * The crafting table was broken: drop its grid at the block. Server-side (authoritative);
     * {@code breaker} biases where the drops spawn and may be null.
     */
    public void onBlockBroken(World world, int x, int y, int z, org.joml.Vector3dc breaker) {
        WorkbenchState state = states.remove(new BlockPos(x, y, z));
        if (state == null) return;
        state.dropContentsAt(world, new org.joml.Vector3d(x + 0.5, y + 0.5, z + 0.5), breaker);
        // Chunk.setBlock(AIR) already cleared the per-block state map entry.
    }

    /** Discovers the crafting-table grids of a cube that entered the world. */
    public void onCubeInstalled(Cube cube) {
        cube.forEachState((x, y, z, value) -> {
            if (!WorkbenchState.isWorkbenchState(value)) return;
            BlockPos pos = new BlockPos(Math.toIntExact(x), Math.toIntExact(y), Math.toIntExact(z));
            // In place when already tracked (a client re-stream) so an open UI stays bound to the same grid.
            states.compute(pos, (p, existing) -> {
                if (existing == null) return WorkbenchState.fromStateString(p, value);
                existing.applyStateString(value);
                return existing;
            });
        });
    }

    /** Writes every tracked grid of the cube back into the world (before its blob is saved). */
    public void flushToWorld(Cube cube) {
        World world = cube.world();
        for (Map.Entry<BlockPos, WorkbenchState> entry : states.entrySet()) {
            BlockPos p = entry.getKey();
            if (!cube.contains(p.x(), p.y(), p.z())) continue;
            WorkbenchState s = entry.getValue();
            String existing = world.getBlockStateAt(p.x(), p.y(), p.z());
            // Never write a grid over a cell that no longer holds a table's state.
            if (existing == null || WorkbenchState.isWorkbenchState(existing)) {
                if (!s.isEmpty() || existing != null) {
                    String fresh = s.toStateString();
                    if (!fresh.equals(existing)) {
                        world.setBlockStateAt(p.x(), p.y(), p.z(), fresh);
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
        }
        if (!stateString.equals(previous)) {
            StateChangeListener l = stateChangeListener;
            if (l != null) {
                l.onWorkbenchStateChanged(pos, stateString);
            }
        }
    }
}
