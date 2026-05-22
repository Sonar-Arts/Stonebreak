package com.stonebreak.blocks.furnace;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.crafting.SmeltingManager;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.network.sync.SyncEvent;
import com.stonebreak.network.sync.SyncService;
import com.stonebreak.util.BlockPos;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * World-scoped registry of {@link FurnaceState} objects, one per placed
 * furnace block. Owns the per-tick smelting loop and the chunk-load/unload
 * round-trip through {@link Chunk#getBlockStates()}.
 */
public class FurnaceStateRegistry {

    private static final Logger logger = LoggerFactory.getLogger(FurnaceStateRegistry.class);

    private static final int CHUNK_SIZE = 16;

    /** Throttle for periodic network state pushes of actively-cooking furnaces. */
    private static final float NET_PUSH_INTERVAL = 0.25f;

    private final Map<BlockPos, FurnaceState> states = new HashMap<>();
    private final SmeltingManager smeltingManager;
    private float netPushAccum;

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
        // Write the initial Unlit state so the chunk persists this position, and
        // (host) replicate it so clients create their own shadow furnace state.
        // World.setBlockAt already scheduled a remesh for the FURNACE placement,
        // so no additional schedule is needed here — the freshly-built mesh
        // will pick up the Unlit state on its first run.
        broadcastState(world, pos);
    }

    public void onBlockBroken(World world, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        FurnaceState state = states.remove(pos);
        if (state == null) return;
        Vector3f dropPos = new Vector3f(x + 0.5f, y + 0.5f, z + 0.5f);
        state.dropContentsAt(world, dropPos);
        // Chunk.setBlock(AIR) already clears the per-block state map entry.
        // Tell clients to drop their shadow furnace state for this position.
        fireStateChanged(pos, "");
    }

    /* ── Network replication ─────────────────────────────────── */

    /**
     * Writes the furnace's current state to its chunk and (host only) replicates
     * it to clients via {@link SyncEvent.BlockStateChanged}. The single funnel
     * for "this furnace changed" — call after any host-side mutation.
     */
    public void broadcastState(World world, BlockPos pos) {
        FurnaceState s = states.get(pos);
        if (s == null) return;
        String str = s.toStateString();
        writeChunkState(world, pos, str);
        fireStateChanged(pos, str);
    }

    /** Client-side: install authoritative furnace state received from the host. */
    public void applyNetworkState(BlockPos pos, String stateString) {
        if (stateString == null || stateString.isBlank()) { states.remove(pos); return; }
        getOrCreate(pos).applyStateString(stateString);
    }

    /** Client-side: drop a furnace whose block was removed on the host. */
    public void removeAt(BlockPos pos) {
        states.remove(pos);
    }

    private void fireStateChanged(BlockPos pos, String str) {
        if (!MultiplayerSession.isHosting()) return;
        SyncService sync = MultiplayerSession.getSyncService();
        // Forced: furnace state changes are often emitted while the host is
        // applying an inbound client edit (place/break/slot), where the normal
        // notifyLocal is suppressed. There is no inbound BlockStateChanged on
        // the host, so this cannot create a re-broadcast loop.
        if (sync != null) sync.notifyLocalForced(new SyncEvent.BlockStateChanged(pos.x(), pos.y(), pos.z(), str));
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
        // Clients never simulate smelting — furnaces are host-authoritative and
        // driven entirely by inbound BlockStateS2C. Ticking here would diverge
        // (double-consume fuel, phantom output) from the host.
        if (MultiplayerSession.isClient()) return;
        if (states.isEmpty() || smeltingManager == null) return;

        // Periodically push actively-cooking furnaces so clients see the cook /
        // fuel bars advance and output appear, not just lit↔unlit flips.
        netPushAccum += dtSeconds;
        boolean periodic = false;
        if (netPushAccum >= NET_PUSH_INTERVAL) { netPushAccum = 0f; periodic = true; }
        boolean hosting = MultiplayerSession.isHosting();

        for (FurnaceState s : states.values()) {
            boolean litFlipped = s.tick(smeltingManager, dtSeconds);
            // Push to chunk on a visual change (lit↔unlit). Cook progress and
            // ingredient/output stacks are persisted by onChunkUnloaded before
            // save — no need to mark the chunk dirty every single tick.
            if (litFlipped) {
                broadcastState(world, s.getPos());
                world.scheduleChunkRemeshAt(s.getPos().x(), s.getPos().y(), s.getPos().z());
            } else if (hosting && periodic && (s.isCooking() || s.isLit())) {
                broadcastState(world, s.getPos());
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
