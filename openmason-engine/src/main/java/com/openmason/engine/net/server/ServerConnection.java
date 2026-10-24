package com.openmason.engine.net.server;

import com.openmason.engine.net.pipeline.PipelineAttributes;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.ProtocolPhase;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Server-side handle for one connected client. Wraps a Netty {@link Channel} and adds
 * writability-aware sending, phase control, and a game-supplied attachment (typically the
 * game's per-connection player object).
 *
 * <p>{@code writeAndFlush} is thread-safe, so {@link #send} may be called from the tick
 * thread; the actual write is scheduled on the channel's event loop.
 *
 * <p>Abuse limits (issue #336): a connection whose queued outbound bytes pass
 * {@link #maxOutboundBytes} is closed (a peer that never reads must not grow the heap), and
 * reading pauses while too many of its packets wait for the tick ({@link #onQueued}).
 */
public final class ServerConnection {

    private static final Logger log = LoggerFactory.getLogger(ServerConnection.class);

    /** Links a child channel back to its {@code ServerConnection}. */
    public static final AttributeKey<ServerConnection> ATTR =
        AttributeKey.valueOf("openmason.net.serverConnection");

    /** How long a kick waits for its reason to flush before the channel is closed anyway. */
    static final long KICK_FLUSH_TIMEOUT_MILLIS = 2_000L;

    private final Channel channel;
    /** Queued outbound bytes past which the connection is closed; 0 = unlimited. */
    private final long maxOutboundBytes;
    private volatile Object attachment;
    /** True once the peer sent an admitting packet (or is trusted, e.g. Local). */
    private volatile boolean admitted;
    /** Builds the packet a kick sends with its reason; null = close without one. */
    private volatile Function<String, Packet> kickPacket;

    /** Packets from this connection waiting in the inbound queue. */
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicBoolean readPaused = new AtomicBoolean();

    public ServerConnection(Channel channel) {
        this(channel, 0L);
    }

    public ServerConnection(Channel channel, long maxOutboundBytes) {
        this.channel = channel;
        this.maxOutboundBytes = maxOutboundBytes;
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
     * <p>A connection whose queued bytes are past {@link #maxOutboundBytes} is closed instead
     * — whatever the packet — since its peer is not reading.
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
        if (maxOutboundBytes > 0 && pendingOutboundBytes() > maxOutboundBytes) {
            log.info("Closing {}: over {} bytes queued and not being read", this, maxOutboundBytes);
            closeWithCause(new IOException("Outbound buffer over " + maxOutboundBytes + " bytes"));
            return false;
        }
        channel.writeAndFlush(packet);
        return true;
    }

    /** Bytes queued for this connection that the peer has not taken yet. */
    long pendingOutboundBytes() {
        Channel.Unsafe unsafe = channel.unsafe();
        ChannelOutboundBuffer buffer = unsafe != null ? unsafe.outboundBuffer() : null;
        return buffer != null ? buffer.totalPendingWriteBytes() : 0L;
    }

    /**
     * Sends {@code packet} and closes the connection once it has been flushed — a kick's
     * reason reaches the client before the channel goes, instead of racing the close. A peer
     * that is not reading never lets it flush, so the channel is closed after
     * {@link #KICK_FLUSH_TIMEOUT_MILLIS} regardless.
     */
    public void sendAndClose(Packet packet) {
        if (!channel.isActive()) {
            return;
        }
        channel.writeAndFlush(packet).addListener(io.netty.channel.ChannelFutureListener.CLOSE);
        channel.eventLoop().schedule(() -> {
            channel.close();
        }, KICK_FLUSH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    }

    /** Send critical traffic (never dropped). */
    public boolean send(Packet packet) {
        return send(packet, false);
    }

    /**
     * Kicks the connection with {@code reason}: the game's kick packet when one is set
     * ({@link NetworkServer#setKickPacketFactory}), then the close; just the close otherwise.
     */
    public void kick(String reason) {
        Function<String, Packet> factory = kickPacket;
        if (factory != null) {
            sendAndClose(factory.apply(reason));
        } else {
            closeWithCause(new IOException(reason));
        }
    }

    void setKickPacket(Function<String, Packet> factory) {
        this.kickPacket = factory;
    }

    public void close() {
        channel.close();
    }

    private void closeWithCause(Throwable cause) {
        channel.attr(PipelineAttributes.CAUSE).setIfAbsent(cause);
        channel.close();
    }

    /** True once the peer is admitted (see {@link NetworkServer#setAdmissionFilter}). */
    public boolean isAdmitted() {
        return admitted;
    }

    void markAdmitted() {
        this.admitted = true;
    }

    // ─── Inbound backpressure (event loop posts, tick thread drains) ─────────────

    /** One more of this connection's packets is queued; pauses reading at {@code pauseDepth}. */
    void onQueued(int pauseDepth) {
        if (queued.incrementAndGet() >= pauseDepth && readPaused.compareAndSet(false, true)) {
            applyReadGate();
        }
    }

    /** One of this connection's packets left the queue; resumes reading at {@code resumeDepth}. */
    void onDequeued(int resumeDepth) {
        if (queued.decrementAndGet() <= resumeDepth && readPaused.compareAndSet(true, false)) {
            applyReadGate();
        }
    }

    /** Packets from this connection waiting for the tick. */
    public int queuedPackets() {
        return queued.get();
    }

    /** True while reading from this connection is paused for backlog. */
    public boolean isReadPaused() {
        return readPaused.get();
    }

    /**
     * Applies the current pause state on the event loop. The task reads the flag when it
     * runs, so a pause and a resume scheduled from different threads in either order still
     * leave the channel matching the latest state.
     */
    private void applyReadGate() {
        if (channel.eventLoop() == null) {
            return;
        }
        channel.eventLoop().execute(() -> channel.config().setAutoRead(!readPaused.get()));
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
