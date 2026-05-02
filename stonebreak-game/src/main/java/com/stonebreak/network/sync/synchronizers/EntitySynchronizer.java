package com.stonebreak.network.sync.synchronizers;

import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.RemotePlayer;
import com.stonebreak.mobs.cow.Cow;
import com.stonebreak.network.protocol.Packet;
import com.stonebreak.network.sync.EntityDeltaCodec;
import com.stonebreak.network.sync.NetworkInterpolator;
import com.stonebreak.network.sync.SyncContext;
import com.stonebreak.network.sync.SyncEvent;
import com.stonebreak.network.sync.SyncMode;
import com.stonebreak.network.sync.Synchronizer;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Replicates non-player entities (cows, future mobs) host → clients.
 *
 * <ul>
 *   <li>Host: assigns network ids, broadcasts spawn/despawn, and emits
 *       compact {@link Packet.EntityMoveS2C} deltas (or
 *       {@link Packet.EntityTeleportS2C} on big jumps) every server tick
 *       for entities that actually moved.</li>
 *   <li>Client: creates "shadow" entities, feeds positions into a
 *       {@link NetworkInterpolator} so render-time motion is smooth between
 *       20 Hz snapshots.</li>
 * </ul>
 */
public final class EntitySynchronizer implements Synchronizer {

    private static final float MIN_BROADCAST_DELTA = 0.005f;
    private static final float MIN_BROADCAST_YAW_DEG = 0.5f;

    private final AtomicInteger nextNetworkId = new AtomicInteger(1);
    /** Network id → local Entity (host: tracked entities; client: shadow entities). */
    private final Map<Integer, Entity> byNetworkId = new HashMap<>();
    /** Host: last absolute position+yaw broadcast for each entity (for delta calc). */
    private final Map<Integer, float[]> lastBroadcast = new HashMap<>();
    /** Client: spawn packets that arrived before the world was ready. */
    private final Deque<Packet.EntitySpawnS2C> pendingSpawns = new ArrayDeque<>();

    @Override
    public void onSessionStart(SyncContext ctx) {
        nextNetworkId.set(1);
        byNetworkId.clear();
        lastBroadcast.clear();

        // Host: snapshot existing entities so they're tracked + can be replicated.
        if (ctx.mode() == SyncMode.HOST && Game.getEntityManager() != null) {
            for (Entity e : Game.getEntityManager().getAllEntities()) {
                if (isReplicable(e)) registerHostEntity(e);
            }
        }
    }

    @Override
    public void onSessionEnd() {
        EntityManager em = Game.getEntityManager();
        if (em != null) {
            for (Entity e : byNetworkId.values()) {
                if (e.isNetworkShadow()) em.removeEntity(e);
            }
        }
        byNetworkId.clear();
        lastBroadcast.clear();
        pendingSpawns.clear();
    }

    @Override
    public boolean handlesInbound(Packet packet) {
        return packet instanceof Packet.EntitySpawnS2C
                || packet instanceof Packet.EntityDespawnS2C
                || packet instanceof Packet.EntityStateS2C
                || packet instanceof Packet.EntityMoveS2C
                || packet instanceof Packet.EntityTeleportS2C;
    }

    @Override
    public void applyInbound(Packet packet, Integer originId, SyncContext ctx) {
        switch (packet) {
            case Packet.EntitySpawnS2C s -> applySpawn(s);
            case Packet.EntityDespawnS2C d -> applyDespawn(d.networkId());
            case Packet.EntityStateS2C s -> applyAbsolute(s.networkId(), s.x(), s.y(), s.z(), s.yaw());
            case Packet.EntityTeleportS2C t -> applyAbsolute(t.networkId(), t.x(), t.y(), t.z(), t.yaw());
            case Packet.EntityMoveS2C m -> applyDelta(m);
            default -> {}
        }
    }

    @Override
    public boolean handlesLocal(SyncEvent event) {
        return event instanceof SyncEvent.EntitySpawned
                || event instanceof SyncEvent.EntityDespawned
                || event instanceof SyncEvent.PeerJoined;
    }

    @Override
    public void emitLocal(SyncEvent event, SyncContext ctx) {
        if (ctx.mode() != SyncMode.HOST) return;
        switch (event) {
            case SyncEvent.EntitySpawned es -> {
                Entity e = es.entity();
                if (!isReplicable(e)) return;
                registerHostEntity(e);
                ctx.broadcast(spawnPacketFor(e));
            }
            case SyncEvent.EntityDespawned ed -> {
                Entity e = ed.entity();
                if (e.getNetworkId() < 0) return;
                byNetworkId.remove(e.getNetworkId());
                lastBroadcast.remove(e.getNetworkId());
                ctx.broadcast(new Packet.EntityDespawnS2C(e.getNetworkId()));
            }
            case SyncEvent.PeerJoined pj -> sendSnapshotTo(pj.playerId(), ctx);
            default -> {}
        }
    }

