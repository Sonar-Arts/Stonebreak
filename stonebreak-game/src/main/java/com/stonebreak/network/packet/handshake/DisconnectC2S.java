package com.stonebreak.network.packet.handshake;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/** Client → server: graceful disconnect notice. */
public record DisconnectC2S(String reason) implements Packet {

    public static final PacketCodec<DisconnectC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, DisconnectC2S p) {
            ByteBufIO.writeString(out, p.reason(), ByteBufIO.MAX_REASON_CHARS);
        }

        @Override
        public DisconnectC2S decode(ByteBuf in) {
            return new DisconnectC2S(ByteBufIO.readString(in, ByteBufIO.MAX_REASON_CHARS));
        }
    };
}
