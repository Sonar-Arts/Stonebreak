package com.stonebreak.network.sync.synchronizers;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.network.protocol.Packet;
import com.stonebreak.network.server.IntegratedServer;
import com.stonebreak.network.server.RemoteClient;
import com.stonebreak.network.sync.SyncContext;
import com.stonebreak.network.sync.SyncEvent;
import com.stonebreak.network.sync.SyncMode;
import com.stonebreak.network.sync.Synchronizer;
import com.stonebreak.world.operations.WorldConfiguration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mirrors block place/break events between host and clients.
 *
 * <p>Outgoing edits are batched per tick: changes that fall in the same 16×16×16
 * section of the same chunk are coalesced into a {@link Packet.MultiBlockChangeS2C}
 * (clusters from explosions, fluid spread, fill tools), while solitary edits ship
 * as a single {@link Packet.BlockChangeS2C}. Inbound packets are applied via
 * {@code World.setBlockAt} (the SyncService's applyingInbound flag prevents the
 * resulting hook from feeding back into the network).
 *
 * <p>Inbound {@code BlockChangeC2S} from clients is validated server-side: world
 * bounds, known block id, and reach distance from the sender's last reported
 * position. Invalid edits are rejected and the originator is sent a corrective
 * S2C reverting to the actual block.
 */
public final class BlockSynchronizer implements Synchronizer {

    /** Maximum allowed reach (squared) from a player's last-known position. */
    private static final float MAX_REACH = 8.0f;
    private static final float MAX_REACH_SQ = MAX_REACH * MAX_REACH;

    /**
     * Notified when the host applies a client-originated edit so chunk-snapshot
     * tracking still sees the change. Required because {@code SyncService}'s
     * applyingInbound flag suppresses the normal {@code SyncEvent.BlockChanged}
     * fan-out for inbound packets (to break re-broadcast feedback loops),
     * which would otherwise also suppress chunk-modification tracking.
     */
    private final ChunkSynchronizer chunkSync;

    public BlockSynchronizer(ChunkSynchronizer chunkSync) {
        this.chunkSync = chunkSync;
    }

    /**
     * Buffered outbound block changes for this tick, grouped by section key
     * {@code (cx, sy, cz)}. Iteration order is insertion-order so the wire order
     * matches the order edits actually happened — important for clients that
     * apply them sequentially (e.g. fluid that depends on neighbour state).
     */
    private final Map<Long, SectionBatch> pendingByTick = new LinkedHashMap<>();

    @Override
    public boolean handlesInbound(Packet packet) {
        return packet instanceof Packet.BlockChangeC2S
                || packet instanceof Packet.BlockChangeS2C
                || packet instanceof Packet.MultiBlockChangeS2C;
    }

    @Override
    public void applyInbound(Packet packet, Integer originId, SyncContext ctx) {
        switch (packet) {
            case Packet.BlockChangeC2S c -> {
                if (!validateClientEdit(c, originId)) {
                    sendRevert(c.x(), c.y(), c.z(), originId, ctx);
                    return;
                }
                applyToWorld(c.x(), c.y(), c.z(), c.blockTypeId());
                // Track for the chunk-snapshot path: SyncService.notifyLocal
                // is suppressed during inbound application, so ChunkSynchronizer's
                // own emitLocal won't fire — call it directly here instead.
                chunkSync.markChunkModified(
                        Math.floorDiv(c.x(), 16),
                        Math.floorDiv(c.z(), 16));
                // Queue for batched re-broadcast on tick (includes originator
                // so they get an authoritative echo).
                queueOutgoing(c.x(), c.y(), c.z(), c.blockTypeId());
            }
            case Packet.BlockChangeS2C s -> applyToWorld(s.x(), s.y(), s.z(), s.blockTypeId());
            case Packet.MultiBlockChangeS2C m -> applyMultiBlock(m);
            default -> {}
        }
    }

    @Override
    public boolean handlesLocal(SyncEvent event) {
        return event instanceof SyncEvent.BlockChanged;
    }

    @Override
    public void emitLocal(SyncEvent event, SyncContext ctx) {
        if (!(event instanceof SyncEvent.BlockChanged b)) return;
        // Online-only; in offline mode notifyLocal is a no-op anyway.
        if (ctx.mode() == SyncMode.OFFLINE) return;
        short id = (short) (b.type() == null ? 0 : b.type().getId());
        queueOutgoing(b.x(), b.y(), b.z(), id);
    }

    @Override
    public void tick(float deltaTime, SyncContext ctx) {
        if (pendingByTick.isEmpty()) return;
        for (Map.Entry<Long, SectionBatch> e : pendingByTick.entrySet()) {
            SectionBatch sb = e.getValue();
            if (sb.size() == 1) {
                // Single edit — ship as BlockChange{C2S|S2C}; no batching overhead.
                int packed = sb.packed[0];
                int lx = (packed >>> 24) & 0xF;
                int ly = (packed >>> 20) & 0xF;
                int lz = (packed >>> 16) & 0xF;
                short blockId = (short) (packed & 0xFFFF);
                int wx = sb.sx * 16 + lx;
                int wy = sb.sy * 16 + ly;
                int wz = sb.sz * 16 + lz;
                if (ctx.mode() == SyncMode.HOST) {
                    ctx.broadcast(new Packet.BlockChangeS2C(wx, wy, wz, blockId));
                } else {
                    ctx.broadcast(new Packet.BlockChangeC2S(wx, wy, wz, blockId));
                }
            } else if (ctx.mode() == SyncMode.HOST) {
                int[] packed = sb.toPackedArray();
                ctx.broadcast(new Packet.MultiBlockChangeS2C(sb.sx, sb.sy, sb.sz, packed));
            } else {
                // Client → server doesn't speak MultiBlockChange; fall back to per-block C2S.
                for (int i = 0; i < sb.size(); i++) {
                    int packed = sb.packed[i];
                    int lx = (packed >>> 24) & 0xF;
                    int ly = (packed >>> 20) & 0xF;
                    int lz = (packed >>> 16) & 0xF;
                    short blockId = (short) (packed & 0xFFFF);
                    ctx.broadcast(new Packet.BlockChangeC2S(
                            sb.sx * 16 + lx, sb.sy * 16 + ly, sb.sz * 16 + lz, blockId));
                }
            }
        }
        pendingByTick.clear();
    }

