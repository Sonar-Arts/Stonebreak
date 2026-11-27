package com.stonebreak.network.packet.player;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import com.stonebreak.player.appearance.PlayerAppearance;
import io.netty.buffer.ByteBuf;

/**
 * Server → clients (#459): a player's look (binary omlook record) - to everyone when it changed, to its owner on
 * joining and when a change was refused (the owner's preview goes back to what the server keeps).
 */
public record PlayerAppearanceS2C(int playerId, byte[] appearance) implements Packet {

    public static final PacketCodec<PlayerAppearanceS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, PlayerAppearanceS2C p) {
            out.writeInt(p.playerId());
            ByteBufIO.writeByteArray(out, p.appearance(), PlayerAppearance.MAX_BYTES);
        }

        @Override
        public PlayerAppearanceS2C decode(ByteBuf in) {
            return new PlayerAppearanceS2C(in.readInt(), ByteBufIO.readByteArray(in, PlayerAppearance.MAX_BYTES));
        }
    };

    /** Value equality (the look bytes compare by content). */
    @Override
    public boolean equals(Object o) {
        return o instanceof PlayerAppearanceS2C p && playerId == p.playerId && java.util.Arrays.equals(appearance, p.appearance);
    }

    @Override
    public int hashCode() {
        return java.util.Arrays.hashCode(appearance) * 31 + playerId;
    }
}
