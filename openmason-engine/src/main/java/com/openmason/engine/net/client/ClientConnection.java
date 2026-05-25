package com.openmason.engine.net.client;

import com.openmason.engine.net.pipeline.PipelineAttributes;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.ProtocolPhase;
import io.netty.channel.Channel;

/**
 * Client-side handle for the single server connection. Mirrors {@code ServerConnection}:
 * writability-aware sending and phase control over a Netty {@link Channel}.
 */
public final class ClientConnection {

    private final Channel channel;

    public ClientConnection(Channel channel) {
        this.channel = channel;
    }

    public Channel channel() {
        return channel;
    }

    public boolean isWritable() {
        return channel.isWritable();
    }

    public boolean isActive() {
        return channel.isActive();
    }

    public void setPhase(ProtocolPhase phase) {
        channel.attr(PipelineAttributes.PHASE).set(phase);
    }

    public ProtocolPhase phase() {
        return channel.attr(PipelineAttributes.PHASE).get();
    }

    /**
     * Send a packet. If {@code droppable} and the channel is over its high write watermark,
     * the packet is skipped (hot client traffic such as player state self-heals next tick).
     *
     * @return {@code true} if written, {@code false} if dropped or the channel is closed
     */
    public boolean send(Packet packet, boolean droppable) {
        if (!channel.isActive()) {
            return false;
        }
        if (droppable && !channel.isWritable()) {
            return false;
        }
        channel.writeAndFlush(packet);
        return true;
    }

    public boolean send(Packet packet) {
        return send(packet, false);
    }

    public void close() {
        channel.close();
    }
}
