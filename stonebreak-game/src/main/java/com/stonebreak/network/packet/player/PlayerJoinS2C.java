package com.stonebreak.network.packet.player;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import com.stonebreak.network.packet.NetCoords;
import io.netty.buffer.ByteBuf;

/** Server → all: a player joined; carries their id, name, and spawn position. */
public record PlayerJoinS2C(int playerId, String username,
                            double x, double y, double z) implements Packet {

    public static final PacketCodec<PlayerJoinS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, PlayerJoinS2C p) {
            out.writeInt(p.playerId());
            ByteBufIO.writeString(out, p.username(), ByteBufIO.MAX_USERNAME_CHARS);
            NetCoords.writePosition(out, p.x(), p.y(), p.z());
        }

        @Override
        public PlayerJoinS2C decode(ByteBuf in) {
            return new PlayerJoinS2C(
                in.readInt(),
                ByteBufIO.readString(in, ByteBufIO.MAX_USERNAME_CHARS),
                NetCoords.readPosition(in), NetCoords.readPosition(in), NetCoords.readPosition(in));
        }
    };
}
