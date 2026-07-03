package com.stonebreak.network.client.handlers;

import com.openmason.engine.net.protocol.codec.EntityDeltaCodec;
import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.network.client.NetworkInterpolator;
import com.stonebreak.network.packet.entity.EntityDespawnS2C;
import com.stonebreak.network.packet.entity.EntityMoveS2C;
import com.stonebreak.network.packet.entity.EntitySpawnS2C;
import com.stonebreak.network.packet.entity.EntityTeleportS2C;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side replication of non-player entities: creates "shadow" entities for inbound
 * spawns, feeds positions into a {@link NetworkInterpolator} so motion is smooth between
 * 20 Hz snapshots, and removes them on despawn. Successor of the old {@code EntitySynchronizer}
 * CLIENT path.
 */
public final class ClientEntityHandler {

    /** Network id → local shadow entity. Concurrent: session-end teardown can race the tick. */
    private final Map<Integer, Entity> byNetworkId = new ConcurrentHashMap<>();
    /** Spawn packets that arrived before the local world/entity manager was ready. */
    private final Deque<EntitySpawnS2C> pendingSpawns = new ArrayDeque<>();

    /** Moves for unknown ids in the current window — a sustained burst means the shadow map
     *  diverged (a spawn was somehow lost), so ask for a full entity resync. Occasional
     *  strays are normal (a despawn racing in-flight moves). */
    private static final int UNKNOWN_MOVE_RESYNC_THRESHOLD = 20;
    private static final long UNKNOWN_MOVE_WINDOW_NS = 5_000_000_000L; // 5 s
    private int unknownMoveCount = 0;
    private long unknownMoveWindowStartNs = 0L;

    private void noteUnknownMove() {
        long now = System.nanoTime();
        if (now - unknownMoveWindowStartNs > UNKNOWN_MOVE_WINDOW_NS) {
            unknownMoveWindowStartNs = now;
            unknownMoveCount = 0;
        }
        if (++unknownMoveCount == UNKNOWN_MOVE_RESYNC_THRESHOLD) {
            com.stonebreak.network.MultiplayerSession.requestEntityResync();
        }
    }

    public void applySpawn(EntitySpawnS2C s) {
        // isClientWorldReady (not bare null checks): during a world REBUILD (rejoin) the
        // old world/manager are still non-null — spawning into them orphans the shadow in
        // an entity manager that is about to be replaced (permanently invisible entity).
        if (!Game.isClientWorldReady()) {
            pendingSpawns.add(s);
            return;
        }
        if (byNetworkId.containsKey(s.networkId())) {
            return;
        }
        EntityType[] types = EntityType.values();
        if (s.entityTypeOrdinal() < 0 || s.entityTypeOrdinal() >= types.length) {
            // Malformed/mis-decoded spawn (e.g. around channel teardown) — never let it
            // throw out of the main-thread dispatch.
            System.err.println("[CLIENT-ENTITY] Spawn with invalid type ordinal "
                + s.entityTypeOrdinal() + " (netId=" + s.networkId() + ") — ignored.");
            return;
        }
        EntityType type = types[s.entityTypeOrdinal()];
        Entity entity = createShadow(type, new Vector3f(s.x(), s.y(), s.z()), s.metadata());
        if (entity == null) {
            // A replicated type without a shadow factory is a wiring bug, not a normal case —
            // this entity would silently never exist on this client.
            System.err.println("[CLIENT-ENTITY] No shadow factory for replicated type " + type
                + " (netId=" + s.networkId() + ") — entity will be invisible on this client!");
            return;
        }
        if (type == EntityType.BLOCK_DROP || type == EntityType.ITEM_DROP) {
            // Client half of the drop-replication diagnosis (pairs with the server line).
            System.out.printf("[CLIENT-ENTITY] Drop shadow %s netId=%d at (%.1f, %.1f, %.1f)%n",
                type, s.networkId(), s.x(), s.y(), s.z());
        }
        entity.setNetworkId(s.networkId());
        entity.setNetworkShadow(true);
        entity.setRotation(new Vector3f(0f, s.yaw(), 0f));
        NetworkInterpolator interp = new NetworkInterpolator();
        interp.seed(s.x(), s.y(), s.z(), s.yaw(), 0f);
        entity.setInterpolator(interp);
        Game.getEntityManager().addEntity(entity);
        byNetworkId.put(s.networkId(), entity);
    }

