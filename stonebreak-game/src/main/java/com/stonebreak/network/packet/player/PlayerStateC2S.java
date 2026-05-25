package com.stonebreak.network.packet.player;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/** Client → server: local player position + orientation (sent at 20 Hz). */
public record PlayerStateC2S(float x, float y, float z, float yaw, float pitch) implements Packet {

    public static final PacketCodec<PlayerStateC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, PlayerStateC2S p) {
            out.writeFloat(p.x());
            out.writeFloat(p.y());
            out.writeFloat(p.z());
            out.writeFloat(p.yaw());
            out.writeFloat(p.pitch());
        }

        @Override
        public PlayerStateC2S decode(ByteBuf in) {
            return new PlayerStateC2S(
                in.readFloat(), in.readFloat(), in.readFloat(),
                in.readFloat(), in.readFloat());
        }
    };
}
