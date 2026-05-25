package com.stonebreak.network.packet.handshake;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/** Server → client refusal (version mismatch, kick, etc.). The client closes after receiving. */
public record KickS2C(String reason) implements Packet {

    public static final PacketCodec<KickS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, KickS2C p) {
            ByteBufIO.writeString(out, p.reason(), ByteBufIO.MAX_REASON_CHARS);
        }

        @Override
        public KickS2C decode(ByteBuf in) {
            return new KickS2C(ByteBufIO.readString(in, ByteBufIO.MAX_REASON_CHARS));
        }
    };
}
