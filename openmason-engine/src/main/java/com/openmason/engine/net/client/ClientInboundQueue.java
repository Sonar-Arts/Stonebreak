package com.openmason.engine.net.client;

import com.openmason.engine.net.protocol.Packet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
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

    private final Queue<Event> queue = new ConcurrentLinkedQueue<>();

    public void postConnect() {
        queue.add(new Event(Kind.CONNECT, null, null));
    }

    public void postPacket(Packet p) {
        queue.add(new Event(Kind.PACKET, p, null));
    }

    public void postDisconnect(Throwable cause) {
        queue.add(new Event(Kind.DISCONNECT, null, cause));
    }

    /** Drain all currently-queued events, isolating handler errors per event. */
    public void drain(Consumer<Event> handler) {
        Event e;
        while ((e = queue.poll()) != null) {
            try {
                handler.accept(e);
            } catch (RuntimeException ex) {
                log.error("Error handling inbound {}", e.kind(), ex);
            }
        }
    }
}
