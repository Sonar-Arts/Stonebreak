package com.stonebreak.blocks.furnace;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.crafting.SmeltingManager;
import com.openmason.engine.util.BlockPos;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.chunk.utils.LocalBlockKey;
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
 * round-trip through {@link Chunk#getBlockStates()}.
 */
public class FurnaceStateRegistry {

    private static final Logger logger = LoggerFactory.getLogger(FurnaceStateRegistry.class);

    private static final int CHUNK_SIZE = 16;

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
        Vector3f dropPos = new Vector3f(x + 0.5f, y + 0.5f, z + 0.5f);
        state.dropContentsAt(world, dropPos);
        // Chunk.setBlock(AIR) already clears the per-block state map entry.
    }

    public void onChunkLoaded(Chunk chunk) {
        int cx = chunk.getX();
        int cz = chunk.getZ();
        for (Map.Entry<Integer, String> e : chunk.getBlockStates().entrySet()) {
            String value = e.getValue();
            if (value == null || !value.startsWith(FurnaceState.STATE_PREFIX)) continue;

            int key = e.getKey();
            BlockPos pos = new BlockPos(
                cx * CHUNK_SIZE + LocalBlockKey.x(key),
                LocalBlockKey.y(key),
                cz * CHUNK_SIZE + LocalBlockKey.z(key));
            // In place when already tracked (a client re-stream of this chunk, #369) so an open
            // furnace UI stays bound to the same object and re-reads it, like an echo. A
            // re-stream carries no ack, so its slots are ignored while one of our snapshots is
            // in flight; the timers always apply.
            FurnaceState s = states.compute(pos, (p, existing) -> {
                if (existing == null) return FurnaceState.fromStateString(p, value);
                existing.applyStateString(value);
                return existing;
            });
            s.markEchoed(value); // clients get this value with the chunk itself
        }
    }

    public void onChunkUnloaded(Chunk chunk) {
        int cx = chunk.getX();
        int cz = chunk.getZ();
        int xMin = cx * CHUNK_SIZE;
        int zMin = cz * CHUNK_SIZE;
        int xMax = xMin + CHUNK_SIZE;
        int zMax = zMin + CHUNK_SIZE;

        // Flush every in-memory state inside this chunk back into the chunk's
        // state map, then drop the in-memory entries so the GC can reclaim them.
        Iterator<Map.Entry<BlockPos, FurnaceState>> it = states.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, FurnaceState> entry = it.next();
            BlockPos p = entry.getKey();
            if (p.x() < xMin || p.x() >= xMax || p.z() < zMin || p.z() >= zMax) continue;

            int lx = Math.floorMod(p.x(), CHUNK_SIZE);
            int lz = Math.floorMod(p.z(), CHUNK_SIZE);
            String fresh = entry.getValue().toStateString();
            chunk.setBlockState(lx, p.y(), lz, fresh);
            it.remove();
        }
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

    /** Writes the state into its chunk; true when it changed (false if unchanged or unloaded). */
    private boolean writeChunkState(World world, BlockPos pos, String stateString) {
        if (world == null || stateString == null) return false;
        int cx = Math.floorDiv(pos.x(), CHUNK_SIZE);
        int cz = Math.floorDiv(pos.z(), CHUNK_SIZE);
        Chunk chunk = world.getChunkIfLoaded(cx, cz);
        if (chunk == null) return false;
        int lx = Math.floorMod(pos.x(), CHUNK_SIZE);
        int lz = Math.floorMod(pos.z(), CHUNK_SIZE);
        String previous = chunk.getBlockStates()
            .get(LocalBlockKey.pack(lx, pos.y(), lz));
        chunk.setBlockState(lx, pos.y(), lz, stateString);
        return !stateString.equals(previous);
    }

    private void announce(FurnaceState s, String stateString) {
        s.markEchoed(stateString);
        StateChangeListener l = stateChangeListener;
        if (l != null) {
            l.onFurnaceStateChanged(s.getPos(), stateString);
        }
    }

}
