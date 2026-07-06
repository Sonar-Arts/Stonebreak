package com.stonebreak.network.packet.handshake;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Server → client: the joining player has no saved data and must create a character
 * before entering the world. Carries the world seed so the client can build the world
 * after character creation is complete.
 */
public record NeedsCharacterCreationS2C(int playerId, long worldSeed) implements Packet {

    public static final PacketCodec<NeedsCharacterCreationS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, NeedsCharacterCreationS2C p) {
            out.writeInt(p.playerId());
            out.writeLong(p.worldSeed());
        }

        @Override
        public NeedsCharacterCreationS2C decode(ByteBuf in) {
            return new NeedsCharacterCreationS2C(in.readInt(), in.readLong());
        }
    };
}
