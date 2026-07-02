package com.stonebreak.network.packet.handshake;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Client → server echo of a {@link KeepAliveS2C} nonce. The server matches it against the
 * outstanding nonce to compute RTT and to refresh the client's liveness deadline.
 *
 * @param nonce the value from the {@link KeepAliveS2C} being answered
 */
public record KeepAliveC2S(long nonce) implements Packet {

    public static final PacketCodec<KeepAliveC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, KeepAliveC2S p) {
            out.writeLong(p.nonce());
        }

        @Override
        public KeepAliveC2S decode(ByteBuf in) {
            return new KeepAliveC2S(in.readLong());
        }
    };
}
