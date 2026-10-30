package com.stonebreak.network.packet.player;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Server → the hit player: a push from a blow the server landed on them (issue #343) — a mob's
 * swing, another player's. The player's own client owns its movement, so the server can only
 * ask it to add this velocity (blocks per second).
 */
public record PlayerKnockbackS2C(float vx, float vy, float vz) implements Packet {

    public static final PacketCodec<PlayerKnockbackS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, PlayerKnockbackS2C p) {
            out.writeFloat(p.vx());
            out.writeFloat(p.vy());
            out.writeFloat(p.vz());
        }

        @Override
        public PlayerKnockbackS2C decode(ByteBuf in) {
            return new PlayerKnockbackS2C(in.readFloat(), in.readFloat(), in.readFloat());
        }
    };
}
