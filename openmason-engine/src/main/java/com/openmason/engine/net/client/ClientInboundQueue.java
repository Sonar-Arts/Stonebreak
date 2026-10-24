package com.openmason.engine.net.client;

import com.openmason.engine.net.protocol.Packet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Lock-free hand-off from the client's Netty event-loop thread to the game thread.
 * Connection lifecycle and CLIENTBOUND packets travel as one ordered stream, drained once
 * per frame by the client world view.
 */
public final class ClientInboundQueue {

    private static final Logger log = LoggerFactory.getLogger(ClientInboundQueue.class);

    public enum Kind { CONNECT, PACKET, DISCONNECT }

    /** One queued event. {@code packet} is set only for {@code PACKET}; {@code cause} only for {@code DISCONNECT}. */
    public record Event(Kind kind, Packet packet, Throwable cause) {}

    /**
     * Backlog at which reading from the network pauses (the game thread is not keeping up —
     * a stall, a minimised window) and the backlog it resumes at. Without this the queue grew
     * without bound and the whole backlog was applied in one frame.
     */
    static final int READ_PAUSE_DEPTH = 8192;
    static final int READ_RESUME_DEPTH = 2048;

    private final Queue<Event> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger depth = new AtomicInteger();
    /** Turns network reads on/off (the channel's autoRead); no-op until a channel is bound. */
    private volatile java.util.function.Consumer<Boolean> readGate = on -> { };
    private volatile boolean readPaused;

    /** Binds the gate that pauses/resumes network reads under backlog (channel autoRead). */
    public void setReadGate(java.util.function.Consumer<Boolean> gate) {
        this.readGate = gate == null ? on -> { } : gate;
    }

    public void postConnect() {
        add(new Event(Kind.CONNECT, null, null));
    }

    public void postPacket(Packet p) {
        add(new Event(Kind.PACKET, p, null));
    }

    public void postDisconnect(Throwable cause) {
        add(new Event(Kind.DISCONNECT, null, cause));
    }

    private void add(Event e) {
        queue.add(e);
        if (depth.incrementAndGet() >= READ_PAUSE_DEPTH && !readPaused) {
            readPaused = true;
            readGate.accept(false);
        }
    }

    /** Events waiting to be drained. */
    public int depth() {
        return depth.get();
    }

    /** Drain all currently-queued events, isolating handler errors per event. */
    public void drain(Consumer<Event> handler) {
        drain(handler, Long.MAX_VALUE);
    }

    /**
     * Drains events in order until the queue is empty or {@code budgetNanos} has been spent
     * (at least one event is always handled); the rest wait for the next call. Keeps a
     * backlog — after a stall — from being applied in one frame.
     */
    public void drain(Consumer<Event> handler, long budgetNanos) {
        long start = System.nanoTime();
        Event e;
        while ((e = queue.poll()) != null) {
            depth.decrementAndGet();
            try {
                handler.accept(e);
            } catch (RuntimeException ex) {
                log.error("Error handling inbound {}", e.kind(), ex);
            }
            if (System.nanoTime() - start >= budgetNanos) {
                break;
            }
        }
        if (readPaused && depth.get() <= READ_RESUME_DEPTH) {
            readPaused = false;
            readGate.accept(true);
        }
    }
}
