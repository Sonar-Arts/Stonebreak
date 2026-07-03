package com.stonebreak.network.packet.handshake;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Server → client keepalive probe, sent every ~5 s per connection. The client echoes the
 * nonce back in a {@link KeepAliveC2S}; the round trip both proves the peer is alive
 * (either side disconnects after ~30 s of silence) and measures RTT.
 *
 * @param nonce     opaque value the client must echo back unchanged
 * @param lastRttMs the server's last measured RTT for this client (ms; 0 until first echo),
 *                  so the client can display its own ping without measuring separately
 */
public record KeepAliveS2C(long nonce, int lastRttMs) implements Packet {

    public static final PacketCodec<KeepAliveS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, KeepAliveS2C p) {
            out.writeLong(p.nonce());
            out.writeInt(p.lastRttMs());
        }

        @Override
        public KeepAliveS2C decode(ByteBuf in) {
            return new KeepAliveS2C(in.readLong(), in.readInt());
        }
    };
}
