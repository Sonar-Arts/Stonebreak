package com.stonebreak.network.server.handlers;

import com.openmason.engine.net.protocol.codec.EntityDeltaCodec;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.RemotePlayer;
import com.stonebreak.mobs.sbe.EntityAnimResolver;
import com.stonebreak.network.packet.entity.EntityAnimS2C;
import com.stonebreak.network.packet.entity.EntityDamageC2S;
import com.stonebreak.network.packet.entity.EntityDespawnS2C;
import com.stonebreak.network.packet.entity.EntityMoveS2C;
import com.stonebreak.network.packet.entity.EntitySpawnS2C;
import com.stonebreak.network.packet.entity.EntityTeleportS2C;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;
import com.openmason.engine.net.protocol.Packet;
import org.joml.Vector3f;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Replicates non-player entities (cows, chickens, drops) to clients — the authoritative
 * successor of the old {@code EntitySynchronizer} HOST path. Assigns network ids,
 * replicates spawn/despawn (driven by the EntityManager listener wired in the lifecycle
 * phase), and emits compact {@link EntityMoveS2C} deltas (or {@link EntityTeleportS2C} on
 * big jumps / periodic resync) each server tick for entities that moved.
 *
 * <p><b>Per-player interest:</b> each player only receives entities within their own view
 * distance (Chebyshev chunk radius — the same shape chunk streaming uses). An entity gets
 * an {@link EntitySpawnS2C} when it enters a player's interest and an
 * {@link EntityDespawnS2C} when it leaves (with a small exit hysteresis so a mob loitering
 * on the boundary doesn't flap). Moves/anim updates go only to interested players. This is
 * what makes entities "despawn" for far-away players and appear per-player instead of
 * being globally revealed to whoever was online when they spawned.
 *
 * <p>The client-side shadow creation + interpolation lives in the client world view, not
 * here.
 */
public final class ServerEntityHandler {

    private static final float MIN_BROADCAST_DELTA = 0.005f;
    private static final float MIN_BROADCAST_YAW_DEG = 0.5f;
    /** Force an absolute teleport every N ticks per entity to bound drift from lost deltas. */
    private static final int RESYNC_PERIOD_TICKS = 40; // 40 * 50ms = 2 s

    /** Interest exit hysteresis: leave at view+2 chunks (mirrors the chunk-forget radius),
     *  enter at the view distance itself. Package-private for the interest tests. */
    static final int INTEREST_EXIT_MARGIN_CHUNKS = 2;

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
    /** playerId → network ids this player has been sent a spawn for (their interest set). */
    private final Map<Integer, Set<Integer>> interestByPlayer = new ConcurrentHashMap<>();

    /** Reset, then snapshot existing entities so they're tracked + replicable. */
    public void onSessionStart(ServerWorldContext ctx) {
        nextNetworkId.set(1);
        byNetworkId.clear();
        lastBroadcast.clear();
        ticksSinceResync.clear();
        lastAnimState.clear();
        interestByPlayer.clear();
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
        interestByPlayer.clear();
    }

    /** EntityManager listener hook: a new entity was added to the authoritative world. */
    public void onEntitySpawned(Entity e, ServerWorldContext ctx) {
        if (!isReplicable(e)) {
            return;
        }
        registerHostEntity(e);
        int sentTo = 0;
        for (ServerPlayer sp : ctx.players()) {
            if (offerToPlayer(sp, e, ctx)) {
                sentTo++;
            }
        }
        // Drops are rare enough to trace individually — this line is the server half of the
        // drop-replication diagnosis (pairs with the client's "Drop shadow" line).
        if (e.getType() == EntityType.BLOCK_DROP || e.getType() == EntityType.ITEM_DROP) {
            Vector3f dp = e.getPosition();
            System.out.printf("[SERVER-ENTITY] %s spawn netId=%d at (%.1f, %.1f, %.1f) sent to %d interested player(s)%n",
                e.getType(), e.getNetworkId(), dp.x, dp.y, dp.z, sentTo);
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
        for (Set<Integer> known : interestByPlayer.values()) {
            known.remove(id);
        }
        // Broadcast (not interest-scoped): a stray despawn for an unknown id is a no-op on
        // the client, and this guarantees no ghost can survive an interest bookkeeping slip.
        ctx.broadcast(new EntityDespawnS2C(id), false);
    }

    /**
     * Fresh interest set for a (re)joining player (their client world starts empty), then
     * send what's already in range.
     */
    public void onPeerJoined(ServerPlayer sp, ServerWorldContext ctx) {
        interestByPlayer.put(sp.playerId(), ConcurrentHashMap.newKeySet());
        updatePlayerInterest(sp, ctx);
    }

    /**
     * EntityResyncC2S: the client's shadow map diverged (a spawn was lost). Re-send the
     * spawn of every entity this player is CURRENTLY interested in (idempotent client-side
     * — known ids are ignored), then run a normal interest pass for anything newly in
     * range. Deliberately does NOT reset the interest set: wiping it would orphan entities
     * sitting in the enter/exit hysteresis band — no longer "known", so never despawned,
     * yet outside the enter radius, so never re-spawned — a frozen ghost on the client.
     */
    public void onPeerResync(ServerPlayer sp, ServerWorldContext ctx) {
        Set<Integer> known = knownSetFor(sp);
        for (Integer id : known) {
            Entity e = byNetworkId.get(id);
            if (e != null) {
                sendSpawnTo(sp, e);
            } else {
                // Bookkeeping slip (entity gone but still in the set) — despawn + forget.
                known.remove(id);
                sp.send(new EntityDespawnS2C(id), false);
            }
        }
        updatePlayerInterest(sp, ctx);
    }

    /** Drop a disconnected player's interest bookkeeping. */
    public void onPeerLeft(ServerPlayer sp) {
        interestByPlayer.remove(sp.playerId());
    }

    public void tick(ServerWorldContext ctx) {
        // Interest pass first: entities entering a player's view get their spawn before any
        // move packet this tick could reference them.
        for (ServerPlayer sp : ctx.players()) {
            updatePlayerInterest(sp, ctx);
        }
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
                sendToInterested(ctx, id, new EntityMoveS2C(id, edx, edy, edz, eyaw), true);
                // Track exactly what we sent (decoded back) so deltas don't accumulate drift.
                last[0] += EntityDeltaCodec.decodePosDelta(edx);
                last[1] += EntityDeltaCodec.decodePosDelta(edy);
                last[2] += EntityDeltaCodec.decodePosDelta(edz);
                last[3] = EntityDeltaCodec.decodeYawDeg(eyaw);
                ticksSinceResync.put(id, sinceResync);
            } else {
                sendToInterested(ctx, id, new EntityTeleportS2C(id, p.x, p.y, p.z, yaw), false);
                last[0] = p.x; last[1] = p.y; last[2] = p.z; last[3] = yaw;
                ticksSinceResync.put(id, 0);
                // The periodic resync also refreshes animation state, self-healing any spawn-race
                // miss (e.g. an anim packet that arrived before the client world was ready).
                EntityAnimS2C anim = animPacketFor(e);
                if (anim != null) {
                    lastAnimState.put(id, anim.state());
                    sendToInterested(ctx, id, anim, false);
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
        // Never credit through the local-player path (on the server thread that would be
        // the HOST's player object regardless of attacker). Credit routes back to the
        // actual attacker via KillCreditS2C — uniform for host (Local channel) and remotes.
        float healthBefore = le.getHealth();
        le.damage(amount, source, new Vector3f(sp.x(), sp.y(), sp.z()), false);
        float dealt = healthBefore - Math.max(0f, le.getHealth());
        if (dealt > 0f) {
            boolean killed = !le.isAlive();
            sp.send(new com.stonebreak.network.packet.player.KillCreditS2C(
                le.getType().ordinal(), dealt, killed, killed ? le.getXpReward() : 0), false);
        }
    }

    /**
     * Server-side hit credit from an OWNED projectile (see {@code ProjectileDamage}):
     * forwards the stat/XP grant to the launching player's client.
     */
    public void sendKillCredit(ServerPlayer sp, LivingEntity victim, float dealt, boolean killed) {
        sp.send(new com.stonebreak.network.packet.player.KillCreditS2C(
            victim.getType().ordinal(), dealt, killed, killed ? victim.getXpReward() : 0), false);
    }

    /** Spawn position must be near the claiming player (zones cast at range get more slack). */
    private static final float MAX_PROJECTILE_SPAWN_DIST_SQ = 8f * 8f;
    private static final float MAX_ZONE_SPAWN_DIST_SQ = 32f * 32f;
    /** Per-kind clamps on client-supplied sim params (hostile-client containment). */
    private static final float MAX_PARAM_DAMAGE = 100f;
    private static final float MAX_PARAM_DURATION = 120f;
    private static final float MAX_PARAM_RADIUS = 16f;
    private static final float MAX_ARROW_SPEED = 60f;

    /**
     * C2S: a player launches a projectile / places an ability entity. Validates and spawns
     * the AUTHORITATIVE entity on the server EntityManager — the entity-add listener
     * replicates it to everyone (originator included; there is no client-local spawn).
     */
    public void handleProjectileSpawn(ServerPlayer sp, com.stonebreak.network.packet.entity.ProjectileSpawnC2S pkt,
                                      ServerWorldContext ctx) {
        EntityManager em = ctx.entityManager();
        if (em == null) {
            return;
        }
        if (!Float.isFinite(pkt.x()) || !Float.isFinite(pkt.y()) || !Float.isFinite(pkt.z())
            || !Float.isFinite(pkt.vx()) || !Float.isFinite(pkt.vy()) || !Float.isFinite(pkt.vz())) {
            return;
        }
        for (float p : pkt.params()) {
            if (!Float.isFinite(p)) {
                return;
            }
        }
        if (sp.lastStateNs() != 0L) {
            float dx = pkt.x() - sp.x();
            float dy = pkt.y() - sp.y();
            float dz = pkt.z() - sp.z();
            float distSq = dx * dx + dy * dy + dz * dz;
            boolean zoneKind = pkt.kind() == com.stonebreak.network.packet.entity.ProjectileSpawnC2S.KIND_LEYLINE_BREACH
                || pkt.kind() == com.stonebreak.network.packet.entity.ProjectileSpawnC2S.KIND_CALTROP;
            if (distSq > (zoneKind ? MAX_ZONE_SPAWN_DIST_SQ : MAX_PROJECTILE_SPAWN_DIST_SQ)) {
                return;
            }
        }
        Vector3f pos = new Vector3f(pkt.x(), pkt.y(), pkt.z());
        Vector3f v = new Vector3f(pkt.vx(), pkt.vy(), pkt.vz());
        float[] params = pkt.params();
        Entity spawned = switch (pkt.kind()) {
            case com.stonebreak.network.packet.entity.ProjectileSpawnC2S.KIND_ARROW -> {
                if (v.lengthSquared() < 1e-6f) {
                    yield null;
                }
                if (v.length() > MAX_ARROW_SPEED) {
                    v.normalize(MAX_ARROW_SPEED);
                }
                yield em.spawnArrow(pos, v);
            }
            case com.stonebreak.network.packet.entity.ProjectileSpawnC2S.KIND_FIRE_BOLT -> {
                if (v.lengthSquared() < 1e-6f) {
                    yield null;
                }
                yield em.spawnFireBolt(pos, v.normalize());
            }
            case com.stonebreak.network.packet.entity.ProjectileSpawnC2S.KIND_NULL_SPIKE -> {
                if (v.lengthSquared() < 1e-6f || params.length < 4) {
                    yield null;
                }
                yield em.spawnNullSpike(pos, v.normalize(),
                    clamp(params[0], 0f, MAX_PARAM_DAMAGE),
                    clamp(params[1], 0f, MAX_PARAM_DURATION),
                    params[2] != 0f,
                    clamp(params[3], 0f, MAX_PARAM_DAMAGE));
            }
            case com.stonebreak.network.packet.entity.ProjectileSpawnC2S.KIND_LEYLINE_BREACH -> {
                if (params.length < 5) {
                    yield null;
                }
                yield em.spawnLeylineBreachZone(pos,
                    clamp(params[0], 0.5f, MAX_PARAM_RADIUS),
                    clamp(params[1], 0f, MAX_PARAM_DAMAGE),
                    clamp(params[2], 0f, MAX_PARAM_DAMAGE),
                    clamp(params[3], 0f, MAX_PARAM_DURATION),
                    params[4] != 0f);
            }
            case com.stonebreak.network.packet.entity.ProjectileSpawnC2S.KIND_CALTROP -> {
                if (params.length < 1) {
                    yield null;
                }
                yield em.spawnCaltropCluster(pos, clamp(params[0], 1f, MAX_PARAM_DURATION));
            }
            default -> null;
        };
        if (spawned != null) {
            // Route hit/kill credit for this projectile back to the launching player.
            spawned.setOwnerPlayerId(sp.playerId());
        }
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
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

    // ─── Per-player interest ────────────────────────────────────────────────────

    /**
     * Diff one player's interest set against every tracked entity: offer spawns for
     * entities entering the view radius, despawn those beyond the exit radius.
     */
    private void updatePlayerInterest(ServerPlayer sp, ServerWorldContext ctx) {
        if (!sp.handshakeDone()) {
            return;
        }
        float px = anchorX(sp, ctx);
        float pz = anchorZ(sp, ctx);
        Set<Integer> known = knownSetFor(sp);
        int enterRadius = sp.viewDistanceChunks();
        int exitRadius = enterRadius + INTEREST_EXIT_MARGIN_CHUNKS;
        for (Entity e : byNetworkId.values()) {
            int id = e.getNetworkId();
            float[] base = lastBroadcast.get(id);
            float ex = base != null ? base[0] : e.getPosition().x;
            float ez = base != null ? base[2] : e.getPosition().z;
            if (known.contains(id)) {
                if (!withinChunkRadius(ex, ez, px, pz, exitRadius)) {
                    known.remove(id);
                    sp.send(new EntityDespawnS2C(id), false);
                }
            } else if (withinChunkRadius(ex, ez, px, pz, enterRadius)) {
                known.add(id);
                sendSpawnTo(sp, e);
            }
        }
    }

    /** Offer one entity to one player (spawn hook path); true if a spawn was sent. */
    private boolean offerToPlayer(ServerPlayer sp, Entity e, ServerWorldContext ctx) {
        if (!sp.handshakeDone()) {
            return false;
        }
        Set<Integer> known = knownSetFor(sp);
        int id = e.getNetworkId();
        if (known.contains(id)) {
            return false;
        }
        Vector3f p = e.getPosition();
        if (!withinChunkRadius(p.x, p.z, anchorX(sp, ctx), anchorZ(sp, ctx), sp.viewDistanceChunks())) {
            return false;
        }
        known.add(id);
        sendSpawnTo(sp, e);
        return true;
    }

    private void sendSpawnTo(ServerPlayer sp, Entity e) {
        sp.send(spawnPacketFor(e), false);
        // Current animation state so the shadow starts in the right clip, not the default.
        EntityAnimS2C anim = animPacketFor(e);
        if (anim != null) {
            sp.send(anim, false);
        }
    }

    private void sendToInterested(ServerWorldContext ctx, int networkId, Packet packet, boolean droppable) {
        for (ServerPlayer sp : ctx.players()) {
            Set<Integer> known = interestByPlayer.get(sp.playerId());
            if (known != null && known.contains(networkId)) {
                sp.send(packet, droppable);
            }
        }
    }

    private Set<Integer> knownSetFor(ServerPlayer sp) {
        return interestByPlayer.computeIfAbsent(sp.playerId(), ignored -> ConcurrentHashMap.newKeySet());
    }

    /** Interest anchor: the player's last reported position, or the world spawn until the
     *  first PlayerStateC2S lands (a just-joined player is standing at the spawn). */
    private static float anchorX(ServerPlayer sp, ServerWorldContext ctx) {
        return sp.lastStateNs() != 0L ? sp.x() : ctx.spawn().x;
    }

    private static float anchorZ(ServerPlayer sp, ServerWorldContext ctx) {
        return sp.lastStateNs() != 0L ? sp.z() : ctx.spawn().z;
    }

    /** Chebyshev chunk-radius test — the same view shape chunk streaming uses. */
    static boolean withinChunkRadius(float ex, float ez, float px, float pz, int radiusChunks) {
        int cs = com.stonebreak.world.operations.WorldConfiguration.CHUNK_SIZE;
        int ecx = Math.floorDiv((int) Math.floor(ex), cs);
        int ecz = Math.floorDiv((int) Math.floor(ez), cs);
        int pcx = Math.floorDiv((int) Math.floor(px), cs);
        int pcz = Math.floorDiv((int) Math.floor(pz), cs);
        return Math.max(Math.abs(ecx - pcx), Math.abs(ecz - pcz)) <= radiusChunks;
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
        // RemotePlayer subclasses (IllusionDecoy) report their own type; the instanceof
        // guard keeps any player-shaped display entity off the entity channel regardless.
        return e != null && !(e instanceof RemotePlayer) && e.getType().replicates();
    }

    /** Send an {@link EntityAnimS2C} to interested players when an entity's SBE state changed. */
    private void broadcastAnimIfChanged(Entity e, ServerWorldContext ctx) {
        String state = EntityAnimResolver.sbeState(e);
        if (state == null) {
            return; // not an AI-animated mob (drops/projectiles)
        }
        int id = e.getNetworkId();
        if (!state.equals(lastAnimState.get(id))) {
            lastAnimState.put(id, state);
            sendToInterested(ctx, id, new EntityAnimS2C(id, state), false);
        }
    }

    /** The current animation-state packet for an entity, or null if it has no AI-driven state. */
    private static EntityAnimS2C animPacketFor(Entity e) {
        String state = EntityAnimResolver.sbeState(e);
        return state != null ? new EntityAnimS2C(e.getNetworkId(), state) : null;
    }

    private EntitySpawnS2C spawnPacketFor(Entity e) {
        // Seed the client at the entity's last-broadcast base (not its live position): the
        // move deltas this handler emits are computed against that base, so a late-interest
        // spawn keeps the receiver's delta chain consistent with everyone else's.
        float[] last = lastBroadcast.get(e.getNetworkId());
        float x, y, z, yaw;
        if (last != null) {
            x = last[0]; y = last[1]; z = last[2]; yaw = last[3];
        } else {
            Vector3f p = e.getPosition();
            x = p.x; y = p.y; z = p.z; yaw = e.getRotation().y;
        }
        return new EntitySpawnS2C(
            e.getNetworkId(), e.getType().ordinal(),
            x, y, z, yaw,
            com.stonebreak.network.EntityReplicationRegistry.metadataFor(e));
    }
}
