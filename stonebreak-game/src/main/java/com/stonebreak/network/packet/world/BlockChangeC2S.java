package com.stonebreak.network.packet.world;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Client → server: the local player placed or broke a block (an intent, server-validated).
 *
 * <p>{@code prevBlockTypeId} is the client's view of the block AT the moment they edited it —
 * needed for break-drop spawning so the server isn't forced to infer "what was there" from its
 * own (possibly out-of-sync) world snapshot. The server treats {@code prevBlockTypeId} as the
 * authoritative source of "what the player broke" for drop decisions; it still applies
 * {@code blockTypeId} to its own world for state correctness.
 */
public record BlockChangeC2S(int x, int y, int z, short blockTypeId, short prevBlockTypeId) implements Packet {

    public static final PacketCodec<BlockChangeC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, BlockChangeC2S p) {
            out.writeInt(p.x());
            out.writeInt(p.y());
            out.writeInt(p.z());
            out.writeShort(p.blockTypeId());
            out.writeShort(p.prevBlockTypeId());
        }

        @Override
        public BlockChangeC2S decode(ByteBuf in) {
            return new BlockChangeC2S(in.readInt(), in.readInt(), in.readInt(), in.readShort(), in.readShort());
        }
    };
}
