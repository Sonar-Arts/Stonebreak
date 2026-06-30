package com.stonebreak.network.server.handlers;

import com.openmason.engine.net.protocol.codec.EntityDeltaCodec;
import com.stonebreak.mobs.cow.Cow;
import com.stonebreak.mobs.entities.BlockDrop;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.ItemDrop;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.RemotePlayer;
import com.stonebreak.mobs.sbe.EntityAnimResolver;
import com.stonebreak.mobs.sheep.Sheep;
import com.stonebreak.items.ItemStack;
import com.stonebreak.network.packet.entity.EntityAnimS2C;
import com.stonebreak.network.packet.entity.EntityDamageC2S;
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

    /** Lenient hit-range gate (arrows/fire bolts land at distance); rejects absurd claims. */
    private static final float MAX_DAMAGE_RANGE_SQ = 64f * 64f;
    /** Upper bound on a single client-reported hit, to contain buggy/hostile clients. */
    private static final float MAX_DAMAGE_AMOUNT = 100f;

    private final AtomicInteger nextNetworkId = new AtomicInteger(1);
    private final Map<Integer, Entity> byNetworkId = new ConcurrentHashMap<>();
    private final Map<Integer, float[]> lastBroadcast = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> ticksSinceResync = new ConcurrentHashMap<>();
    /** Last SBE animation-state name broadcast per entity, so we only resend on change. */
    private final Map<Integer, String> lastAnimState = new ConcurrentHashMap<>();

    /** Reset, then snapshot existing entities so they're tracked + replicable. */
    public void onSessionStart(ServerWorldContext ctx) {
        nextNetworkId.set(1);
        byNetworkId.clear();
        lastBroadcast.clear();
        ticksSinceResync.clear();
        lastAnimState.clear();
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
        lastAnimState.clear();
    }

    /** EntityManager listener hook: a new entity was added to the authoritative world. */
    public void onEntitySpawned(Entity e, ServerWorldContext ctx) {
        if (!isReplicable(e)) {
            return;
        }
        registerHostEntity(e);
        ctx.broadcast(spawnPacketFor(e), false);
        // Send the initial animation state so the shadow starts in the right clip, not the default.
        EntityAnimS2C anim = animPacketFor(e);
        if (anim != null) {
            lastAnimState.put(e.getNetworkId(), anim.state());
            ctx.broadcast(anim, false);
        }
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
        lastAnimState.remove(id);
        ctx.broadcast(new EntityDespawnS2C(id), false);
    }

    /** Send the full spawn snapshot of every tracked entity to one joining player. */
    public void onPeerJoined(ServerPlayer sp) {
        for (Entity e : byNetworkId.values()) {
            sp.send(spawnPacketFor(e), false);
            EntityAnimS2C anim = animPacketFor(e);
            if (anim != null) {
                sp.send(anim, false);
            }
        }
    }

    public void tick(ServerWorldContext ctx) {
        for (Entity e : byNetworkId.values()) {
            if (!e.isAlive()) {
                continue;
            }
            // Replicate animation/behavior state on change (independent of movement — a mob can
            // change state while stationary, e.g. Idle -> Grazing).
            broadcastAnimIfChanged(e, ctx);

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
                // The periodic resync also refreshes animation state, self-healing any spawn-race
                // miss (e.g. an anim packet that arrived before the client world was ready).
                EntityAnimS2C anim = animPacketFor(e);
                if (anim != null) {
                    lastAnimState.put(id, anim.state());
                    ctx.broadcast(anim, false);
                }
            }
        }
    }

    /**
     * C2S: a player claims to have damaged entity {@code targetNetworkId}. Validates the
     * claim (entity exists + alive, plausible range, sane amount/source) and applies the
     * damage on the authoritative entity. Death then flows through the normal listener
     * chain (EntityManager removal → {@link EntityDespawnS2C} + replicated drops).
     */
    public void handleEntityDamage(ServerPlayer sp, EntityDamageC2S pkt, ServerWorldContext ctx) {
        Entity e = byNetworkId.get(pkt.targetNetworkId());
        if (!(e instanceof LivingEntity le) || !le.isAlive()) {
            return; // unknown id (despawn raced the hit) or already dead — drop silently
        }
        Vector3f p = e.getPosition();
        float dx = p.x - sp.x();
        float dy = p.y - sp.y();
        float dz = p.z - sp.z();
        if (dx * dx + dy * dy + dz * dz > MAX_DAMAGE_RANGE_SQ) {
            return;
        }
        float amount = pkt.amount();
        if (!(amount > 0f)) { // also rejects NaN
            return;
        }
        amount = Math.min(amount, MAX_DAMAGE_AMOUNT);

        LivingEntity.DamageSource source = decodeSource(pkt.sourceOrdinal());
        // Stats/XP credit only the same-JVM local player; remote attackers credit nobody
        // rather than mis-crediting the host (per-player attribution is a follow-up).
        le.damage(amount, source, new Vector3f(sp.x(), sp.y(), sp.z()), sp.isLocal());
    }

    private static LivingEntity.DamageSource decodeSource(byte ordinal) {
        LivingEntity.DamageSource[] values = LivingEntity.DamageSource.values();
        if (ordinal < 0 || ordinal >= values.length) {
            return LivingEntity.DamageSource.UNKNOWN;
        }
        LivingEntity.DamageSource source = values[ordinal];
        return switch (source) { // allow-list of client-originated sources
            case PLAYER, ARROW, FIRE -> source;
            default -> LivingEntity.DamageSource.UNKNOWN;
        };
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
            || t == EntityType.SHEEP
            || t == EntityType.GOOSE
            || t == EntityType.BLOCK_DROP
            || t == EntityType.ITEM_DROP;
    }

    /** Broadcast an {@link EntityAnimS2C} when an entity's SBE animation state has changed. */
    private void broadcastAnimIfChanged(Entity e, ServerWorldContext ctx) {
        String state = EntityAnimResolver.sbeState(e);
        if (state == null) {
            return; // not an AI-animated mob (drops/projectiles)
        }
        int id = e.getNetworkId();
        if (!state.equals(lastAnimState.get(id))) {
            lastAnimState.put(id, state);
            ctx.broadcast(new EntityAnimS2C(id, state), false);
        }
    }

    /** The current animation-state packet for an entity, or null if it has no AI-driven state. */
    private static EntityAnimS2C animPacketFor(Entity e) {
        String state = EntityAnimResolver.sbeState(e);
        return state != null ? new EntityAnimS2C(e.getNetworkId(), state) : null;
    }

    private static EntitySpawnS2C spawnPacketFor(Entity e) {
        Vector3f p = e.getPosition();
        String metadata = "";
        if (e instanceof Cow cow) {
            metadata = cow.getTextureVariant();
        } else if (e instanceof Sheep sheep) {
            metadata = sheep.getTextureVariant();
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
