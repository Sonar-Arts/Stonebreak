package com.stonebreak.network.client;

import com.stonebreak.network.protocol.Packet;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Thread-safe inbound packet queue. Network reader threads call {@link #post};
 * the main game thread drains via {@link #drain} once per tick so that all
 * world/entity mutation happens on the main thread.
 */
public final class NetworkEventBus {

    private final ConcurrentLinkedQueue<Packet> queue = new ConcurrentLinkedQueue<>();

    public void post(Packet packet) {
        queue.add(packet);
    }

    public void drain(Consumer<Packet> handler) {
        Packet p;
        while ((p = queue.poll()) != null) {
            try {
                handler.accept(p);
            } catch (Exception e) {
                System.err.println("[NETWORK] Error handling packet " + p.getClass().getSimpleName() + ": " + e);
                e.printStackTrace();
            }
        }
    }

    public void clear() {
        queue.clear();
    }
}
