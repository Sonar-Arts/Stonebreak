package com.stonebreak.network.packet.player;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import com.stonebreak.network.packet.NetCoords;
import io.netty.buffer.ByteBuf;

/**
 * Client → server: local player position + orientation + movement/action flags
 * ({@link PlayerStateFlags}), sent at 20 Hz.
 */
public record PlayerStateC2S(double x, double y, double z, float yaw, float pitch, short flags) implements Packet {

    public static final PacketCodec<PlayerStateC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, PlayerStateC2S p) {
            NetCoords.writePosition(out, p.x(), p.y(), p.z());
            out.writeFloat(p.yaw());
            out.writeFloat(p.pitch());
            out.writeShort(p.flags());
        }

        @Override
        public PlayerStateC2S decode(ByteBuf in) {
            return new PlayerStateC2S(
                NetCoords.readPosition(in), NetCoords.readPosition(in), NetCoords.readPosition(in),
                in.readFloat(), in.readFloat(), in.readShort());
        }
    };
}
