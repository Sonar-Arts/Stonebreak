package com.openmason.engine.net.server;

import com.openmason.engine.net.protocol.Packet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Lock-free hand-off from Netty event-loop threads to the server tick thread. Connection
 * lifecycle (CONNECT/DISCONNECT) and PLAY packets travel as one ordered stream, so
 * join/leave and packet application all run on the tick thread — never on an event loop.
 *
 * <p>Bounded (issue #336): a connection with {@code readPauseDepth} packets waiting stops
 * being read until the tick drains it back to {@code readResumeDepth}, so a client sending
 * faster than the tick drains backs up in its own TCP window instead of the heap. A drain
 * never handles more than was queued when it started, so a steady stream cannot hold the
 * tick inside it.
 */
public final class ServerInboundQueue {

    private static final Logger log = LoggerFactory.getLogger(ServerInboundQueue.class);

    public enum Kind { CONNECT, PACKET, DISCONNECT }

    /**
     * One queued event. {@code packet} is set only for {@code PACKET}; {@code cause} only for
     * {@code DISCONNECT}. {@code receivedNanos} is the {@link System#nanoTime()} at which the
     * event-loop thread queued it — when the packet arrived, independent of how late the tick
     * thread drains it (a hitch on the tick thread must not bunch the arrival times handlers
     * measure intervals with).
     */
    public record Envelope(Kind kind, ServerConnection connection, Packet packet, Throwable cause,
                           long receivedNanos) {}

    private final Queue<Envelope> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger depth = new AtomicInteger();
    private final int readPauseDepth;
    private final int readResumeDepth;

    public ServerInboundQueue() {
        this(ServerLimits.DEFAULTS);
    }

    public ServerInboundQueue(ServerLimits limits) {
        this.readPauseDepth = limits.readPauseDepth();
        this.readResumeDepth = limits.readResumeDepth();
    }

    public void postConnect(ServerConnection c) {
        add(new Envelope(Kind.CONNECT, c, null, null, System.nanoTime()));
    }

    public void postPacket(ServerConnection c, Packet p) {
        c.onQueued(readPauseDepth);
        add(new Envelope(Kind.PACKET, c, p, null, System.nanoTime()));
    }

    public void postDisconnect(ServerConnection c, Throwable cause) {
        add(new Envelope(Kind.DISCONNECT, c, null, cause, System.nanoTime()));
    }

    private void add(Envelope e) {
        depth.incrementAndGet(); // counted first: a racing drain may see it before the add
        queue.add(e);
    }

    /** Envelopes waiting to be drained. */
    public int depth() {
        return depth.get();
    }

    /**
     * Drains the envelopes queued when the call started (not ones arriving meanwhile),
     * isolating handler errors per envelope.
     */
    public void drain(Consumer<Envelope> handler) {
        drain(handler, Integer.MAX_VALUE);
    }

    /**
     * Drains at most {@code maxEnvelopes} of the envelopes queued when the call started; the
     * rest wait for the next drain.
     */
    public void drain(Consumer<Envelope> handler, int maxEnvelopes) {
        int remaining = Math.min(maxEnvelopes, depth.get());
        Envelope e;
        while (remaining-- > 0 && (e = queue.poll()) != null) {
            depth.decrementAndGet();
            if (e.kind() == Kind.PACKET) {
                e.connection().onDequeued(readResumeDepth);
            }
            try {
                handler.accept(e);
            } catch (RuntimeException ex) {
                log.error("Error handling inbound {} from {}", e.kind(), e.connection(), ex);
            }
        }
    }
}
