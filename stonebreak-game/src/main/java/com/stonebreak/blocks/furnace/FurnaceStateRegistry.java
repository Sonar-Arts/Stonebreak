package com.stonebreak.blocks.furnace;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.crafting.SmeltingManager;
import com.stonebreak.util.BlockPos;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Map;
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

    public FurnaceStateRegistry(SmeltingManager smeltingManager) {
        this.smeltingManager = smeltingManager;
    }

    /** Look up the furnace at {@code pos}, or {@code null} if none. */
    public FurnaceState get(BlockPos pos) {
        return states.get(pos);
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
        writeChunkState(world, pos, s.toStateString());
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
        for (Map.Entry<String, String> e : chunk.getBlockStates().entrySet()) {
            String value = e.getValue();
            if (value == null || !value.startsWith(FurnaceState.STATE_PREFIX)) continue;

            BlockPos pos = parseStateKey(cx, cz, e.getKey());
            if (pos == null) continue;
            FurnaceState s = FurnaceState.fromStateString(pos, value);
            states.put(pos, s);
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
            writeChunkState(world, s.getPos(), s.toStateString());
            if (litFlipped) {
                world.scheduleChunkRemeshAt(s.getPos().x(), s.getPos().y(), s.getPos().z());
            }
        }
    }

    /* ── Helpers ─────────────────────────────────────────────── */

    private void writeChunkState(World world, BlockPos pos, String stateString) {
        if (world == null || stateString == null) return;
        int cx = Math.floorDiv(pos.x(), CHUNK_SIZE);
        int cz = Math.floorDiv(pos.z(), CHUNK_SIZE);
        Chunk chunk = world.getChunkIfLoaded(cx, cz);
        if (chunk == null) return;
        int lx = Math.floorMod(pos.x(), CHUNK_SIZE);
        int lz = Math.floorMod(pos.z(), CHUNK_SIZE);
        chunk.setBlockState(lx, pos.y(), lz, stateString);
    }

    private static BlockPos parseStateKey(int chunkX, int chunkZ, String key) {
        try {
            String[] parts = key.split(",");
            if (parts.length != 3) return null;
            int lx = Integer.parseInt(parts[0]);
            int y  = Integer.parseInt(parts[1]);
            int lz = Integer.parseInt(parts[2]);
            return new BlockPos(chunkX * CHUNK_SIZE + lx, y, chunkZ * CHUNK_SIZE + lz);
        } catch (Exception e) {
            logger.debug("Bad block-state key {}", key);
            return null;
        }
    }
}
