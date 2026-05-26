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

    public ServerBlockHandler(ServerChunkHandler chunkHandler) {
        this.chunkHandler = chunkHandler;
    }

    public void onSessionEnd() {
        pendingByTick.clear();
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
            // Host chunk not loaded — don't broadcast (originator applied locally; a
            // re-broadcast would worsen divergence with other clients).
            return;
        }
        // Break (non-air → air): spawn drops authoritatively and run per-break cleanup. The
        // EntityManager listener broadcasts the resulting drop entities (see ServerEntityHandler).
        if (prev != null && prev != BlockType.AIR && incoming == BlockType.AIR) {
            if (prev == BlockType.FURNACE) {
                com.stonebreak.blocks.furnace.FurnaceStateRegistry fr =
                    com.stonebreak.core.Game.getInstance().getFurnaceRegistry();
                if (fr != null) fr.onBlockBroken(world, c.x(), c.y(), c.z());
            }
            Vector3f dropPos = new Vector3f(c.x() + 0.5f, c.y() + 0.5f, c.z() + 0.5f);
            com.stonebreak.util.DropUtil.handleBlockBroken(world, dropPos, prev);
        }
        chunkHandler.markChunkModified(Math.floorDiv(c.x(), 16), Math.floorDiv(c.z(), 16));
        queueOutgoing(c.x(), c.y(), c.z(), c.blockTypeId());
    }

    /** Host-originated edit (wired from {@code World.setBlockAt} in the lifecycle phase). */
    public void onLocalBlockChange(int x, int y, int z, BlockType type, ServerWorldContext ctx) {
        short id = (short) (type == null ? 0 : type.getId());
        chunkHandler.markChunkModified(Math.floorDiv(x, WorldConfiguration.CHUNK_SIZE),
            Math.floorDiv(z, WorldConfiguration.CHUNK_SIZE));
        queueOutgoing(x, y, z, id);
    }

    // ─── Per-tick flush ──────────────────────────────────────────────────────────

    public void tick(ServerWorldContext ctx) {
        if (pendingByTick.isEmpty()) {
            return;
        }
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
