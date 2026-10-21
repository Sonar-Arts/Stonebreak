package com.stonebreak.network;

import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.network.server.IntegratedServer;
import java.util.function.Supplier;

/**
 * Bridges authoritative-world EntityManager lifecycle events to the integrated server's entity
 * replication. Deliberately NO battletest gate: the listener is structurally bound to the saved
 * world's EntityManager at attach time and the battle-test arena has its own EntityManager that
 * never fires it, so every callback is a legitimate saved-world replication event even when the
 * battletest flag flips mid-server-tick. (The old time-of-callback {@code isActive()} check
 * dropped that straddling tick's spawn/despawn — issue #375.)
 *
 * <p>Side effect of the missing gate: spawn/despawn packets may be emitted while an arena session
 * is active, but battletest is singleplayer-only and the client pump is suspended then, so the
 * packets queue in the local channel and are applied on resume — none are lost.
 */
public final class EntityReplicationListener implements EntityManager.Listener {

    /** Supplier, not a direct reference: shutdown detaches the listener and then nulls the
     *  server field, so a callback already in flight on another thread reads the field when
     *  it fires and becomes a no-op instead of reaching a torn-down server. */
    private final Supplier<IntegratedServer> server;

    public EntityReplicationListener(Supplier<IntegratedServer> server) {
        this.server = server;
    }

    @Override
    public void onEntityAdded(Entity e) {
        IntegratedServer s = server.get();
        if (s != null) {
            s.onEntitySpawned(e);
        }
    }

    @Override
    public void onEntityRemoved(Entity e) {
        IntegratedServer s = server.get();
        if (s != null) {
            s.onEntityDespawned(e);
        }
    }
}