    @Override
    public void onSessionEnd() {
        pendingByTick.clear();
    }

    // ─── Outgoing batching ──────────────────────────────────────────────────

    private void queueOutgoing(int x, int y, int z, short blockId) {
        int sx = Math.floorDiv(x, 16);
        int sy = Math.floorDiv(y, 16);
        int sz = Math.floorDiv(z, 16);
        int lx = x - sx * 16;
        int ly = y - sy * 16;
        int lz = z - sz * 16;
        long key = sectionKey(sx, sy, sz);
        SectionBatch batch = pendingByTick.get(key);
        if (batch == null) {
            batch = new SectionBatch(sx, sy, sz);
            pendingByTick.put(key, batch);
        }
        batch.add(lx, ly, lz, blockId);
    }

    private static long sectionKey(int sx, int sy, int sz) {
        // sx/sz can span ~ ±2^24 chunks (way past plausible world size), sy is 0..15.
        return ((long) (sx & 0xFFFFFF) << 36)
                | ((long) (sz & 0xFFFFFF) << 12)
                | (sy & 0xFFF);
    }

    /** Mutable buffer for batched edits in one section. */
    private static final class SectionBatch {
        final int sx, sy, sz;
        int[] packed = new int[8];
        int n = 0;

        SectionBatch(int sx, int sy, int sz) { this.sx = sx; this.sy = sy; this.sz = sz; }

        void add(int lx, int ly, int lz, short blockId) {
            // Coalesce: if the same local position is edited again, overwrite
            // the prior entry rather than ship a stale-then-fresh pair.
            int posBits = ((lx & 0xF) << 24) | ((ly & 0xF) << 20) | ((lz & 0xF) << 16);
            int v = posBits | (blockId & 0xFFFF);
            // Bits 16-27 hold the local position (lx<<24 | ly<<20 | lz<<16).
            int posMask = 0x0FFF0000;
            for (int i = 0; i < n; i++) {
                if ((packed[i] & posMask) == (v & posMask)) {
                    packed[i] = v;
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

        /**
         * Build a packed entry array using the wire layout
         * {@code (localPos << 16) | blockId} where {@code localPos = (lx<<8)|(ly<<4)|lz}.
         */
        int[] toPackedArray() {
            int[] out = new int[n];
            for (int i = 0; i < n; i++) {
                int v = packed[i];
                int lx = (v >>> 24) & 0xF;
                int ly = (v >>> 20) & 0xF;
                int lz = (v >>> 16) & 0xF;
                int blockId = v & 0xFFFF;
                int localPos = (lx << 8) | (ly << 4) | lz;
                out[i] = (localPos << 16) | blockId;
            }
            return out;
        }
    }

    // ─── Inbound application ────────────────────────────────────────────────

    private void applyMultiBlock(Packet.MultiBlockChangeS2C m) {
        int baseX = m.sectionX() * 16;
        int baseY = m.sectionY() * 16;
        int baseZ = m.sectionZ() * 16;
        for (int v : m.packed()) {
            int localPos = (v >>> 16) & 0xFFFF;
            int lx = (localPos >> 8) & 0xF;
            int ly = (localPos >> 4) & 0xF;
            int lz = localPos & 0xF;
            short blockId = (short) (v & 0xFFFF);
            applyToWorld(baseX + lx, baseY + ly, baseZ + lz, blockId);
        }
    }

    // ─── Validation ─────────────────────────────────────────────────────────

    private boolean validateClientEdit(Packet.BlockChangeC2S c, Integer originId) {
        if (c.y() < 0 || c.y() >= WorldConfiguration.WORLD_HEIGHT) return false;
        if (BlockType.getById(c.blockTypeId() & 0xFFFF) == null) return false;
        if (originId == null) return true;
        IntegratedServer srv = MultiplayerSession.getServer();
        if (srv == null) return false;
        RemoteClient rc = srv.getClient(originId);
        if (rc == null) return false;
        if (rc.getLastStateNs() == 0L) return true; // no position yet — accept conservatively
        float dx = (c.x() + 0.5f) - rc.getX();
        float dy = (c.y() + 0.5f) - rc.getY();
        float dz = (c.z() + 0.5f) - rc.getZ();
        return (dx * dx + dy * dy + dz * dz) <= MAX_REACH_SQ;
    }

    private void sendRevert(int x, int y, int z, Integer originId, SyncContext ctx) {
        if (originId == null || Game.getWorld() == null) return;
        if (y < 0 || y >= WorldConfiguration.WORLD_HEIGHT) return;
        BlockType actual = Game.getWorld().getBlockAt(x, y, z);
        short id = (short) (actual == null ? 0 : actual.getId());
        ctx.sendTo(originId, new Packet.BlockChangeS2C(x, y, z, id));
    }

    private void applyToWorld(int x, int y, int z, short blockTypeId) {
        if (Game.getWorld() == null) return;
        BlockType type = BlockType.getById(blockTypeId & 0xFFFF);
        if (type == null) type = BlockType.AIR;
        Game.getWorld().setBlockAt(x, y, z, type, true);
    }
}