    @Override
    public void tick(float deltaTime, SyncContext ctx) {
        // Drain queued spawn packets once the local world is ready (client-side).
        if (!pendingSpawns.isEmpty() && Game.getWorld() != null && Game.getEntityManager() != null) {
            Iterator<Packet.EntitySpawnS2C> it = pendingSpawns.iterator();
            while (it.hasNext()) { applySpawn(it.next()); it.remove(); }
        }
        if (ctx.mode() != SyncMode.HOST) return;

        for (Entity e : byNetworkId.values()) {
            if (!e.isAlive()) continue;
            int id = e.getNetworkId();
            float[] last = lastBroadcast.get(id);
            Vector3f p = e.getPosition();
            float yaw = e.getRotation().y;

            float dx = p.x - last[0];
            float dy = p.y - last[1];
            float dz = p.z - last[2];
            float dyaw = Math.abs(yaw - last[3]);

            boolean posMoved = Math.abs(dx) >= MIN_BROADCAST_DELTA
                            || Math.abs(dy) >= MIN_BROADCAST_DELTA
                            || Math.abs(dz) >= MIN_BROADCAST_DELTA;
            boolean rotMoved = dyaw >= MIN_BROADCAST_YAW_DEG;
            if (!posMoved && !rotMoved) continue;

            if (EntityDeltaCodec.fitsInDelta(dx, dy, dz)) {
                short edx = EntityDeltaCodec.encodePosDelta(dx);
                short edy = EntityDeltaCodec.encodePosDelta(dy);
                short edz = EntityDeltaCodec.encodePosDelta(dz);
                short eyaw = EntityDeltaCodec.encodeYawDeg(yaw);
                ctx.broadcast(new Packet.EntityMoveS2C(id, edx, edy, edz, eyaw));
                // Track exactly what we sent (decoded back) so successive deltas
                // don't accumulate floating-point drift.
                last[0] += EntityDeltaCodec.decodePosDelta(edx);
                last[1] += EntityDeltaCodec.decodePosDelta(edy);
                last[2] += EntityDeltaCodec.decodePosDelta(edz);
                last[3] = EntityDeltaCodec.decodeYawDeg(eyaw);
            } else {
                ctx.broadcast(new Packet.EntityTeleportS2C(id, p.x, p.y, p.z, yaw));
                last[0] = p.x; last[1] = p.y; last[2] = p.z; last[3] = yaw;
            }
        }
    }

    /** Send the full spawn snapshot of every replicable entity to one client. Used on join. */
    public void sendSnapshotTo(int playerId, SyncContext ctx) {
        if (ctx.mode() != SyncMode.HOST) return;
        for (Entity e : byNetworkId.values()) {
            ctx.sendTo(playerId, spawnPacketFor(e));
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private void registerHostEntity(Entity e) {
        if (e.getNetworkId() < 0) e.setNetworkId(nextNetworkId.getAndIncrement());
        byNetworkId.put(e.getNetworkId(), e);
        Vector3f p = e.getPosition();
        lastBroadcast.put(e.getNetworkId(), new float[]{p.x, p.y, p.z, e.getRotation().y});
    }

    private static boolean isReplicable(Entity e) {
        if (e == null || e instanceof RemotePlayer) return false;
        EntityType t = e.getType();
        return t == EntityType.COW; // v1: only mobs replicated
    }

    private static Packet.EntitySpawnS2C spawnPacketFor(Entity e) {
        Vector3f p = e.getPosition();
        String metadata = "";
        if (e instanceof Cow cow) metadata = cow.getTextureVariant();
        return new Packet.EntitySpawnS2C(
                e.getNetworkId(), e.getType().ordinal(),
                p.x, p.y, p.z, e.getRotation().y, metadata);
    }

    private void applySpawn(Packet.EntitySpawnS2C s) {
        if (Game.getWorld() == null || Game.getEntityManager() == null) {
            pendingSpawns.add(s);
            return;
        }
        if (byNetworkId.containsKey(s.networkId())) return;
        EntityType type = EntityType.values()[s.entityTypeOrdinal()];
        Entity entity = createShadow(type, new Vector3f(s.x(), s.y(), s.z()), s.metadata());
        if (entity == null) return;
        entity.setNetworkId(s.networkId());
        entity.setNetworkShadow(true);
        entity.setRotation(new Vector3f(0f, s.yaw(), 0f));
        NetworkInterpolator interp = new NetworkInterpolator();
        interp.seed(s.x(), s.y(), s.z(), s.yaw(), 0f);
        entity.setInterpolator(interp);
        Game.getEntityManager().addEntity(entity);
        byNetworkId.put(s.networkId(), entity);
    }

    private void applyDespawn(int networkId) {
        Entity e = byNetworkId.remove(networkId);
        if (e != null && Game.getEntityManager() != null) Game.getEntityManager().removeEntity(e);
    }

    private void applyAbsolute(int networkId, float x, float y, float z, float yaw) {
        Entity e = byNetworkId.get(networkId);
        if (e == null) return;
        NetworkInterpolator interp = e.getInterpolator();
        if (interp != null) {
            interp.receive(x, y, z, yaw, e);
        } else {
            e.setPosition(new Vector3f(x, y, z));
            e.setRotation(new Vector3f(e.getRotation().x, yaw, e.getRotation().z));
        }
    }

    private void applyDelta(Packet.EntityMoveS2C m) {
        Entity e = byNetworkId.get(m.networkId());
        if (e == null) return;
        NetworkInterpolator interp = e.getInterpolator();
        // Deltas are relative to the entity's last known *target* position
        // (what the host last broadcast), not the displayed-and-interpolated
        // position. We rebase from the interpolator's target so deltas don't
        // smear between two clients with mismatched display states.
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

    private static Entity createShadow(EntityType type, Vector3f pos, String metadata) {
        return switch (type) {
            case COW -> {
                String variant = (metadata == null || metadata.isBlank()) ? "default" : metadata;
                yield new Cow(Game.getWorld(), pos, variant);
            }
            default -> null;
        };
    }
}
