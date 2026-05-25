package com.stonebreak.network.packet.world;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/** Server → client: an authoritative single block change. */
public record BlockChangeS2C(int x, int y, int z, short blockTypeId) implements Packet {

    public static final PacketCodec<BlockChangeS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, BlockChangeS2C p) {
            out.writeInt(p.x());
            out.writeInt(p.y());
            out.writeInt(p.z());
            out.writeShort(p.blockTypeId());
        }

        @Override
        public BlockChangeS2C decode(ByteBuf in) {
            return new BlockChangeS2C(in.readInt(), in.readInt(), in.readInt(), in.readShort());
        }
    };
}
