package com.stonebreak.network.packet.player;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/** Server → all: a player left. */
public record PlayerLeaveS2C(int playerId) implements Packet {

    public static final PacketCodec<PlayerLeaveS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, PlayerLeaveS2C p) {
            out.writeInt(p.playerId());
        }

        @Override
        public PlayerLeaveS2C decode(ByteBuf in) {
            return new PlayerLeaveS2C(in.readInt());
        }
    };
}
