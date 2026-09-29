package com.stonebreak.blocks.workbench;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.chunk.utils.LocalBlockKey;
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

    private static final int CHUNK_SIZE = 16;

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
     * in place, so an open workbench UI sees the change live. Client-side only.
     */
    public void applyAuthoritativeState(BlockPos pos, String stateString) {
        getOrCreate(pos).applyStateString(stateString);
    }

    /**
     * Applies a player's slot snapshot onto the authoritative grid and persists it (the
     * change listener echoes it to every client). Server-side; the caller validates reach
     * and that the block is still a workbench.
     */
    public void applySlots(World world, BlockPos pos, String encodedSlots) {
        WorkbenchState s = getOrCreate(pos);
        s.applySlots(encodedSlots);
        writeChunkState(world, pos, s.toStateString());
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
    public void onBlockBroken(World world, int x, int y, int z, Vector3f breaker) {
        WorkbenchState state = states.remove(new BlockPos(x, y, z));
        if (state == null) return;
        state.dropContentsAt(world, new Vector3f(x + 0.5f, y + 0.5f, z + 0.5f), breaker);
        // Chunk.setBlock(AIR) already cleared the per-block state map entry.
    }

    public void onChunkLoaded(Chunk chunk) {
        int cx = chunk.getX();
        int cz = chunk.getZ();
        for (Map.Entry<Integer, String> e : chunk.getBlockStates().entrySet()) {
            String value = e.getValue();
            if (!WorkbenchState.isWorkbenchState(value)) continue;

            int key = e.getKey();
            BlockPos pos = new BlockPos(
                cx * CHUNK_SIZE + LocalBlockKey.x(key),
                LocalBlockKey.y(key),
                cz * CHUNK_SIZE + LocalBlockKey.z(key));
            // In place when already tracked (a client re-stream of this chunk) so an open UI
            // stays bound to the same grid; treated like an echo so the UI re-baselines.
            states.compute(pos, (p, existing) -> {
                if (existing == null) return WorkbenchState.fromStateString(p, value);
                existing.applyStateString(value);
                return existing;
            });
        }
    }

    public void onChunkUnloaded(Chunk chunk) {
        int xMin = chunk.getX() * CHUNK_SIZE;
        int zMin = chunk.getZ() * CHUNK_SIZE;
        int xMax = xMin + CHUNK_SIZE;
        int zMax = zMin + CHUNK_SIZE;

        // Flush every grid inside this chunk back into the chunk's state map (the server
        // already wrote each change through; this also covers states created without a
        // write, e.g. a UI that was opened but never edited), then drop the entries.
        Iterator<Map.Entry<BlockPos, WorkbenchState>> it = states.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, WorkbenchState> entry = it.next();
            BlockPos p = entry.getKey();
            if (p.x() < xMin || p.x() >= xMax || p.z() < zMin || p.z() >= zMax) continue;

            WorkbenchState s = entry.getValue();
            int lx = Math.floorMod(p.x(), CHUNK_SIZE);
            int lz = Math.floorMod(p.z(), CHUNK_SIZE);
            String existing = chunk.getBlockState(lx, p.y(), lz);
            // Never write a grid over a cell that no longer holds a table's state (broken
            // and replaced while tracked) — only refresh our own entry or record a new one.
            if (existing == null || WorkbenchState.isWorkbenchState(existing)) {
                if (!s.isEmpty() || existing != null) {
                    chunk.setBlockState(lx, p.y(), lz, s.toStateString());
                }
            }
            it.remove();
        }
    }

    /* ── Helpers ─────────────────────────────────────────────── */

    private void writeChunkState(World world, BlockPos pos, String stateString) {
        if (world == null || stateString == null) return;
        Chunk chunk = world.getChunkIfLoaded(Math.floorDiv(pos.x(), CHUNK_SIZE), Math.floorDiv(pos.z(), CHUNK_SIZE));
        if (chunk == null) return;
        int lx = Math.floorMod(pos.x(), CHUNK_SIZE);
        int lz = Math.floorMod(pos.z(), CHUNK_SIZE);
        String previous = chunk.getBlockState(lx, pos.y(), lz);
        chunk.setBlockState(lx, pos.y(), lz, stateString);
        if (!stateString.equals(previous)) {
            StateChangeListener l = stateChangeListener;
            if (l != null) {
                l.onWorkbenchStateChanged(pos, stateString);
            }
        }
    }
}
