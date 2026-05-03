package com.stonebreak.network.sync;

import com.stonebreak.network.protocol.Packet;

/**
 * Strategy for syncing one domain (blocks, chat, entities, player state).
 * Registered with {@link SyncService}; the service routes inbound packets and
 * outbound local events to the matching synchronizer.
 *
 * Implementations should be stateless or hold only their own domain state —
 * cross-domain coordination is the SyncService's job.
 */
public interface Synchronizer {

    /** Returns true if this synchronizer wants to handle the inbound packet. */
    boolean handlesInbound(Packet packet);

    /** Apply an inbound packet. {@code originId} is the sender's player id (host-side) or null (client-side). */
    void applyInbound(Packet packet, Integer originId, SyncContext ctx);

    /** Returns true if this synchronizer wants to react to the local event. */
    boolean handlesLocal(SyncEvent event);

    /** Process a local state change. */
    void emitLocal(SyncEvent event, SyncContext ctx);

    /** Per-tick hook for periodic broadcasts (e.g. player state at 20 Hz). */
    default void tick(float deltaTime, SyncContext ctx) {}

    /** Called when the session is ending; release any caches. */
    default void onSessionEnd() {}

    /** Called when a session begins; allocate session-scoped state. */
    default void onSessionStart(SyncContext ctx) {}
}
