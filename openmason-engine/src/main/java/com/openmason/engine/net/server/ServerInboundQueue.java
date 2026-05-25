package com.openmason.engine.net.server;

import com.openmason.engine.net.protocol.Packet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Lock-free hand-off from Netty event-loop threads to the server tick thread. Connection
 * lifecycle (CONNECT/DISCONNECT) and PLAY packets travel as one ordered stream, so
 * join/leave and packet application all run on the tick thread — never on an event loop.
 */
public final class ServerInboundQueue {

    private static final Logger log = LoggerFactory.getLogger(ServerInboundQueue.class);

    public enum Kind { CONNECT, PACKET, DISCONNECT }

    /** One queued event. {@code packet} is set only for {@code PACKET}; {@code cause} only for {@code DISCONNECT}. */
    public record Envelope(Kind kind, ServerConnection connection, Packet packet, Throwable cause) {}

    private final Queue<Envelope> queue = new ConcurrentLinkedQueue<>();

    public void postConnect(ServerConnection c) {
        queue.add(new Envelope(Kind.CONNECT, c, null, null));
    }

    public void postPacket(ServerConnection c, Packet p) {
        queue.add(new Envelope(Kind.PACKET, c, p, null));
    }

    public void postDisconnect(ServerConnection c, Throwable cause) {
        queue.add(new Envelope(Kind.DISCONNECT, c, null, cause));
    }

    /** Drain all currently-queued envelopes, isolating handler errors per envelope. */
    public void drain(Consumer<Envelope> handler) {
        Envelope e;
        while ((e = queue.poll()) != null) {
            try {
                handler.accept(e);
            } catch (RuntimeException ex) {
                log.error("Error handling inbound {} from {}", e.kind(), e.connection(), ex);
            }
        }
    }
}
