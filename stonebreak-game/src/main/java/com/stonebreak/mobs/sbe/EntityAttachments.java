package com.stonebreak.mobs.sbe;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Runtime registry of models attached to entity sockets: which accessory asset
 * hangs on which named attachment point of which entity. Purely visual, local
 * state — attachments are neither persisted nor replicated (v1); the renderer
 * reads this each frame and poses attached models via
 * {@code SbePoseSolver.socketWorldMatrix}.
 *
 * <p>Keys are the entity objects themselves (or {@link #LOCAL_PLAYER} for the
 * local player, who is not an {@code Entity}); the backing {@link WeakHashMap}
 * lets despawned entities drop their attachments with them.
 */
public final class EntityAttachments {

    /** One attached model: the socket it hangs on and the accessory asset. */
    public record Attached(String socketName, SbeEntityAsset asset) {}

    /** Sentinel key for the local player (not an {@code Entity}). */
    public static final Object LOCAL_PLAYER = new Object();

    private static final Map<Object, List<Attached>> ATTACHMENTS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private EntityAttachments() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Attaches {@code asset} to {@code socketName} on the entity, replacing any
     * model already on that socket (matched case-insensitively).
     */
    public static void attach(Object entityKey, String socketName, SbeEntityAsset asset) {
        ATTACHMENTS.compute(entityKey, (k, existing) -> {
            List<Attached> next = new java.util.ArrayList<>();
            if (existing != null) {
                for (Attached a : existing) {
                    if (!a.socketName().equalsIgnoreCase(socketName)) {
                        next.add(a);
                    }
                }
            }
            next.add(new Attached(socketName, asset));
            return List.copyOf(next);
        });
    }

    /**
     * Detaches the model on {@code socketName} (case-insensitive), or every
     * attachment when {@code socketName} is null. Returns true if anything was
     * removed.
     */
    public static boolean detach(Object entityKey, String socketName) {
        boolean[] removed = {false};
        ATTACHMENTS.computeIfPresent(entityKey, (k, existing) -> {
            if (socketName == null) {
                removed[0] = !existing.isEmpty();
                return null;
            }
            List<Attached> next = new java.util.ArrayList<>();
            for (Attached a : existing) {
                if (a.socketName().equalsIgnoreCase(socketName)) {
                    removed[0] = true;
                } else {
                    next.add(a);
                }
            }
            return next.isEmpty() ? null : List.copyOf(next);
        });
        return removed[0];
    }

    /** The entity's attachments; the shared empty list when it has none. */
    public static List<Attached> get(Object entityKey) {
        List<Attached> list = ATTACHMENTS.get(entityKey);
        return list != null ? list : List.of();
    }
}
