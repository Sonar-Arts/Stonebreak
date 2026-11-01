package com.openmason.engine.net.pipeline;

import com.openmason.engine.net.protocol.ProtocolPhase;
import io.netty.util.AttributeKey;

/**
 * Shared Netty channel attributes used across the networking pipeline.
 */
public final class PipelineAttributes {

    /**
     * Current {@link ProtocolPhase} of a channel. Read by the encoder and decoder to
     * select the registry slice; flipped to {@code PLAY} once the handshake completes.
     */
    public static final AttributeKey<ProtocolPhase> PHASE = AttributeKey.valueOf("openmason.net.phase");

    /**
     * Compression codecs the peer decodes ({@link com.openmason.engine.net.protocol.codec.WireCompression}
     * bitmask), set once the handshake reports them; absent = none (issue #346).
     */
    public static final AttributeKey<Integer> PEER_COMPRESSION = AttributeKey.valueOf("openmason.net.peerCompression");

    /** Last error seen by {@code exceptionCaught}, read by {@code channelInactive}. */
    public static final AttributeKey<Throwable> CAUSE = AttributeKey.valueOf("openmason.net.cause");

    private PipelineAttributes() {}
}
