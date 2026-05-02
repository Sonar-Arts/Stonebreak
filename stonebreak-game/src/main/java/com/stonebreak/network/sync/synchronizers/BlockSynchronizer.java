package com.stonebreak.network.sync.synchronizers;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.network.protocol.Packet;
import com.stonebreak.network.sync.SyncContext;
import com.stonebreak.network.sync.SyncEvent;
import com.stonebreak.network.sync.SyncMode;
import com.stonebreak.network.sync.Synchronizer;

/**
 * Mirrors block place/break events between host and clients.
 * Local edits → BlockChange{C2S|S2C}; inbound packets are applied via
 * {@code World.setBlockAt} (the SyncService's applyingInbound flag prevents
 * the resulting hook from feeding back into the network).
 */
public final class BlockSynchronizer implements Synchronizer {

    @Override
    public boolean handlesInbound(Packet packet) {
        return packet instanceof Packet.BlockChangeC2S
                || packet instanceof Packet.BlockChangeS2C;
    }

    @Override
    public void applyInbound(Packet packet, Integer originId, SyncContext ctx) {
        switch (packet) {
            case Packet.BlockChangeC2S c -> {
                applyToWorld(c.x(), c.y(), c.z(), c.blockTypeId());
                // Host re-broadcasts to all clients (including originator for echo).
                ctx.broadcast(new Packet.BlockChangeS2C(c.x(), c.y(), c.z(), c.blockTypeId()));
            }
            case Packet.BlockChangeS2C s -> applyToWorld(s.x(), s.y(), s.z(), s.blockTypeId());
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
        short id = (short) (b.type() == null ? 0 : b.type().getId());
        if (ctx.mode() == SyncMode.HOST) {
            ctx.broadcast(new Packet.BlockChangeS2C(b.x(), b.y(), b.z(), id));
        } else if (ctx.mode() == SyncMode.CLIENT) {
            ctx.broadcast(new Packet.BlockChangeC2S(b.x(), b.y(), b.z(), id));
        }
    }

    private void applyToWorld(int x, int y, int z, short blockTypeId) {
        if (Game.getWorld() == null) return;
        BlockType type = BlockType.getById(blockTypeId & 0xFFFF);
        if (type == null) type = BlockType.AIR;
        Game.getWorld().setBlockAt(x, y, z, type, true);
    }
}
