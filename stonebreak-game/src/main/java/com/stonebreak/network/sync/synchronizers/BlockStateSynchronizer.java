package com.stonebreak.network.sync.synchronizers;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.furnace.FurnaceState;
import com.stonebreak.blocks.furnace.FurnaceStateRegistry;
import com.stonebreak.core.Game;
import com.stonebreak.items.ItemStack;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.network.protocol.Packet;
import com.stonebreak.network.server.IntegratedServer;
import com.stonebreak.network.server.RemoteClient;
import com.stonebreak.network.sync.SyncContext;
import com.stonebreak.network.sync.SyncEvent;
import com.stonebreak.network.sync.SyncMode;
import com.stonebreak.network.sync.Synchronizer;
import com.stonebreak.util.BlockPos;
import com.stonebreak.world.World;

/**
 * Replicates block-entity state (currently furnaces) between host and clients.
 *
 * <ul>
 *   <li><b>Host → clients:</b> a {@link SyncEvent.BlockStateChanged} (emitted by
 *       {@link FurnaceStateRegistry} whenever a furnace's contents/lit-state
 *       change) is broadcast as a {@link Packet.BlockStateS2C}. Blank state means
 *       "block-entity removed".</li>
 *   <li><b>Client → host:</b> a {@link Packet.FurnaceSlotC2S} intent (player put
 *       /took an item in a furnace) is validated, applied to the authoritative
 *       furnace, then echoed back to everyone as a {@link Packet.BlockStateS2C}.</li>
 *   <li><b>Client apply:</b> inbound {@link Packet.BlockStateS2C} writes the state
 *       into the world + furnace registry (in place, so an open furnace UI tracks
 *       it) and schedules a remesh so lit/unlit visuals update.</li>
 * </ul>
 *
 * <p>Furnace smelting itself only ticks on the host (see
 * {@link FurnaceStateRegistry#tick}); clients are display-only.
 */
public final class BlockStateSynchronizer implements Synchronizer {

    /** Maximum allowed reach (squared) from a player's last-known position. */
    private static final float MAX_REACH = 8.0f;
    private static final float MAX_REACH_SQ = MAX_REACH * MAX_REACH;

    @Override
    public boolean handlesInbound(Packet packet) {
        return packet instanceof Packet.BlockStateS2C
                || packet instanceof Packet.FurnaceSlotC2S;
    }

    @Override
    public void applyInbound(Packet packet, Integer originId, SyncContext ctx) {
        switch (packet) {
            case Packet.BlockStateS2C s -> applyBlockState(s);
            case Packet.FurnaceSlotC2S f -> applyFurnaceSlot(f, originId, ctx);
            default -> {}
        }
    }

    @Override
    public boolean handlesLocal(SyncEvent event) {
        return event instanceof SyncEvent.BlockStateChanged;
    }

    @Override
    public void emitLocal(SyncEvent event, SyncContext ctx) {
        if (ctx.mode() != SyncMode.HOST) return;
        if (!(event instanceof SyncEvent.BlockStateChanged b)) return;
        ctx.broadcast(new Packet.BlockStateS2C(b.x(), b.y(), b.z(), b.state() == null ? "" : b.state()));
    }

    // ─── Client apply ────────────────────────────────────────────────────────

    private void applyBlockState(Packet.BlockStateS2C s) {
        World world = Game.getWorld();
        if (world == null) return; // chunk not ready; host re-pushes furnace state periodically
        BlockPos pos = new BlockPos(s.x(), s.y(), s.z());
        boolean removal = s.state() == null || s.state().isBlank();
        FurnaceStateRegistry reg = furnaceRegistry();

        if (removal) {
            world.setBlockStateAt(s.x(), s.y(), s.z(), null);
            if (reg != null) reg.removeAt(pos);
        } else {
            world.setBlockStateAt(s.x(), s.y(), s.z(), s.state());
            if (reg != null && s.state().startsWith(FurnaceState.STATE_PREFIX)) {
                reg.applyNetworkState(pos, s.state());
            }
        }
        // Lit/unlit visuals are baked into the mesh — rebuild so the change shows.
        world.scheduleChunkRemeshAt(s.x(), s.y(), s.z());
    }

    // ─── Host apply (client furnace operation) ────────────────────────────────

    private void applyFurnaceSlot(Packet.FurnaceSlotC2S f, Integer originId, SyncContext ctx) {
        if (ctx.mode() != SyncMode.HOST) return;
        World world = Game.getWorld();
        FurnaceStateRegistry reg = furnaceRegistry();
        if (world == null || reg == null) return;
        if (world.getBlockAt(f.x(), f.y(), f.z()) != BlockType.FURNACE) return;
        if (!withinReach(f.x(), f.y(), f.z(), originId)) return;

        BlockPos pos = new BlockPos(f.x(), f.y(), f.z());
        FurnaceState st = reg.getOrCreate(pos);
        ItemStack stack = FurnaceState.decodeStackString(f.stack());
        switch (f.slot()) {
            case 0 -> st.setIngredient(stack);
            case 1 -> st.setFuel(stack);
            case 2 -> st.setOutput(stack);
            default -> { return; }
        }
        // Echo the authoritative state to everyone (including the originator, so
        // their optimistic UI converges).
        reg.broadcastState(world, pos);
    }

    private static boolean withinReach(int x, int y, int z, Integer originId) {
        if (originId == null) return true; // host-local
        IntegratedServer srv = MultiplayerSession.getServer();
        if (srv == null) return false;
        RemoteClient rc = srv.getClient(originId);
        if (rc == null) return false;
        if (rc.getLastStateNs() == 0L) return true; // no position yet — accept conservatively
        float dx = (x + 0.5f) - rc.getX();
        float dy = (y + 0.5f) - rc.getY();
        float dz = (z + 0.5f) - rc.getZ();
        return (dx * dx + dy * dy + dz * dz) <= MAX_REACH_SQ;
    }

    private static FurnaceStateRegistry furnaceRegistry() {
        Game g = Game.getInstance();
        return g == null ? null : g.getFurnaceRegistry();
    }
}
