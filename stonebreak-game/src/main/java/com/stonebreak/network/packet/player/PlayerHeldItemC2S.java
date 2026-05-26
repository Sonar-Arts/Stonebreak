package com.stonebreak.network.packet.player;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/** Client → server: the local player switched their held hotbar item. */
public record PlayerHeldItemC2S(int itemId) implements Packet {

    public static final PacketCodec<PlayerHeldItemC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, PlayerHeldItemC2S p) {
            out.writeInt(p.itemId());
        }

        @Override
        public PlayerHeldItemC2S decode(ByteBuf in) {
            return new PlayerHeldItemC2S(in.readInt());
        }
    };
}
