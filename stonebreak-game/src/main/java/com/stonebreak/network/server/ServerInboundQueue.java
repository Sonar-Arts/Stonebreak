package com.stonebreak.network.server;

import com.stonebreak.network.protocol.Packet;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;

/**
 * Server-side inbound queue. Each entry carries the originating client's id
 * alongside the packet, so receivers don't need to do an after-the-fact lookup
 * (which the previous map-keyed-by-packet design leaked).
 *
 * <p>Reader threads call {@link #post}; the host's main thread drains via
 * {@link #drain} once per tick.
 */
public final class ServerInboundQueue {

    public record Envelope(Packet packet, int originId) {}

    private final ConcurrentLinkedQueue<Envelope> queue = new ConcurrentLinkedQueue<>();

    public void post(Packet packet, int originId) {
        queue.add(new Envelope(packet, originId));
    }

    public void drain(BiConsumer<Packet, Integer> handler) {
        Envelope e;
        while ((e = queue.poll()) != null) {
            try {
                handler.accept(e.packet(), e.originId());
            } catch (Exception ex) {
                System.err.println("[NETWORK] Error handling "
                        + e.packet().getClass().getSimpleName() + " from " + e.originId() + ": " + ex);
                ex.printStackTrace();
            }
        }
    }

    public void clear() {
        queue.clear();
    }
}
