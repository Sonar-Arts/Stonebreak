package com.openmason.engine.net.server;

import com.openmason.engine.net.pipeline.PipelineAttributes;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.ProtocolPhase;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

/**
 * Server-side handle for one connected client. Wraps a Netty {@link Channel} and adds
 * writability-aware sending, phase control, and a game-supplied attachment (typically the
 * game's per-connection player object).
 *
 * <p>{@code writeAndFlush} is thread-safe, so {@link #send} may be called from the tick
 * thread; the actual write is scheduled on the channel's event loop.
 */
public final class ServerConnection {

    /** Links a child channel back to its {@code ServerConnection}. */
    public static final AttributeKey<ServerConnection> ATTR =
        AttributeKey.valueOf("openmason.net.serverConnection");

    private final Channel channel;
    private volatile Object attachment;

    public ServerConnection(Channel channel) {
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

    /** Set the protocol phase (flips both encoder and decoder lookups for this channel). */
    public void setPhase(ProtocolPhase phase) {
        channel.attr(PipelineAttributes.PHASE).set(phase);
    }

    public ProtocolPhase phase() {
        return channel.attr(PipelineAttributes.PHASE).get();
    }

    /**
     * Send a packet. If {@code droppable} and the channel is over its high write watermark,
     * the packet is skipped — hot traffic (entity deltas, player state) self-heals on the
     * next replication tick. Critical traffic should pass {@code droppable = false}.
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

    /** Send critical traffic (never dropped). */
    public boolean send(Packet packet) {
        return send(packet, false);
    }

    public void close() {
        channel.close();
    }

    /** Game-supplied per-connection state (e.g. the server-side player). */
    @SuppressWarnings("unchecked")
    public <T> T attachment() {
        return (T) attachment;
    }

    public void setAttachment(Object attachment) {
        this.attachment = attachment;
    }

    @Override
    public String toString() {
        return "ServerConnection[" + channel.id() + "]";
    }
}
