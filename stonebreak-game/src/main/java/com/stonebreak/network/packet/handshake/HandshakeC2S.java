package com.stonebreak.network.packet.handshake;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Client → server: first packet of the HANDSHAKE phase (version + username + the join secret
 * this server issued the client last time, "" if none — issue #335 — and the compression
 * codecs the client decodes, a {@code WireCompression} bitmask — issue #346).
 */
public record HandshakeC2S(int protocolVersion, String username, String clientSecret,
                           int compressionCodecs) implements Packet {

    /** Longest join secret on the wire (the server issues far shorter ones). */
    public static final int MAX_SECRET_CHARS = 64;

    public HandshakeC2S {
        clientSecret = clientSecret == null ? "" : clientSecret;
        compressionCodecs &= 0xFF;
    }

    /** A handshake that decodes no compression (tests, older callers). */
    public HandshakeC2S(int protocolVersion, String username, String clientSecret) {
        this(protocolVersion, username, clientSecret, 0);
    }

    /** A handshake without a secret (first join, or the in-process host). */
    public HandshakeC2S(int protocolVersion, String username) {
        this(protocolVersion, username, "");
    }

    public static final PacketCodec<HandshakeC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, HandshakeC2S p) {
            out.writeInt(p.protocolVersion());
            ByteBufIO.writeString(out, p.username(), ByteBufIO.MAX_USERNAME_CHARS);
            ByteBufIO.writeString(out, p.clientSecret(), MAX_SECRET_CHARS);
            out.writeByte(p.compressionCodecs());
        }

        @Override
        public HandshakeC2S decode(ByteBuf in) {
            int version = in.readInt();
            String username = ByteBufIO.readString(in, ByteBufIO.MAX_USERNAME_CHARS);
            // Absent from a pre-v16 client's handshake (secret) and a pre-v20 one (codecs):
            // decode it anyway, so the server can answer with "Protocol mismatch" instead of a
            // decoder error closing the channel.
            String secret = in.isReadable() ? ByteBufIO.readString(in, MAX_SECRET_CHARS) : "";
            int codecs = in.isReadable() ? in.readUnsignedByte() : 0;
            return new HandshakeC2S(version, username, secret, codecs);
        }
    };
}
