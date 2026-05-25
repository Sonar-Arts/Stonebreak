package com.stonebreak.network.packet.world;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/** Client → server: the local player placed or broke a block (an intent, server-validated). */
public record BlockChangeC2S(int x, int y, int z, short blockTypeId) implements Packet {

    public static final PacketCodec<BlockChangeC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, BlockChangeC2S p) {
            out.writeInt(p.x());
            out.writeInt(p.y());
            out.writeInt(p.z());
            out.writeShort(p.blockTypeId());
        }

        @Override
        public BlockChangeC2S decode(ByteBuf in) {
            return new BlockChangeC2S(in.readInt(), in.readInt(), in.readInt(), in.readShort());
        }
    };
}
