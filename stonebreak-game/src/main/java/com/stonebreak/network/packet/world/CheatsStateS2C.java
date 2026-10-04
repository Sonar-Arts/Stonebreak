package com.stonebreak.network.packet.world;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Server → client: the world's authoritative cheats flag. Sent right after
 * {@code WelcomeS2C} (so a loaded world restores its saved setting on the client) and
 * broadcast whenever the host toggles it with {@code CheatsSetC2S}.
 */
public record CheatsStateS2C(boolean enabled) implements Packet {

    public static final PacketCodec<CheatsStateS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, CheatsStateS2C p) {
            out.writeBoolean(p.enabled());
        }

        @Override
        public CheatsStateS2C decode(ByteBuf in) {
            return new CheatsStateS2C(in.readBoolean());
        }
    };
}
