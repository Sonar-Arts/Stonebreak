package com.stonebreak.network.packet.player;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Client → server: the player selected a hotbar slot (number keys / scroll wheel / UI
 * document). Replaces the old {@code PlayerHeldItemC2S} relay: the server stores the
 * selection and derives the held item from its own inventory replica, so tool-specific
 * drops and the shears check read a value the client cannot forge.
 */
public record HotbarSelectC2S(int slot) implements Packet {

    public static final PacketCodec<HotbarSelectC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, HotbarSelectC2S p) {
            out.writeByte(p.slot());
        }

        @Override
        public HotbarSelectC2S decode(ByteBuf in) {
            return new HotbarSelectC2S(in.readUnsignedByte());
        }
    };
}
