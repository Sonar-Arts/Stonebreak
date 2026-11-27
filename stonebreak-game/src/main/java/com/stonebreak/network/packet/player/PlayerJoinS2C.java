package com.stonebreak.network.packet.player;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import com.stonebreak.network.packet.NetCoords;
import com.stonebreak.player.appearance.PlayerAppearance;
import io.netty.buffer.ByteBuf;

/**
 * Server → all: a player joined; carries their id, name, spawn position and look (#459: the binary omlook record,
 * empty = unknown, draw the defaults).
 */
public record PlayerJoinS2C(int playerId, String username,
                            double x, double y, double z, byte[] appearance) implements Packet {

    public PlayerJoinS2C {
        appearance = appearance == null ? new byte[0] : appearance;
    }

    /** A join without a look. */
    public PlayerJoinS2C(int playerId, String username, double x, double y, double z) {
        this(playerId, username, x, y, z, null);
    }

    public static final PacketCodec<PlayerJoinS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, PlayerJoinS2C p) {
            out.writeInt(p.playerId());
            ByteBufIO.writeString(out, p.username(), ByteBufIO.MAX_USERNAME_CHARS);
            NetCoords.writePosition(out, p.x(), p.y(), p.z());
            ByteBufIO.writeByteArray(out, p.appearance(), PlayerAppearance.MAX_BYTES);
        }

        @Override
        public PlayerJoinS2C decode(ByteBuf in) {
            return new PlayerJoinS2C(
                in.readInt(),
                ByteBufIO.readString(in, ByteBufIO.MAX_USERNAME_CHARS),
                NetCoords.readPosition(in), NetCoords.readPosition(in), NetCoords.readPosition(in),
                ByteBufIO.readByteArray(in, PlayerAppearance.MAX_BYTES));
        }
    };

    /** Value equality (the look bytes compare by content). */
    @Override
    public boolean equals(Object o) {
        return o instanceof PlayerJoinS2C p && playerId == p.playerId && username.equals(p.username)
                && Double.compare(x, p.x) == 0 && Double.compare(y, p.y) == 0 && Double.compare(z, p.z) == 0
                && java.util.Arrays.equals(appearance, p.appearance);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(playerId, username, x, y, z) * 31 + java.util.Arrays.hashCode(appearance);
    }
}
