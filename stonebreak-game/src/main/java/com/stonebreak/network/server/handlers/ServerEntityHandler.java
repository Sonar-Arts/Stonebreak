package com.stonebreak.network.server.handlers;

import com.openmason.engine.net.protocol.codec.EntityDeltaCodec;
import com.stonebreak.mobs.cow.Cow;
import com.stonebreak.mobs.entities.BlockDrop;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.ItemDrop;
import com.stonebreak.mobs.entities.RemotePlayer;
import com.stonebreak.items.ItemStack;
import com.stonebreak.network.packet.entity.EntityDespawnS2C;
import com.stonebreak.network.packet.entity.EntityMoveS2C;
import com.stonebreak.network.packet.entity.EntitySpawnS2C;
import com.stonebreak.network.packet.entity.EntityTeleportS2C;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;
import org.joml.Vector3f;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Replicates non-player entities (cows, chickens, drops) to clients — the authoritative
 * successor of the old {@code EntitySynchronizer} HOST path. Assigns network ids,
 * broadcasts spawn/despawn (driven by the EntityManager listener wired in the lifecycle
 * phase), and emits compact {@link EntityMoveS2C} deltas (or {@link EntityTeleportS2C} on
 * big jumps / periodic resync) each server tick for entities that moved.
 *
 * <p>The client-side shadow creation + interpolation lives in the client world view, not
 * here.
 */
public final class ServerEntityHandler {

    private static final float MIN_BROADCAST_DELTA = 0.005f;
    private static final float MIN_BROADCAST_YAW_DEG = 0.5f;
    /** Force an absolute teleport every N ticks per entity to bound drift from lost deltas. */
    private static final int RESYNC_PERIOD_TICKS = 40; // 40 * 50ms = 2 s

    private final AtomicInteger nextNetworkId = new AtomicInteger(1);
    private final Map<Integer, Entity> byNetworkId = new ConcurrentHashMap<>();
    private final Map<Integer, float[]> lastBroadcast = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> ticksSinceResync = new ConcurrentHashMap<>();

    /** Reset, then snapshot existing entities so they're tracked + replicable. */
    public void onSessionStart(ServerWorldContext ctx) {
        nextNetworkId.set(1);
        byNetworkId.clear();
        lastBroadcast.clear();
        ticksSinceResync.clear();
        EntityManager em = ctx.entityManager();
        if (em != null) {
            for (Entity e : em.getAllEntities()) {
                if (isReplicable(e)) {
                    registerHostEntity(e);
                }
            }
        }
    }

    public void onSessionEnd() {
        byNetworkId.clear();
        lastBroadcast.clear();
        ticksSinceResync.clear();
    }

    /** EntityManager listener hook: a new entity was added to the authoritative world. */
    public void onEntitySpawned(Entity e, ServerWorldContext ctx) {
        if (!isReplicable(e)) {
            return;
        }
        registerHostEntity(e);
        ctx.broadcast(spawnPacketFor(e), false);
    }

    /** EntityManager listener hook: an entity was removed from the authoritative world. */
    public void onEntityDespawned(Entity e, ServerWorldContext ctx) {
        int id = e.getNetworkId();
        if (id < 0) {
            return;
        }
        byNetworkId.remove(id);
        lastBroadcast.remove(id);
        ticksSinceResync.remove(id);
        ctx.broadcast(new EntityDespawnS2C(id), false);
    }

    /** Send the full spawn snapshot of every tracked entity to one joining player. */
    public void onPeerJoined(ServerPlayer sp) {
        for (Entity e : byNetworkId.values()) {
            sp.send(spawnPacketFor(e), false);
        }
    }

    public void tick(ServerWorldContext ctx) {
        for (Entity e : byNetworkId.values()) {
            if (!e.isAlive()) {
                continue;
            }
            int id = e.getNetworkId();
            float[] last = lastBroadcast.get(id);
            if (last == null) {
                continue;
            }
            Vector3f p = e.getPosition();
            float yaw = e.getRotation().y;

            float dx = p.x - last[0];
            float dy = p.y - last[1];
            float dz = p.z - last[2];
            float dyaw = Math.abs(yaw - last[3]);

            int sinceResync = ticksSinceResync.getOrDefault(id, 0) + 1;
            boolean posMoved = Math.abs(dx) >= MIN_BROADCAST_DELTA
                || Math.abs(dy) >= MIN_BROADCAST_DELTA
                || Math.abs(dz) >= MIN_BROADCAST_DELTA;
            boolean rotMoved = dyaw >= MIN_BROADCAST_YAW_DEG;
            boolean forceResync = sinceResync >= RESYNC_PERIOD_TICKS;

            if (!posMoved && !rotMoved && !forceResync) {
                ticksSinceResync.put(id, sinceResync);
                continue;
            }

            if (!forceResync && EntityDeltaCodec.fitsInDelta(dx, dy, dz)) {
                short edx = EntityDeltaCodec.encodePosDelta(dx);
                short edy = EntityDeltaCodec.encodePosDelta(dy);
                short edz = EntityDeltaCodec.encodePosDelta(dz);
                short eyaw = EntityDeltaCodec.encodeYawDeg(yaw);
                ctx.broadcast(new EntityMoveS2C(id, edx, edy, edz, eyaw), true);
                // Track exactly what we sent (decoded back) so deltas don't accumulate drift.
                last[0] += EntityDeltaCodec.decodePosDelta(edx);
                last[1] += EntityDeltaCodec.decodePosDelta(edy);
                last[2] += EntityDeltaCodec.decodePosDelta(edz);
                last[3] = EntityDeltaCodec.decodeYawDeg(eyaw);
                ticksSinceResync.put(id, sinceResync);
            } else {
                ctx.broadcast(new EntityTeleportS2C(id, p.x, p.y, p.z, yaw), false);
                last[0] = p.x; last[1] = p.y; last[2] = p.z; last[3] = yaw;
                ticksSinceResync.put(id, 0);
            }
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private void registerHostEntity(Entity e) {
        if (e.getNetworkId() < 0) {
            e.setNetworkId(nextNetworkId.getAndIncrement());
        }
        byNetworkId.put(e.getNetworkId(), e);
        Vector3f p = e.getPosition();
        lastBroadcast.put(e.getNetworkId(), new float[]{p.x, p.y, p.z, e.getRotation().y});
        ticksSinceResync.put(e.getNetworkId(), 0);
    }

    private static boolean isReplicable(Entity e) {
        if (e == null || e instanceof RemotePlayer) {
            return false;
        }
        EntityType t = e.getType();
        return t == EntityType.COW
            || t == EntityType.CHICKEN
            || t == EntityType.BLOCK_DROP
            || t == EntityType.ITEM_DROP;
    }

    private static EntitySpawnS2C spawnPacketFor(Entity e) {
        Vector3f p = e.getPosition();
        String metadata = "";
        if (e instanceof Cow cow) {
            metadata = cow.getTextureVariant();
        } else if (e instanceof BlockDrop bd) {
            metadata = Integer.toString(bd.getBlockType().getId());
        } else if (e instanceof ItemDrop id) {
            ItemStack stack = id.getItemStack();
            metadata = stack.getBlockTypeId() + ":" + stack.getCount();
        }
        return new EntitySpawnS2C(
            e.getNetworkId(), e.getType().ordinal(),
            p.x, p.y, p.z, e.getRotation().y, metadata);
    }
}
