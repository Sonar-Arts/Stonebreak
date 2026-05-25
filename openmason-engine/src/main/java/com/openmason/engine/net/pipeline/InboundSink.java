package com.openmason.engine.net.pipeline;

import com.openmason.engine.net.protocol.Packet;
import io.netty.channel.Channel;

/**
 * Destination for inbound traffic and connection lifecycle, invoked by
 * {@link InboundHandler} on a Netty event-loop thread.
 *
 * <p>Implementations must only <em>enqueue</em> — they must never touch world/entity state
 * directly (that happens later when the owning thread drains the queue). The synthetic
 * {@link #onConnect}/{@link #onDisconnect} callbacks let join/leave be processed on the
 * tick/game thread alongside packets.
 */
public interface InboundSink {

    /** A channel became active (connected). */
    void onConnect(Channel channel);

    /** A fully-formed packet arrived. */
    void onPacket(Channel channel, Packet packet);

    /** A channel became inactive (disconnected). {@code cause} may be {@code null}. */
    void onDisconnect(Channel channel, Throwable cause);
}
