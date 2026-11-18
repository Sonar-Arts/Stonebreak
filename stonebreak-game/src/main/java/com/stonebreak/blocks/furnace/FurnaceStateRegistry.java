package com.stonebreak.blocks.furnace;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.crafting.SmeltingManager;
import com.openmason.engine.util.BlockPos;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Cube;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * World-scoped registry of {@link FurnaceState} objects, one per placed
 * furnace block. Owns the per-tick smelting loop and the chunk-load/unload
 * round-trip through the world's block states (cube payload).
 */
public class FurnaceStateRegistry {

    private static final Logger logger = LoggerFactory.getLogger(FurnaceStateRegistry.class);


    // Concurrent: the authoritative sim (tick / chunk load+unload) runs on the server thread
    // while the furnace UI + block place/break run on the main thread.
    private final Map<BlockPos, FurnaceState> states = new ConcurrentHashMap<>();
    private final SmeltingManager smeltingManager;

    /**
     * Fired whenever a furnace's persisted state STRING actually changes (contents, timers,
     * lit flip). The integrated server installs this on the authoritative world's registry to
     * broadcast {@code BlockStateS2C}; null elsewhere. Deduped, so an idle furnace fires
     * nothing, and a cooking one's progress is rate-limited ({@link FurnaceState#echoDue}).
     */
    @FunctionalInterface
    public interface StateChangeListener {
        void onFurnaceStateChanged(BlockPos pos, String stateString);
    }

    private volatile StateChangeListener stateChangeListener;

    public void setStateChangeListener(StateChangeListener listener) {
        this.stateChangeListener = listener;
    }

    public FurnaceStateRegistry(SmeltingManager smeltingManager) {
        this.smeltingManager = smeltingManager;
    }

    /**
     * Applies an authoritative server state (from {@code BlockStateS2C}) onto the LOCAL
     * registry entry in place — same object identity, so an open furnace UI bound to this
     * state sees contents/progress update live. {@code ack} is the echo's acknowledgement of
     * our own slot intents ({@link FurnaceState#applyStateString(String, int)}). Client-side only.
     */
    public void applyAuthoritativeState(BlockPos pos, String stateString, int ack) {
        getOrCreate(pos).applyStateString(stateString, ack);
    }

    /**
     * Persists the CURRENT state of {@code pos} into its chunk at once (the change listener
     * echoes it), rather than at the next tick: a container action mutates the state in place
     * (issue #334), and the echo queued for the actor's ack must carry the state that
     * includes it (issue #353). Server-side.
     */
    public void persist(World world, BlockPos pos) {
        FurnaceState s = states.get(pos);
        if (s != null) {
            String fresh = s.toStateString();
            if (writeChunkState(world, pos, fresh)) {
                announce(s, fresh);
            }
        }
    }

    /** Look up the furnace at {@code pos}, or {@code null} if none. */
    public FurnaceState get(BlockPos pos) {
        return states.get(pos);
    }

    /** All tracked furnace positions (live concurrent view). */
    public Set<BlockPos> positions() {
        return states.keySet();
    }

    /** Look up the furnace at {@code pos}, creating an empty state if missing. */
    public FurnaceState getOrCreate(BlockPos pos) {
        return states.computeIfAbsent(pos, FurnaceState::new);
    }

    /* ── Lifecycle hooks ─────────────────────────────────────── */

    public void onBlockPlaced(World world, int x, int y, int z, BlockType type) {
        if (type != BlockType.FURNACE) return;
        BlockPos pos = new BlockPos(x, y, z);
        FurnaceState s = states.computeIfAbsent(pos, FurnaceState::new);
        // Write the initial Unlit state so the chunk persists this position.
        // World.setBlockAt already scheduled a remesh for the FURNACE placement,
        // so no additional schedule is needed here — the freshly-built mesh
        // will pick up the Unlit state on its first run.
        String fresh = s.toStateString();
        if (writeChunkState(world, pos, fresh)) {
            announce(s, fresh);
        }
    }