    public void applyDespawn(int networkId) {
        // A despawn can arrive while its spawn is still buffered (world rebuild, or a fast
        // interest enter/exit flap) — drop the buffered spawn too or it would later create
        // a ghost the server considers gone.
        pendingSpawns.removeIf(s -> s.networkId() == networkId);
        Entity e = byNetworkId.remove(networkId);
        if (e != null && Game.getEntityManager() != null) {
            Game.getEntityManager().removeEntity(e);
        }
    }

    public void applyDelta(EntityMoveS2C m) {
        // While the world is rebuilding, spawns sit buffered in pendingSpawns — every move
        // would look like an "unknown id" and trip the resync heuristic for nothing. Drop
        // them; the ≤2 s periodic teleport rebases the shadow once the spawn drains.
        if (!Game.isClientWorldReady()) {
            return;
        }
        Entity e = byNetworkId.get(m.networkId());
        if (e == null) {
            noteUnknownMove();
            return;
        }
        NetworkInterpolator interp = e.getInterpolator();
        // Deltas rebase from the last broadcast target (not the displayed position) so they
        // don't smear between clients with mismatched display states.
        float baseX, baseY, baseZ;
        if (interp != null) {
            baseX = interp.targetX(); baseY = interp.targetY(); baseZ = interp.targetZ();
        } else {
            Vector3f p = e.getPosition();
            baseX = p.x; baseY = p.y; baseZ = p.z;
        }
        float nx = baseX + EntityDeltaCodec.decodePosDelta(m.dx());
        float ny = baseY + EntityDeltaCodec.decodePosDelta(m.dy());
        float nz = baseZ + EntityDeltaCodec.decodePosDelta(m.dz());
        float yaw = EntityDeltaCodec.decodeYawDeg(m.yawDeg10());
        applyAbsolute(m.networkId(), nx, ny, nz, yaw);
    }

    public void applyTeleport(EntityTeleportS2C t) {
        applyAbsolute(t.networkId(), t.x(), t.y(), t.z(), t.yaw());
    }

    /**
     * Apply a server-replicated animation/behavior state to a shadow entity. The shadow's AI is
     * otherwise frozen (shadows skip local update), so this is what makes its rendered animation,
     * debug wireframe, and debug overlay track the authoritative server state.
     */
    public void applyAnim(int networkId, String sbeState) {
        Entity e = byNetworkId.get(networkId);
        if (e != null) {
            e.applyNetworkState(sbeState);
        }
    }

    public void applyAbsolute(int networkId, float x, float y, float z, float yaw) {
        Entity e = byNetworkId.get(networkId);
        if (e == null) {
            return;
        }
        NetworkInterpolator interp = e.getInterpolator();
        if (interp != null) {
            interp.receive(x, y, z, yaw, e);
        } else {
            e.setPosition(new Vector3f(x, y, z));
            e.setRotation(new Vector3f(e.getRotation().x, yaw, e.getRotation().z));
        }
    }

    /** Number of live replicated entity shadows this client tracks (debug overlay). */
    public int trackedShadowCount() {
        return byNetworkId.size();
    }

    public void tick() {
        if (pendingSpawns.isEmpty() || !Game.isClientWorldReady()) {
            return;
        }
        Iterator<EntitySpawnS2C> it = pendingSpawns.iterator();
        while (it.hasNext()) {
            applySpawn(it.next());
            it.remove();
        }
    }

    public void onSessionEnd() {
        EntityManager em = Game.getEntityManager();
        if (em != null) {
            for (Entity e : byNetworkId.values()) {
                if (e.isNetworkShadow()) {
                    em.removeEntity(e);
                }
            }
        }
        byNetworkId.clear();
        pendingSpawns.clear();
    }

    // ─── Shadow construction ──────────────────────────────────────────────────

    private static Entity createShadow(EntityType type, Vector3f pos, String metadata) {
        return com.stonebreak.network.EntityReplicationRegistry.createShadow(
            type, Game.getWorld(), pos, metadata);
    }
}
