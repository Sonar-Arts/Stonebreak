package com.stonebreak.network.packet.handshake;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/** Client → server: first packet of the HANDSHAKE phase (version + username). */
public record HandshakeC2S(int protocolVersion, String username) implements Packet {

    public static final PacketCodec<HandshakeC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, HandshakeC2S p) {
            out.writeInt(p.protocolVersion());
            ByteBufIO.writeString(out, p.username(), ByteBufIO.MAX_USERNAME_CHARS);
        }

        @Override
        public HandshakeC2S decode(ByteBuf in) {
            return new HandshakeC2S(
                in.readInt(),
                ByteBufIO.readString(in, ByteBufIO.MAX_USERNAME_CHARS));
        }
    };
}
