package com.stonebreak.network.packet.player;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Client → server: the sender died and scatters its inventory. One request replaces the
 * old per-stack {@code DropItemC2S} burst: the server spawns a drop for every stack its
 * own authoritative replica holds — without consuming toss rate-limit tokens (a death
 * legitimately exceeds the toss budget) — then clears the replica and syncs. Nothing is
 * client-supplied, so a forged request can only scatter the victim's own items (issue
 * #334).
 */
public record DeathDropC2S() implements Packet {

    public static final PacketCodec<DeathDropC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, DeathDropC2S p) {
            // no payload
        }

        @Override
        public DeathDropC2S decode(ByteBuf in) {
            return new DeathDropC2S();
        }
    };
}
