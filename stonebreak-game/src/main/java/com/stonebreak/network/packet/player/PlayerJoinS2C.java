package com.stonebreak.network.packet.player;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/** Server → all: a player joined; carries their id, name, and spawn position. */
public record PlayerJoinS2C(int playerId, String username,
                            float x, float y, float z) implements Packet {

    public static final PacketCodec<PlayerJoinS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, PlayerJoinS2C p) {
            out.writeInt(p.playerId());
            ByteBufIO.writeString(out, p.username(), ByteBufIO.MAX_USERNAME_CHARS);
            out.writeFloat(p.x());
            out.writeFloat(p.y());
            out.writeFloat(p.z());
        }

        @Override
        public PlayerJoinS2C decode(ByteBuf in) {
            return new PlayerJoinS2C(
                in.readInt(),
                ByteBufIO.readString(in, ByteBufIO.MAX_USERNAME_CHARS),
                in.readFloat(), in.readFloat(), in.readFloat());
        }
    };
}
