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

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Client-side replication of non-player entities: creates "shadow" entities for inbound
 * spawns, feeds positions into a {@link NetworkInterpolator} so motion is smooth between
 * 20 Hz snapshots, and removes them on despawn. Successor of the old {@code EntitySynchronizer}
 * CLIENT path.
 */
public final class ClientEntityHandler {

    /** Network id → local shadow entity. Concurrent: session-end teardown can race the tick. */
    private final Map<Integer, Entity> byNetworkId = new ConcurrentHashMap<>();
    /** Spawns that arrived before the local world/entity manager was ready, plus any anim/
     *  variant state that arrived for them while buffered (last-wins per field — both packets
     *  carry absolute state, not deltas). */
    private final List<PendingSpawn> pendingSpawns = new ArrayList<>();

    private record PendingSpawn(EntitySpawnS2C spawn, String animState, String variant) {
        PendingSpawn(EntitySpawnS2C spawn) {
            this(spawn, null, null);
        }
        PendingSpawn withAnim(String animState) {
            return new PendingSpawn(spawn, animState, variant);
        }
        PendingSpawn withVariant(String variant) {
            return new PendingSpawn(spawn, animState, variant);
        }
        /** The entity moved while its spawn waited: spawn it where it is now. */
        PendingSpawn movedTo(float x, float y, float z, float yaw) {
            EntitySpawnS2C s = spawn;
            return new PendingSpawn(new EntitySpawnS2C(s.networkId(), s.entityTypeOrdinal(),
                    x, y, z, yaw, s.metadata()), animState, variant);
        }
    }

    /**
     * An absolute correction farther than this (blocks) is a real relocation — snap instead of
     * sliding the shadow across the gap (and 25 % past it) in one snapshot period.
     */
    private static final float TELEPORT_SNAP_DISTANCE = 4f;

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
        applyBuffered(new PendingSpawn(s));
    }

    private void applyBuffered(PendingSpawn p) {
        // isClientWorldReady (not bare null checks): during a world REBUILD (rejoin) the
        // old world/manager are still non-null — spawning into them orphans the shadow in
        // an entity manager that is about to be replaced (permanently invisible entity).
        if (!Game.isClientWorldReady()) {
            pendingSpawns.add(p);
            return;
        }
        EntitySpawnS2C s = p.spawn();
        Entity existing = byNetworkId.get(s.networkId());
        if (existing != null) {
            // Duplicate of a live shadow (interest flap while loading): forward any anim/
            // variant queued behind the buffered copy instead of discarding the record.
            applyQueuedState(existing, p);
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
        applyQueuedState(entity, p);
    }

    /** Applies a buffered record's queued anim/variant to a fresh shadow or a live duplicate. */
    private static void applyQueuedState(Entity e, PendingSpawn p) {
        if (p.animState() != null) {
            e.applyNetworkState(p.animState());
        }
        if (p.variant() != null && e instanceof com.stonebreak.mobs.entities.LivingEntity le) {
            le.setTextureVariant(p.variant());
        }
    }

    public void applyDespawn(int networkId) {
        // A despawn can arrive while its spawn is still buffered (world rebuild, or a fast
        // interest enter/exit flap) — drop the buffered spawn too or it would later create
        // a ghost the server considers gone.
        pendingSpawns.removeIf(p -> p.spawn().networkId() == networkId);
        Entity e = byNetworkId.remove(networkId);
        if (e != null && Game.getEntityManager() != null) {
            Game.getEntityManager().removeEntity(e);
        }
    }

    public void applyDelta(EntityMoveS2C m) {
        Entity e = byNetworkId.get(m.networkId());
        if (e == null) {
            // Still buffered (world build): fold the move into the waiting spawn — deltas are
            // relative to the server's broadcast base, which the spawn position seeded — so
            // the shadow appears where the mob is, not where it was when the build began.
            if (queueForPendingSpawn(m.networkId(), p -> p.movedTo(
                    p.spawn().x() + EntityDeltaCodec.decodePosDelta(m.dx()),
                    p.spawn().y() + EntityDeltaCodec.decodePosDelta(m.dy()),
                    p.spawn().z() + EntityDeltaCodec.decodePosDelta(m.dz()),
                    EntityDeltaCodec.decodeYawDeg(m.yawDeg10())))) {
                return;
            }
            if (Game.isClientWorldReady()) {
                noteUnknownMove();
            }
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
        Entity e = byNetworkId.get(t.networkId());
        if (e == null) {
            queueForPendingSpawn(t.networkId(), p -> p.movedTo(t.x(), t.y(), t.z(), t.yaw()));
            return;
        }
        NetworkInterpolator interp = e.getInterpolator();
        if (interp != null) {
            Vector3f cur = e.getPosition();
            float dx = t.x() - cur.x, dy = t.y() - cur.y, dz = t.z() - cur.z;
            if (dx * dx + dy * dy + dz * dz > TELEPORT_SNAP_DISTANCE * TELEPORT_SNAP_DISTANCE) {
                interp.seed(t.x(), t.y(), t.z(), t.yaw(), interp.currentTargetPitch());
                e.setPosition(t.x(), t.y(), t.z());
                e.setYaw(t.yaw());
                return;
            }
        }
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
            return;
        }
        // The spawn may still be buffered (world build) — keep the state for the drain.
        queueForPendingSpawn(networkId, p -> p.withAnim(sbeState));
    }

    /**
     * Applies the authoritative appearance variant of a replicated entity
     * (e.g. a sheared sheep's {@code "Sheared"} SBE variant). Idempotent —
     * the shearing client's predicted swap already set the same value, and
     * unknown variants fall back to the base model in the renderer.
     */
    public void applyVariant(int networkId, String variant) {
        if (variant != null && !variant.isBlank()) {
            Entity e = byNetworkId.get(networkId);
            if (e instanceof com.stonebreak.mobs.entities.LivingEntity le) {
                le.setTextureVariant(variant);
                return;
            }
            if (e == null) {
                // The spawn may still be buffered (world build) — keep the variant for the
                // drain; it does not self-heal (the periodic teleport resync resends anim
                // state but no variant).
                queueForPendingSpawn(networkId, p -> p.withVariant(variant));
            }
        }
    }

    /**
     * Replaces the first pending spawn for {@code networkId} with {@code update} applied;
     * false when no spawn for it is buffered.
     */
    private boolean queueForPendingSpawn(int networkId,
            Function<PendingSpawn, PendingSpawn> update) {
        ListIterator<PendingSpawn> lit = pendingSpawns.listIterator();
        while (lit.hasNext()) {
            PendingSpawn p = lit.next();
            if (p.spawn().networkId() == networkId) {
                lit.set(update.apply(p));
                return true;
            }
        }
        return false;
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
            e.setPosition(x, y, z);
            e.setYaw(yaw);
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
        // Drain a snapshot: the world-build thread can flip readiness between any two reads,
        // and applyBuffered re-buffers a record (with its queued anim/variant) when it does.
        // Re-buffering into the list being iterated threw ConcurrentModificationException.
        List<PendingSpawn> batch = new ArrayList<>(pendingSpawns);
        pendingSpawns.clear();
        for (PendingSpawn p : batch) {
            applyBuffered(p);
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
