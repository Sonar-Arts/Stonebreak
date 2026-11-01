package com.openmason.engine.net.server;

import com.openmason.engine.net.protocol.Packet;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe set of active connections with broadcast helpers. The set is mutated on
 * event-loop threads (connect/disconnect) and read on the tick thread (broadcast), so a
 * concurrent set keeps both safe without locking.
 *
 * <p>Unicast is {@link ServerConnection#send} directly; this type covers the fan-out cases.
 */
public final class ConnectionRegistry {

    private final Set<ServerConnection> connections = ConcurrentHashMap.newKeySet();
    /**
     * Which connections broadcasts reach. The game admits a connection once its handshake is
     * accepted: one still handshaking is not in the world, and must not get world traffic.
     */
    private volatile java.util.function.Predicate<ServerConnection> broadcastFilter = c -> true;

    /** Sets which connections {@link #broadcast} and {@link #broadcastExcept} reach. */
    public void setBroadcastFilter(java.util.function.Predicate<ServerConnection> filter) {
        this.broadcastFilter = filter == null ? c -> true : filter;
    }

    /** Whether broadcasts reach {@code c} (see {@link #setBroadcastFilter}). */
    public boolean receivesBroadcasts(ServerConnection c) {
        return broadcastFilter.test(c);
    }

    public void add(ServerConnection c) {
        connections.add(c);
    }

    public void remove(ServerConnection c) {
        connections.remove(c);
    }

    public int size() {
        return connections.size();
    }

    /** Live view of the active connections (safe to iterate concurrently). */
    public Collection<ServerConnection> all() {
        return connections;
    }

    public void broadcast(Packet packet, boolean droppable) {
        java.util.function.Predicate<ServerConnection> filter = broadcastFilter;
        for (ServerConnection c : connections) {
            if (filter.test(c)) {
                c.send(packet, droppable);
            }
        }
    }

    public void broadcast(Packet packet) {
        broadcast(packet, false);
    }

    public void broadcastExcept(ServerConnection except, Packet packet, boolean droppable) {
        java.util.function.Predicate<ServerConnection> filter = broadcastFilter;
        for (ServerConnection c : connections) {
            if (c != except && filter.test(c)) {
                c.send(packet, droppable);
            }
        }
    }

    public void broadcastExcept(ServerConnection except, Packet packet) {
        broadcastExcept(except, packet, false);
    }
}
