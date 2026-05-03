package com.stonebreak.network.sync;

import com.stonebreak.network.protocol.Packet;

/**
 * Outbound surface area that synchronizers see. Hides whether we're a host or
 * client behind {@link #broadcast} / {@link #broadcastExcept} / {@link #send}.
 */
public interface SyncContext {

    SyncMode mode();

    /** Local player id. 0 for host, server-assigned for client, -1 if offline. */
    int localPlayerId();

    /** Send a packet to the server (client mode) or to all clients (host mode). */
    void broadcast(Packet packet);

    /** Host-only: send to everyone except {@code excludePlayerId}. No-op for clients. */
    void broadcastExcept(int excludePlayerId, Packet packet);

    /** Host-only: send to a single client. No-op for clients. */
    void sendTo(int playerId, Packet packet);

    /** True iff the in-flight inbound packet is being applied (used to suppress re-broadcast). */
    boolean isApplyingInbound();
}
