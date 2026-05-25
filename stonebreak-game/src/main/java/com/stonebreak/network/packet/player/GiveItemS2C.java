package com.stonebreak.network.packet.player;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Server → one client: hand the receiving client this item stack (authoritative drop
 * pickup, command-give, etc.). The client adds it to its local inventory.
 */
public record GiveItemS2C(int itemId, int count) implements Packet {

    public static final PacketCodec<GiveItemS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, GiveItemS2C p) {
            out.writeInt(p.itemId());
            out.writeInt(p.count());
        }

        @Override
        public GiveItemS2C decode(ByteBuf in) {
            return new GiveItemS2C(in.readInt(), in.readInt());
        }
    };
}
