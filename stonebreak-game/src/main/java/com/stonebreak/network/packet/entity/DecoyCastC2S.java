package com.stonebreak.network.packet.entity;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Client → server: the sender cast Mirrored Deceit (issue #341). The SERVER spawns the
 * illusion decoys around its own record of the player — so server mobs can target them —
 * drives them from the player's reported movement, and replicates them to every client
 * (the caster included). Nothing is client-supplied: position, facing and the mirrored held
 * item come from the server's state of the player.
 */
public record DecoyCastC2S() implements Packet {

    public static final PacketCodec<DecoyCastC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, DecoyCastC2S p) {
            // no payload
        }

        @Override
        public DecoyCastC2S decode(ByteBuf in) {
            return new DecoyCastC2S();
        }
    };
}
