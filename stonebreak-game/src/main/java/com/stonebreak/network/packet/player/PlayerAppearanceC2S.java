package com.stonebreak.network.packet.player;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import com.stonebreak.player.appearance.PlayerAppearance;
import io.netty.buffer.ByteBuf;

/**
 * Client → server (#459): the look this player asks for (binary omlook record). {@code offer} = the client's
 * preferred look sent after joining: the server takes it only for a player whose look was never set (a new player,
 * a save from before #459); otherwise it is a change, checked against the catalog. Either way the server answers with
 * {@link PlayerAppearanceS2C}.
 */
public record PlayerAppearanceC2S(byte[] appearance, boolean offer) implements Packet {

    public static final PacketCodec<PlayerAppearanceC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, PlayerAppearanceC2S p) {
            ByteBufIO.writeByteArray(out, p.appearance(), PlayerAppearance.MAX_BYTES);
            out.writeBoolean(p.offer());
        }

        @Override
        public PlayerAppearanceC2S decode(ByteBuf in) {
            return new PlayerAppearanceC2S(ByteBufIO.readByteArray(in, PlayerAppearance.MAX_BYTES), in.readBoolean());
        }
    };

    /** Value equality (the look bytes compare by content). */
    @Override
    public boolean equals(Object o) {
        return o instanceof PlayerAppearanceC2S p && offer == p.offer && java.util.Arrays.equals(appearance, p.appearance);
    }

    @Override
    public int hashCode() {
        return java.util.Arrays.hashCode(appearance) * 31 + Boolean.hashCode(offer);
    }
}
