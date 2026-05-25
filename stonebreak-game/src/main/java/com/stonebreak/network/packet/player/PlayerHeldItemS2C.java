package com.stonebreak.network.packet.player;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/** Server → all: a player's held item changed (or initial snapshot on join). */
public record PlayerHeldItemS2C(int playerId, int itemId) implements Packet {

    public static final PacketCodec<PlayerHeldItemS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, PlayerHeldItemS2C p) {
            out.writeInt(p.playerId());
            out.writeInt(p.itemId());
        }

        @Override
        public PlayerHeldItemS2C decode(ByteBuf in) {
            return new PlayerHeldItemS2C(in.readInt(), in.readInt());
        }
    };
}
