package com.openmason.engine.net.protocol;

import io.netty.buffer.ByteBuf;

/**
 * Per-packet binary (de)serialization. Each concrete packet type owns exactly one codec,
 * registered in a {@link PacketRegistry}. Codecs are stateless and thread-safe.
 *
 * <p>A codec handles the packet <em>body</em> only — the frame length prefix and the
 * packet id are written by the pipeline and registry, not here.
 *
 * @param <T> the packet type this codec encodes and decodes
 */
public interface PacketCodec<T extends Packet> {

    /** Write {@code packet}'s fields into {@code out}. */
    void encode(ByteBuf out, T packet);

    /**
     * Write {@code packet} for a peer that decodes the {@code peerCompression} codecs
     * ({@link com.openmason.engine.net.protocol.codec.WireCompression} bitmask, negotiated in the
     * handshake; 0 = none). The wire encoder calls this; codecs that never compress keep the
     * default, which ignores the mask.
     */
    default void encode(ByteBuf out, T packet, int peerCompression) {
        encode(out, packet);
    }

    /** Read a packet of type {@code T} from {@code in}. */
    T decode(ByteBuf in);
}
