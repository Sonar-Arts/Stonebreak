package com.stonebreak.network.packet.world;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Server → all clients: whether the world lets players hurt each other (issue #343). The flag
 * lives in the world's save and changes only through the server's {@code /pvp} command; clients
 * mirror it to decide whether another player is a combat target at all. Sent on join and on change.
 */
public record PvpStateS2C(boolean enabled) implements Packet {

    public static final PacketCodec<PvpStateS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, PvpStateS2C p) {
            out.writeBoolean(p.enabled());
        }

        @Override
        public PvpStateS2C decode(ByteBuf in) {
            return new PvpStateS2C(in.readBoolean());
        }
    };
}
