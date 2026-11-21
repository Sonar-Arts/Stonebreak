package com.stonebreak.network.packet.world;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import com.stonebreak.network.packet.NetCoords;
import io.netty.buffer.ByteBuf;

/** Server → client: an authoritative single block change (64-bit block coordinates, zigzag varlongs). */
public record BlockChangeS2C(long x, long y, long z, short blockTypeId) implements Packet {

    public static final PacketCodec<BlockChangeS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, BlockChangeS2C p) {
            NetCoords.writeBlock(out, p.x(), p.y(), p.z());
            out.writeShort(p.blockTypeId());
        }

        @Override
        public BlockChangeS2C decode(ByteBuf in) {
            long x = NetCoords.readVarLong(in);
            long y = NetCoords.readVarLong(in);
            long z = NetCoords.readVarLong(in);
            return new BlockChangeS2C(x, y, z, in.readShort());
        }
    };
}
