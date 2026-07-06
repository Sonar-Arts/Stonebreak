package com.stonebreak.network.packet.player;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Client → server: the joining player's character creation data. Contains all RPG
 * stats (class, ability scores, CP, skills, feats, background, etc.) that the client
 * has configured in the character creation UI. The server validates and applies this
 * data before welcoming the player into the world.
 */
public record CharacterCreationC2S(byte[] json) implements Packet {

    public static final int MAX_BYTES = 32 * 1024;

    public static final PacketCodec<CharacterCreationC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, CharacterCreationC2S p) {
            ByteBufIO.writeByteArray(out, p.json(), MAX_BYTES);
        }

        @Override
        public CharacterCreationC2S decode(ByteBuf in) {
            return new CharacterCreationC2S(ByteBufIO.readByteArray(in, MAX_BYTES));
        }
    };
}