    public void onBlockBroken(World world, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        FurnaceState state = states.remove(pos);
        if (state == null) return;
        org.joml.Vector3d dropPos = new org.joml.Vector3d(x + 0.5, y + 0.5, z + 0.5);
        state.dropContentsAt(world, dropPos);
        // Chunk.setBlock(AIR) already clears the per-block state map entry.
    }

    /** Discovers the furnaces of a cube that entered the world (their state strings carry the contents). */
    public void onCubeInstalled(Cube cube) {
        cube.forEachState((x, y, z, value) -> {
            if (value == null || !value.startsWith(FurnaceState.STATE_PREFIX)) return;
            BlockPos pos = new BlockPos(Math.toIntExact(x), Math.toIntExact(y), Math.toIntExact(z));
            // In place when already tracked (a client re-stream of this cube, #369) so an open
            // furnace UI stays bound to the same object and re-reads it, like an echo.
            FurnaceState s = states.compute(pos, (p, existing) -> {
                if (existing == null) return FurnaceState.fromStateString(p, value);
                existing.applyStateString(value);
                return existing;
            });
            s.markEchoed(value); // clients get this value with the cube itself
        });
    }

    /** Writes every tracked furnace of the cube back into the world (before its blob is saved). */
    public void flushToWorld(Cube cube) {
        World world = cube.world();
        for (Map.Entry<BlockPos, FurnaceState> entry : states.entrySet()) {
            BlockPos p = entry.getKey();
            if (cube.contains(p.x(), p.y(), p.z())) {
                writeChunkState(world, p, entry.getValue().toStateString());
            }
        }
    }

    /** The cube leaves next tick: flush its furnaces into the world so the blob carries them. */
    public void onCubeUnloadPending(Cube cube) {
        flushToWorld(cube);
    }

    /** The cube left: drop its in-memory furnaces. */
    public void onCubeUnloaded(Cube cube) {
        states.keySet().removeIf(p -> cube.contains(p.x(), p.y(), p.z()));
    }

    /* ── Tick loop ───────────────────────────────────────────── */

    /** Ticks every loaded furnace by {@code dtSeconds}. */
    public void tick(World world, float dtSeconds) {
        if (states.isEmpty() || smeltingManager == null) return;

        for (FurnaceState s : states.values()) {
            boolean litFlipped = s.tick(smeltingManager, dtSeconds);
            // Persist the current state to the chunk every tick so the autosave captures cook
            // progress + contents. This matters now that the authoritative (headless) server
            // never unloads chunks — the old onChunkUnloaded-only flush would never fire, losing
            // furnace contents. setBlockState dedups (only re-dirties on an actual change), so an
            // idle furnace costs nothing. A lit↔unlit flip additionally triggers a remesh.
            // Clients hear about it per echoDue: burn/cook move every tick while cooking, and
            // echoing each one sent 20 packets/s per furnace to every connection.
            String fresh = s.toStateString();
            writeChunkState(world, s.getPos(), fresh);
            if (s.echoDue(fresh)) {
                announce(s, fresh);
            }
            if (litFlipped) {
                world.scheduleChunkRemeshAt(s.getPos().x(), s.getPos().y(), s.getPos().z());
            }
        }
    }

    /* ── Helpers ─────────────────────────────────────────────── */

    /** Writes the state into the world; true when it changed (false if unchanged or unloaded). */
    private boolean writeChunkState(World world, BlockPos pos, String stateString) {
        if (world == null || stateString == null) return false;
        if (!world.isLoaded(pos.x(), pos.y(), pos.z())) return false;
        String previous = world.getBlockStateAt(pos.x(), pos.y(), pos.z());
        if (stateString.equals(previous)) return false;
        world.setBlockStateAt(pos.x(), pos.y(), pos.z(), stateString);
        return true;
    }

    private void announce(FurnaceState s, String stateString) {
        s.markEchoed(stateString);
        StateChangeListener l = stateChangeListener;
        if (l != null) {
            l.onFurnaceStateChanged(s.getPos(), stateString);
        }
    }

}
