package com.stonebreak.network.packet.player;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Server → client: a remote player's position + orientation + movement/action flags
 * ({@link PlayerStateFlags}), relayed verbatim from that player's {@link PlayerStateC2S}.
 */
public record PlayerStateS2C(int playerId,
                             float x, float y, float z,
                             float yaw, float pitch, byte flags) implements Packet {

    public static final PacketCodec<PlayerStateS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, PlayerStateS2C p) {
            out.writeInt(p.playerId());
            out.writeFloat(p.x());
            out.writeFloat(p.y());
            out.writeFloat(p.z());
            out.writeFloat(p.yaw());
            out.writeFloat(p.pitch());
            out.writeByte(p.flags());
        }

        @Override
        public PlayerStateS2C decode(ByteBuf in) {
            return new PlayerStateS2C(
                in.readInt(),
                in.readFloat(), in.readFloat(), in.readFloat(),
                in.readFloat(), in.readFloat(), in.readByte());
        }
    };
}
