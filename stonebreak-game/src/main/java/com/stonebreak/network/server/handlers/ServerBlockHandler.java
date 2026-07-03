package com.stonebreak.network.server.handlers;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.network.packet.world.BlockChangeC2S;
import com.stonebreak.network.packet.world.BlockChangeS2C;
import com.stonebreak.network.packet.world.MultiBlockChangeS2C;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;
import org.joml.Vector3f;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Authoritative block-edit handler — successor of the old {@code BlockSynchronizer} HOST
 * path. Validates inbound client edits (world bounds, known block id, reach distance from
 * the sender's cached position), applies accepted edits to the authoritative world via the
 * <b>non-broadcasting</b> {@code setBlockAt(..., false)} path, spawns drops for breaks, and
 * batches outgoing changes per tick into {@link BlockChangeS2C} / {@link MultiBlockChangeS2C}.
 *
 * <p>Drops spawned for a break enter the world's {@code EntityManager}; their network
 * broadcast is handled by {@link ServerEntityHandler} via the entity-add listener — there is
 * no separate drop broadcast here.
 */
public final class ServerBlockHandler {

    private static final float MAX_REACH = 8.0f;
    private static final float MAX_REACH_SQ = MAX_REACH * MAX_REACH;

    private final ServerChunkHandler chunkHandler;

    /** Outbound edits buffered this tick, grouped by section key {@code (sx, sy, sz)}.
     *  Insertion-ordered so wire order matches edit order. */
    private final Map<Long, SectionBatch> pendingByTick = new LinkedHashMap<>();

    /** Ticks an accepted edit may wait for its server chunk to load before being reverted. */
    private static final int RETRY_TTL_TICKS = 20; // 1 s at 20 Hz

    /** Validated edits whose target chunk wasn't loaded server-side yet: the chunk load has
     *  been kicked off and the edit retries each tick until it applies or its TTL expires
     *  (then the originator gets a corrective revert). Without this, an edit racing the
     *  server's chunk load was silently dropped while the originator kept it locally —
     *  a permanent one-block divergence. */
    private final java.util.ArrayDeque<PendingEdit> retryQueue = new java.util.ArrayDeque<>();

    private static final class PendingEdit {
        final ServerPlayer sp;
        final BlockChangeC2S change;
        int ticksLeft = RETRY_TTL_TICKS;
        PendingEdit(ServerPlayer sp, BlockChangeC2S change) { this.sp = sp; this.change = change; }
    }

    /** Snow-layer changes buffered this tick, per section — flushed as {@code BlockMetaS2C}. */
    private final Map<Long, SectionBatch> pendingSnowByTick = new LinkedHashMap<>();

    /** Block-state echoes (door placement facing) queued behind this tick's block batches
     *  so a state packet can never overtake — and be wiped by — its own block change. */
    private final java.util.List<com.stonebreak.network.packet.world.BlockStateS2C> pendingStateEchoes =
            new java.util.ArrayList<>();

    public ServerBlockHandler(ServerChunkHandler chunkHandler) {
        this.chunkHandler = chunkHandler;
    }

    public void onSessionEnd() {
        pendingByTick.clear();
        pendingSnowByTick.clear();
        pendingStateEchoes.clear();
        retryQueue.clear();
    }

    // ─── Inbound (client edit) ───────────────────────────────────────────────────

    public void handleBlockChange(ServerPlayer sp, BlockChangeC2S c, ServerWorldContext ctx) {
        World world = ctx.world();
        if (world == null) {
            return;
        }
        if (!validateClientEdit(sp, c)) {
            sendRevert(sp, c.x(), c.y(), c.z(), ctx);
            return;
        }
        BlockType incoming = BlockType.getById(c.blockTypeId() & 0xFFFF);
        // Use the CLIENT-reported prev as the source of truth for "what the player broke" —
        // the server's own world snapshot can lag under burst load (esp. for fast non-host
        // edits arriving between server ticks), causing the server's getBlockAt to read the
        // post-edit AIR and emit no drop. Cross-check the server's snapshot only as a sanity
        // guard for cheating: if the client claims a block we'd never accept, fall back to
        // the server's view.
        BlockType clientPrev = BlockType.getById(c.prevBlockTypeId() & 0xFFFF);
        BlockType serverPrev = world.getBlockAt(c.x(), c.y(), c.z());
        BlockType prev = (clientPrev != null && clientPrev != BlockType.AIR) ? clientPrev : serverPrev;
        boolean applied = applyToWorld(world, c.x(), c.y(), c.z(), c.blockTypeId());
        if (!applied) {
            // Server chunk not loaded yet (the reach check bounds this to a near-player chunk,
            // so it's a load race, not garbage). Kick off the load and retry for a bounded
            // window; expiry reverts the originator instead of silently diverging.
            world.getChunkAt(Math.floorDiv(c.x(), 16), Math.floorDiv(c.z(), 16));
            retryQueue.add(new PendingEdit(sp, c));
            return;
        }
        applyAccepted(sp, c, incoming, prev, world, ctx);
    }

    /** Post-apply effects of an accepted edit: break drops/cleanup, version bump, broadcast. */
    private void applyAccepted(ServerPlayer sp, BlockChangeC2S c, BlockType incoming, BlockType prev,
                               World world, ServerWorldContext ctx) {
        // Break (non-air → air): spawn drops authoritatively and run per-break cleanup. The
        // EntityManager listener broadcasts the resulting drop entities (see ServerEntityHandler).
        if (prev != null && prev != BlockType.AIR && incoming == BlockType.AIR) {
            if (prev == BlockType.FURNACE && world.getFurnaceRegistry() != null) {
                // The SERVER world's registry (per-world) — drops contents authoritatively.
                world.getFurnaceRegistry().onBlockBroken(world, c.x(), c.y(), c.z());
            }
            Vector3f dropPos = new Vector3f(c.x() + 0.5f, c.y() + 0.5f, c.z() + 0.5f);
            com.stonebreak.util.DropUtil.handleBlockBroken(world, dropPos, prev);
        }
        // Furnace placement: register the authoritative state (Unlit, empty). The client's
        // own BlockPlacer only touched ITS display registry.
        if (incoming == BlockType.FURNACE && world.getFurnaceRegistry() != null) {
            world.getFurnaceRegistry().onBlockPlaced(world, c.x(), c.y(), c.z(), incoming);
        }
        // Door placement: initialize the authoritative state (closed, panel on the placer's
        // edge) and echo it so every client — the placer included — agrees on the facing.
        // The echo is QUEUED, not broadcast immediately: block changes flush in per-tick
        // batches, and a state packet overtaking its block change would be wiped when the
        // late-arriving block apply clears the cell's state entry.
        if (incoming == BlockType.OAK_DOOR) {
            com.stonebreak.blocks.door.DoorState placed =
                    com.stonebreak.blocks.door.DoorState.placed(sp.x(), sp.z(), c.x(), c.z());
            world.setBlockStateAt(c.x(), c.y(), c.z(), placed.toStateString());
            pendingStateEchoes.add(new com.stonebreak.network.packet.world.BlockStateS2C(
                    c.x(), c.y(), c.z(), placed.toStateString()));
        }
        // Snow layer bookkeeping derived from the block change (the SnowLayerC2S intent only
        // covers layer increments on an EXISTING snow block): a placed SNOW block starts at
        // 1 layer; a broken one drops its tracking. The manager's mutation listener then
        // dirties the chunk and queues the BlockMetaS2C broadcast.
        if (world.getSnowLayerManager() != null) {
            if (incoming == BlockType.SNOW) {
                world.getSnowLayerManager().setSnowLayers(c.x(), c.y(), c.z(), 1);
            } else if (prev == BlockType.SNOW && incoming == BlockType.AIR) {
                world.getSnowLayerManager().removeSnowLayers(c.x(), c.y(), c.z());
            }
        }
        chunkHandler.markChunkModified(Math.floorDiv(c.x(), 16), Math.floorDiv(c.z(), 16));
        queueOutgoing(c.x(), c.y(), c.z(), c.blockTypeId());
    }

    /**
     * C2S: a player claims a snow-layer change at a position (the increment-on-existing-snow
     * case that carries no block change). Validates like a block edit, then applies to the
     * authoritative SnowLayerManager — its mutation listener dirties the chunk for save and
     * feeds {@link #onServerSnowChange} for the broadcast (echoed to the originator too, so
     * an over-optimistic client self-corrects).
     */
    public void handleSnowLayer(ServerPlayer sp, com.stonebreak.network.packet.world.SnowLayerC2S s,
                                ServerWorldContext ctx) {
        World world = ctx.world();
        if (world == null || world.getSnowLayerManager() == null) {
            return;
        }
        int layers = s.layers();
        if (layers < 0 || layers > 8 || s.y() < 0 || s.y() >= WorldConfiguration.WORLD_HEIGHT) {
            return;
        }
        if (sp.lastStateNs() != 0L) {
            float dx = (s.x() + 0.5f) - sp.x();
            float dy = (s.y() + 0.5f) - sp.y();
            float dz = (s.z() + 0.5f) - sp.z();
            if (dx * dx + dy * dy + dz * dz > MAX_REACH_SQ) {
                return;
            }
        }
        if (layers == 0) {
            world.getSnowLayerManager().removeSnowLayers(s.x(), s.y(), s.z());
        } else if (world.getBlockAt(s.x(), s.y(), s.z()) == BlockType.SNOW) {
            world.getSnowLayerManager().setSnowLayers(s.x(), s.y(), s.z(), layers);
        }
    }

    /**
     * C2S: the player edited the slots of an open furnace UI. Validates (reach, furnace
     * exists on the server world), then applies SLOTS ONLY onto the authoritative
     * {@code FurnaceState} — burn/cook timers stay server-owned. The registry's next tick
     * writes the new state string, whose change listener broadcasts the {@code BlockStateS2C}
     * echo that corrects everyone (originator included).
     */
    public void handleFurnaceSlots(ServerPlayer sp, com.stonebreak.network.packet.world.FurnaceSlotsC2S f,
                                   ServerWorldContext ctx) {
        World world = ctx.world();
        if (world == null || world.getFurnaceRegistry() == null) {
            return;
        }
        if (f.y() < 0 || f.y() >= WorldConfiguration.WORLD_HEIGHT) {
            return;
        }
        if (sp.lastStateNs() != 0L) {
            float dx = (f.x() + 0.5f) - sp.x();
            float dy = (f.y() + 0.5f) - sp.y();
            float dz = (f.z() + 0.5f) - sp.z();
            if (dx * dx + dy * dy + dz * dz > MAX_REACH_SQ) {
                return;
            }
        }
        if (world.getBlockAt(f.x(), f.y(), f.z()) != BlockType.FURNACE) {
            return;
        }
        world.getFurnaceRegistry()
            .getOrCreate(new com.openmason.engine.util.BlockPos(f.x(), f.y(), f.z()))
            .applySlots(f.slots());
    }

    /**
     * C2S: the player right-clicked a toggleable block (door). Validates (reach, target is
     * a door on the server world), flips the authoritative door state, and echoes the
     * result to ALL clients — originator included — as {@link com.stonebreak.network.packet.world.BlockStateS2C}.
     * Clients play the new state's animation clip when they observe the flip.
     */
    public void handleBlockToggle(ServerPlayer sp, com.stonebreak.network.packet.world.BlockToggleC2S t,
                                  ServerWorldContext ctx) {
        World world = ctx.world();
        if (world == null) {
            return;
        }
        if (t.y() < 0 || t.y() >= WorldConfiguration.WORLD_HEIGHT) {
            return;
        }
        if (sp.lastStateNs() != 0L) {
            float dx = (t.x() + 0.5f) - sp.x();
            float dy = (t.y() + 0.5f) - sp.y();
            float dz = (t.z() + 0.5f) - sp.z();
            if (dx * dx + dy * dy + dz * dz > MAX_REACH_SQ) {
                return;
            }
        }
        if (world.getBlockAt(t.x(), t.y(), t.z()) != BlockType.OAK_DOOR) {
            return;
        }
        com.stonebreak.blocks.door.DoorState next = com.stonebreak.blocks.door.DoorState
                .parse(world.getBlockStateAt(t.x(), t.y(), t.z()))
                .toggled();
        world.setBlockStateAt(t.x(), t.y(), t.z(), next.toStateString());
        ctx.broadcast(new com.stonebreak.network.packet.world.BlockStateS2C(
                t.x(), t.y(), t.z(), next.toStateString()), false);
    }

    /**
     * Authoritative snow mutation ({@code layers == 0} = removed) from the server world's
     * SnowLayerManager listener. Batched per section and flushed each tick as
     * {@link com.stonebreak.network.packet.world.BlockMetaS2C} (KIND_SNOW_LAYERS).
     */
    public void onServerSnowChange(int x, int y, int z, int layers) {
        if (y < 0 || y >= WorldConfiguration.WORLD_HEIGHT) {
            return;
        }
        int sx = Math.floorDiv(x, 16);
        int sy = Math.floorDiv(y, 16);
        int sz = Math.floorDiv(z, 16);
        long key = sectionKey(sx, sy, sz);
        SectionBatch batch = pendingSnowByTick.computeIfAbsent(key, k -> new SectionBatch(sx, sy, sz));
        batch.add(x - sx * 16, y - sy * 16, z - sz * 16, (short) layers);
    }

    /** Retry edits that were waiting on a server chunk load; revert the originator on expiry. */
    private void drainRetries(ServerWorldContext ctx) {
        if (retryQueue.isEmpty()) {
            return;
        }
        World world = ctx.world();
        if (world == null) {
            retryQueue.clear();
            return;
        }
        int n = retryQueue.size();
        for (int i = 0; i < n; i++) {
            PendingEdit pe = retryQueue.poll();
            if (pe == null) {
                break;
            }
            BlockChangeC2S c = pe.change;
            BlockType clientPrev = BlockType.getById(c.prevBlockTypeId() & 0xFFFF);
            BlockType serverPrev = world.getBlockAt(c.x(), c.y(), c.z()); // before apply
            if (applyToWorld(world, c.x(), c.y(), c.z(), c.blockTypeId())) {
                BlockType incoming = BlockType.getById(c.blockTypeId() & 0xFFFF);
                BlockType prev = (clientPrev != null && clientPrev != BlockType.AIR)
                    ? clientPrev : serverPrev;
                applyAccepted(pe.sp, c, incoming, prev, world, ctx);
            } else if (--pe.ticksLeft <= 0) {
                sendRevert(pe.sp, c.x(), c.y(), c.z(), ctx);
            } else {
                retryQueue.add(pe); // still loading — try again next tick
            }
        }
    }

    /** Host-originated edit (wired from {@code World.setBlockAt} in the lifecycle phase). */
    public void onLocalBlockChange(int x, int y, int z, BlockType type, ServerWorldContext ctx) {
        short id = (short) (type == null ? 0 : type.getId());
        chunkHandler.markChunkModified(Math.floorDiv(x, WorldConfiguration.CHUNK_SIZE),
            Math.floorDiv(z, WorldConfiguration.CHUNK_SIZE));
        queueOutgoing(x, y, z, id);
    }

    /** Sim edits accepted per tick before falling back to whole-chunk re-streams. Flowing
     *  water peaks well under this; the cap only bites on pathological floods. */
    private static final int MAX_SIM_EDITS_PER_TICK = 2048;
    private int simEditsThisTick = 0;

    /**
     * Authoritative SIMULATION mutation (water flow etc., via the {@code World} mutation
     * callback on the server tick thread). Rides the same per-section batches as player
     * edits ({@code MultiBlockChangeS2C}) but does NOT bump the chunk version — a version
     * bump would re-stream the whole chunk to every viewer continuously under flowing water.
     * Clients that don't hold the chunk drop the apply harmlessly and receive a fresh
     * snapshot (encoded from live state at send time) when it enters view.
     *
     * <p>Overflow guardrail: past {@link #MAX_SIM_EDITS_PER_TICK} queued sim edits in one
     * tick, fall back to marking the chunk modified (one full re-stream self-heals it) and
     * skip per-block queuing for the rest of the tick.
     */
    public void onServerBlockChange(int x, int y, int z, BlockType type) {
        if (y < 0 || y >= WorldConfiguration.WORLD_HEIGHT) {
            return;
        }
        if (simEditsThisTick >= MAX_SIM_EDITS_PER_TICK) {
            int cx = Math.floorDiv(x, WorldConfiguration.CHUNK_SIZE);
            int cz = Math.floorDiv(z, WorldConfiguration.CHUNK_SIZE);
            chunkHandler.markChunkModified(cx, cz);
            // Still a sim edit: record it for the audit's sim-edit grace window too.
            chunkHandler.invalidateHash(cx, cz);
            return;
        }
        simEditsThisTick++;
        // Sim edits change chunk contents without a version bump, so the audit hash cache
        // must be invalidated here (player edits invalidate via markChunkModified).
        chunkHandler.invalidateHash(Math.floorDiv(x, WorldConfiguration.CHUNK_SIZE),
            Math.floorDiv(z, WorldConfiguration.CHUNK_SIZE));
        queueOutgoing(x, y, z, (short) (type == null ? 0 : type.getId()));
    }

    // ─── Per-tick flush ──────────────────────────────────────────────────────────

    public void tick(ServerWorldContext ctx) {
        simEditsThisTick = 0;
        drainRetries(ctx);
        if (!pendingByTick.isEmpty()) {
            for (SectionBatch sb : pendingByTick.values()) {
                if (sb.size() == 1) {
                    int packed = sb.packed[0];
                    int lx = (packed >>> 24) & 0xF;
                    int ly = (packed >>> 20) & 0xF;
                    int lz = (packed >>> 16) & 0xF;
                    short blockId = (short) (packed & 0xFFFF);
                    ctx.broadcast(new BlockChangeS2C(sb.sx * 16 + lx, sb.sy * 16 + ly, sb.sz * 16 + lz, blockId), false);
                } else {
                    ctx.broadcast(new MultiBlockChangeS2C(sb.sx, sb.sy, sb.sz, sb.toPackedArray()), false);
                }
            }
            pendingByTick.clear();
        }
        if (!pendingSnowByTick.isEmpty()) {
            for (SectionBatch sb : pendingSnowByTick.values()) {
                ctx.broadcast(new com.stonebreak.network.packet.world.BlockMetaS2C(
                    sb.sx, sb.sy, sb.sz,
                    com.stonebreak.network.packet.world.BlockMetaS2C.KIND_SNOW_LAYERS,
                    sb.toPackedArray()), false);
            }
            pendingSnowByTick.clear();
        }
        // AFTER the block batches: state echoes for blocks placed this tick
        // (see pendingStateEchoes — order matters, block apply clears state).
        if (!pendingStateEchoes.isEmpty()) {
            for (com.stonebreak.network.packet.world.BlockStateS2C echo : pendingStateEchoes) {
                ctx.broadcast(echo, false);
            }
            pendingStateEchoes.clear();
        }
    }

    // ─── Outgoing batching ─────────────────────────────────────────────────────────

    private void queueOutgoing(int x, int y, int z, short blockId) {
        int sx = Math.floorDiv(x, 16);
        int sy = Math.floorDiv(y, 16);
        int sz = Math.floorDiv(z, 16);
        int lx = x - sx * 16;
        int ly = y - sy * 16;
        int lz = z - sz * 16;
        long key = sectionKey(sx, sy, sz);
        SectionBatch batch = pendingByTick.computeIfAbsent(key, k -> new SectionBatch(sx, sy, sz));
        batch.add(lx, ly, lz, blockId);
    }

    private static long sectionKey(int sx, int sy, int sz) {
        return ((long) (sx & 0xFFFFFF) << 36) | ((long) (sz & 0xFFFFFF) << 12) | (sy & 0xFFF);
    }

    // ─── Inbound application + validation ────────────────────────────────────────────

    private boolean applyToWorld(World world, int x, int y, int z, short blockTypeId) {
        BlockType type = BlockType.getById(blockTypeId & 0xFFFF);
        if (type == null) {
            type = BlockType.AIR;
        }
        // false = non-broadcasting: applies to the authoritative world without firing the
        // local-change hook (the server decides the rebroadcast via queueOutgoing).
        return world.setBlockAt(x, y, z, type, false);
    }

    private boolean validateClientEdit(ServerPlayer sp, BlockChangeC2S c) {
        if (c.y() < 0 || c.y() >= WorldConfiguration.WORLD_HEIGHT) {
            return false;
        }
        if (BlockType.getById(c.blockTypeId() & 0xFFFF) == null) {
            return false;
        }
        if (sp.lastStateNs() == 0L) {
            return true; // no position reported yet — accept conservatively
        }
        float dx = (c.x() + 0.5f) - sp.x();
        float dy = (c.y() + 0.5f) - sp.y();
        float dz = (c.z() + 0.5f) - sp.z();
        return (dx * dx + dy * dy + dz * dz) <= MAX_REACH_SQ;
    }

    private void sendRevert(ServerPlayer sp, int x, int y, int z, ServerWorldContext ctx) {
        World world = ctx.world();
        if (world == null || y < 0 || y >= WorldConfiguration.WORLD_HEIGHT) {
            return;
        }
        BlockType actual = world.getBlockAt(x, y, z);
        short id = (short) (actual == null ? 0 : actual.getId());
        sp.send(new BlockChangeS2C(x, y, z, id), false);
    }

    /** Mutable buffer for batched edits in one section (mirrors the legacy wire layout). */
    private static final class SectionBatch {
        final int sx, sy, sz;
        int[] packed = new int[8];
        int n = 0;

        SectionBatch(int sx, int sy, int sz) { this.sx = sx; this.sy = sy; this.sz = sz; }

        void add(int lx, int ly, int lz, short blockId) {
            int posBits = ((lx & 0xF) << 24) | ((ly & 0xF) << 20) | ((lz & 0xF) << 16);
            int v = posBits | (blockId & 0xFFFF);
            int posMask = 0x0FFF0000;
            for (int i = 0; i < n; i++) {
                if ((packed[i] & posMask) == (v & posMask)) {
                    packed[i] = v; // coalesce repeated edits to the same cell
                    return;
                }
            }
            if (n == packed.length) {
                int[] grown = new int[packed.length * 2];
                System.arraycopy(packed, 0, grown, 0, n);
                packed = grown;
            }
            packed[n++] = v;
        }

        int size() { return n; }

        /** Wire layout: {@code (localPos << 16) | blockId}, {@code localPos = (lx<<8)|(ly<<4)|lz}. */
        int[] toPackedArray() {
            int[] out = new int[n];
            for (int i = 0; i < n; i++) {
                int v = packed[i];
                int lx = (v >>> 24) & 0xF;
                int ly = (v >>> 20) & 0xF;
                int lz = (v >>> 16) & 0xF;
                int blockId = v & 0xFFFF;
                out[i] = (((lx << 8) | (ly << 4) | lz) << 16) | blockId;
            }
            return out;
        }
    }
}
