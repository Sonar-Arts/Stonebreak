package com.stonebreak.network.sync;

import com.stonebreak.network.protocol.Packet;

import java.util.ArrayList;
import java.util.List;

/**
 * Single source of truth for multiplayer synchronization. All inbound packets
 * and local state-change events flow through this service, which dispatches to
 * registered {@link Synchronizer} strategies.
 *
 * <p>Lifetime: one global instance owned by {@code MultiplayerSession}; cleared
 * when the session ends.
 */
public final class SyncService {

    private final List<Synchronizer> synchronizers = new ArrayList<>();
    private SyncContext context;
    private final ThreadLocal<Boolean> applyingInbound = ThreadLocal.withInitial(() -> false);

    public void register(Synchronizer s) {
        synchronizers.add(s);
    }

    public List<Synchronizer> registered() {
        return synchronizers;
    }

    /** Bind a context (host or client) and notify all synchronizers. */
    public void start(SyncContext ctx) {
        this.context = ctx;
        for (Synchronizer s : synchronizers) {
            s.onSessionStart(ctx);
        }
    }

    public void stop() {
        for (Synchronizer s : synchronizers) {
            try { s.onSessionEnd(); } catch (Exception e) {
                System.err.println("[SYNC] " + s.getClass().getSimpleName() + " onSessionEnd threw: " + e);
            }
        }
        this.context = null;
    }

    public boolean isActive() { return context != null; }

    public boolean isApplyingInbound() { return applyingInbound.get(); }

    /** Route an inbound packet through every synchronizer that wants it. */
    public void onInbound(Packet packet, Integer originId) {
        if (context == null) return;
        applyingInbound.set(true);
        try {
            for (Synchronizer s : synchronizers) {
                if (s.handlesInbound(packet)) {
                    try { s.applyInbound(packet, originId, context); }
                    catch (Exception e) {
                        System.err.println("[SYNC] " + s.getClass().getSimpleName()
                                + " failed on " + packet.getClass().getSimpleName() + ": " + e);
                        e.printStackTrace();
                    }
                }
            }
        } finally {
            applyingInbound.set(false);
        }
    }

    /** Route a local event through every synchronizer that wants it. */
    public void notifyLocal(SyncEvent event) {
        if (context == null) return;
        if (applyingInbound.get()) return; // suppress feedback loops
        dispatchLocal(event);
    }

    /**
     * Like {@link #notifyLocal(SyncEvent)} but ignores the
     * {@code applyingInbound} flag. Used for events that must propagate even
     * during inbound packet application — e.g. when the host applies an
     * inbound block-break that spawns a drop entity, the drop spawn must
     * still be broadcast to clients. Never call this for {@link SyncEvent.BlockChanged}
     * during inbound block-change application — that would cause re-broadcast loops.
     */
    public void notifyLocalForced(SyncEvent event) {
        if (context == null) return;
        dispatchLocal(event);
    }

    private void dispatchLocal(SyncEvent event) {
        for (Synchronizer s : synchronizers) {
            if (s.handlesLocal(event)) {
                try { s.emitLocal(event, context); }
                catch (Exception e) {
                    System.err.println("[SYNC] " + s.getClass().getSimpleName()
                            + " failed on " + event.getClass().getSimpleName() + ": " + e);
                    e.printStackTrace();
                }
            }
        }
    }

    public void tick(float deltaTime) {
        if (context == null) return;
        for (Synchronizer s : synchronizers) {
            try { s.tick(deltaTime, context); }
            catch (Exception e) {
                System.err.println("[SYNC] " + s.getClass().getSimpleName() + " tick threw: " + e);
            }
        }
    }
}
