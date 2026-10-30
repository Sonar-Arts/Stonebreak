package com.stonebreak.network.server.handlers;

import com.openmason.engine.net.protocol.codec.EntityDeltaCodec;
import com.stonebreak.items.weapon.DamageType;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.HitKind;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.RemotePlayer;
import com.stonebreak.mobs.entities.ai.AwarenessController;
import com.stonebreak.mobs.entities.ai.perception.Loudness;
import com.stonebreak.mobs.entities.ai.perception.NoiseBus;
import com.stonebreak.mobs.sbe.EntityAnimResolver;
import com.stonebreak.mobs.entities.status.StatusEffectBits;
import com.stonebreak.mobs.entities.status.StatusEffectType;
import com.stonebreak.mobs.sheep.Sheep;
import com.stonebreak.network.packet.entity.EntityAnimS2C;
import com.stonebreak.network.packet.entity.EntityAwarenessS2C;
import com.stonebreak.network.packet.entity.EntityDamageC2S;
import com.stonebreak.network.packet.entity.EntityDespawnS2C;
import com.stonebreak.network.packet.entity.EntityMoveS2C;
import com.stonebreak.network.packet.entity.EntitySpawnS2C;
import com.stonebreak.network.packet.entity.EntityStatusS2C;
import com.stonebreak.network.packet.entity.EntityTeleportS2C;
import com.stonebreak.network.packet.entity.EntityVariantS2C;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;
import com.stonebreak.network.server.combat.CombatRules;
import com.openmason.engine.net.protocol.Packet;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    /** After a dropped move delta, the absolute resync follows within this many ticks. */
    private static final int DROPPED_MOVE_RESYNC_TICKS = 10;

    /** Interest exit hysteresis: leave at view+2 chunks (mirrors the chunk-forget radius),
     *  enter at the view distance itself. Package-private for the interest tests. */
    static final int INTEREST_EXIT_MARGIN_CHUNKS = 2;

    /** Tolerant shear-range gate — the client raycast owns the precise targeting. */
    private static final float MAX_SHEAR_RANGE_SQ = 8f * 8f;
    /** Per-player shear rate limit, to contain buggy/hostile EntityShearC2S spam. */
    private static final long SHEAR_RATE_LIMIT_NANOS = 500_000_000L;

    private final AtomicInteger nextNetworkId = new AtomicInteger(1);
    /** Replication state per network id — one lookup per entity per tick (issue #346). */
    private final Map<Integer, Tracked> byNetworkId = new ConcurrentHashMap<>();
    /** playerId → where that player's interest was last judged from. */
    private final Map<Integer, PlayerView> views = new ConcurrentHashMap<>();
    /**
     * Entities whose broadcast base entered another chunk since the last interest pass — the
     * only ones a player who stayed in their chunk needs judged again (tick thread only).
     */
    private final List<Tracked> crossedChunk = new ArrayList<>();

    /** Reset, then snapshot existing entities so they're tracked + replicable. */
    public void onSessionStart(ServerWorldContext ctx) {
        nextNetworkId.set(1);
        clearTracking();
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
        clearTracking();
    }

    private void clearTracking() {
        for (Tracked t : byNetworkId.values()) {
            t.removed = true;
        }
        byNetworkId.clear();
        views.clear();
        crossedChunk.clear();
    }

    /** EntityManager listener hook: a new entity was added to the authoritative world. */
    public void onEntitySpawned(Entity e, ServerWorldContext ctx) {
        if (!isReplicable(e)) {
            return;
        }
        Tracked t = registerHostEntity(e);
        int sentTo = 0;
        for (ServerPlayer sp : ctx.players()) {
            if (offerToPlayer(sp, t, ctx)) {
                sentTo++;
            }
        }
        // Drops are rare enough to trace individually — this line is the server half of the
        // drop-replication diagnosis (pairs with the client's "Drop shadow" line).
        if (e.getType() == EntityType.BLOCK_DROP || e.getType() == EntityType.ITEM_DROP) {
            System.out.printf("[SERVER-ENTITY] %s spawn netId=%d at (%.1f, %.1f, %.1f) sent to %d interested player(s)%n",
                e.getType(), e.getNetworkId(), e.getX(), e.getY(), e.getZ(), sentTo);
        }
    }

    /** EntityManager listener hook: an entity was removed from the authoritative world. */
    public void onEntityDespawned(Entity e, ServerWorldContext ctx) {
        int id = e.getNetworkId();
        if (id < 0) {
            return;
        }
        Tracked t = byNetworkId.remove(id);
        if (t != null) {
            t.removed = true; // a pending crossed-chunk entry must not spawn it again
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
        int playerId = sp.playerId();
        for (Tracked t : byNetworkId.values()) {
            t.removeViewer(playerId);
        }
        views.put(playerId, new PlayerView());
        updatePlayerInterest(sp, ctx, true);
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
        int playerId = sp.playerId();
        for (Tracked t : byNetworkId.values()) {
            if (t.hasViewer(playerId)) {
                t.addViewer(sp); // the viewer entry now points at this session
                sendSpawnTo(sp, t);
            }
        }
        updatePlayerInterest(sp, ctx, true);
    }

    /** The authoritative entity with {@code networkId}, or null. */
    public Entity entity(int networkId) {
        Tracked t = byNetworkId.get(networkId);
        return t != null ? t.entity : null;
    }

    /** Drop a disconnected player's interest bookkeeping. */
    public void onPeerLeft(ServerPlayer sp) {
        int playerId = sp.playerId();
        for (Tracked t : byNetworkId.values()) {
            t.removeViewer(playerId);
        }
        views.remove(playerId);
    }

    public void tick(ServerWorldContext ctx) {
        // Interest pass first: entities entering a player's view get their spawn before any
        // move packet this tick could reference them.
        for (ServerPlayer sp : ctx.players()) {
            updatePlayerInterest(sp, ctx, false);
        }
        crossedChunk.clear();
        for (Tracked t : byNetworkId.values()) {
            Entity e = t.entity;
            if (!e.isAlive() || t.removed) {
                continue;
            }
            // Replicate animation/behavior state on change (independent of movement — a mob can
            // change state while stationary, e.g. Idle -> Grazing).
            broadcastAnimIfChanged(t, ctx);
            // Replicate appearance variant on change (shear swap, wool regrowth via grazing).
            broadcastVariantIfChanged(t, ctx);
            // Replicate health fraction + status effects on change (#371).
            broadcastStatusIfChanged(t, ctx);
            // Replicate stealth awareness on change (#321).
            broadcastAwarenessIfChanged(t);

            int id = t.id;
            float[] last = t.last;
            float x = e.getX();
            float y = e.getY();
            float z = e.getZ();
            float yaw = e.getYaw();

            float dx = x - last[0];
            float dy = y - last[1];
            float dz = z - last[2];
            // Angular difference: the codec wraps yaw to (-360, 360), so the sent base can
            // differ from an unwrapped live yaw by whole turns.
            float dyaw = Math.abs(((yaw - last[3]) % 360f + 540f) % 360f - 180f);

            int sinceResync = t.ticksSinceResync + 1;
            boolean posMoved = Math.abs(dx) >= MIN_BROADCAST_DELTA
                || Math.abs(dy) >= MIN_BROADCAST_DELTA
                || Math.abs(dz) >= MIN_BROADCAST_DELTA;
            boolean rotMoved = dyaw >= MIN_BROADCAST_YAW_DEG;
            boolean forceResync = sinceResync >= RESYNC_PERIOD_TICKS;

            if (!posMoved && !rotMoved && !forceResync) {
                t.ticksSinceResync = sinceResync;
                continue;
            }

            if (!forceResync && EntityDeltaCodec.fitsInDelta(dx, dy, dz)) {
                short edx = EntityDeltaCodec.encodePosDelta(dx);
                short edy = EntityDeltaCodec.encodePosDelta(dy);
                short edz = EntityDeltaCodec.encodePosDelta(dz);
                short eyaw = EntityDeltaCodec.encodeYawDeg(yaw);
                boolean allSent = sendToInterested(t, new EntityMoveS2C(id, edx, edy, edz, eyaw), true);
                // Track exactly what we sent (decoded back) so deltas don't accumulate drift.
                last[0] += EntityDeltaCodec.decodePosDelta(edx);
                last[1] += EntityDeltaCodec.decodePosDelta(edy);
                last[2] += EntityDeltaCodec.decodePosDelta(edz);
                last[3] = EntityDeltaCodec.decodeYawDeg(eyaw);
                // The base is shared by every viewer: a delta dropped for one of them (a
                // backed-up channel) offsets that viewer's shadow, so pull the absolute
                // teleport in to ≤0.5 s instead of waiting out the 2 s resync (not next tick:
                // a channel that stays backed up would then get a reliable teleport per tick).
                if (!allSent) {
                    sinceResync = Math.max(sinceResync, RESYNC_PERIOD_TICKS - DROPPED_MOVE_RESYNC_TICKS);
                }
                t.ticksSinceResync = sinceResync;
            } else {
                sendToInterested(t, new EntityTeleportS2C(id, x, y, z, yaw), false);
                last[0] = x; last[1] = y; last[2] = z; last[3] = yaw;
                t.ticksSinceResync = 0;
                // The periodic resync also refreshes animation state, self-healing any spawn-race
                // miss (e.g. an anim packet that arrived before the client world was ready).
                EntityAnimS2C anim = animPacketFor(e);
                if (anim != null) {
                    t.lastAnim = anim.state();
                    sendToInterested(t, anim, false);
                }
                EntityAwarenessS2C awareness = awarenessPacketFor(e);
                if (awareness != null) {
                    t.lastAwareness = awareness.state();
                    sendToInterested(t, awareness, false);
                }
            }
            if (t.rebase()) {
                crossedChunk.add(t);
            }
        }
    }

    /**
     * C2S: a player claims to have damaged entity {@code targetNetworkId}. Validates the
     * claim against the server's own state by its {@code HitKind} ({@link CombatRules}):
     * <ul>
     *   <li>MELEE — the target's box within reach of the player's server-known position,
     *       the swing cooldown, a cap from the server-known held weapon; always PLAYER damage.</li>
     *   <li>ABILITY — the player's class has an ability-hit profile with an unlocked ability,
     *       the profile's range, damage cap and hit budget; PLAYER or ARCANE damage.</li>
     * </ul>
     * Projectile hits are simulated here and never claimed. Then applies the damage on the
     * authoritative entity and answers the attacker with the applied result
     * ({@code KillCreditS2C}). Death flows through the normal listener chain (EntityManager
     * removal → {@link EntityDespawnS2C} + replicated drops).
     */
    public void handleEntityDamage(ServerPlayer sp, EntityDamageC2S pkt, ServerWorldContext ctx) {
        Entity e = entity(pkt.targetNetworkId());
        if (!(e instanceof LivingEntity le) || !le.isAlive()) {
            return; // unknown id (despawn raced the hit) or already dead — drop silently
        }
        if (le instanceof RemotePlayer) {
            return; // decoys take no hits (#374); a hit on a player is a PlayerDamageC2S (#343)
        }
        if (!sp.isLiveInWorld()) {
            return; // no reported position to measure from, or dead
        }
        float amount = pkt.amount();
        if (!(amount > 0f) || !Float.isFinite(amount)) { // also rejects NaN
            return;
        }
        HitKind kind = HitKind.fromWire(pkt.hitKind());
        LivingEntity.DamageSource source = decodeSource(pkt.sourceOrdinal());
        long now = System.nanoTime();
        float cap;
        if (kind == HitKind.MELEE) {
            if (!CombatRules.withinMeleeReach(sp.x(), sp.y(), sp.z(), le.getBoundingBox())
                    || !sp.combat().tryMelee(now)) {
                return;
            }
            cap = CombatRules.maxMeleeDamage(sp.inventory().heldItemId(),
                    sp.serverStats().getSelectedClassId());
            source = LivingEntity.DamageSource.PLAYER;
        } else if (kind == HitKind.ABILITY) {
            CombatRules.AbilityHitProfile profile =
                    CombatRules.abilityProfile(sp.serverStats().getSelectedClassId());
            if (profile == null || !profile.unlockedFor(sp.serverStats())
                    || distanceSq(sp, le) > profile.maxRange() * profile.maxRange()
                    || !sp.combat().tryAbilityHit(profile, now)) {
                return;
            }
            cap = profile.maxDamage();
            if (source != LivingEntity.DamageSource.ARCANE) {
                source = LivingEntity.DamageSource.PLAYER;
            }
        } else {
            return; // PROJECTILE (server-simulated, never claimed) or an unknown kind
        }
        amount = Math.min(amount, cap);
        DamageType type = decodeType(pkt.typeOrdinal(), source);

        // Never credit through the local-player path (on the server thread that would be
        // the HOST's player object regardless of attacker). Credit routes back to the
        // actual attacker via KillCreditS2C — uniform for host (Local channel) and remotes.
        // The weapon is what the server says this player holds, not a client claim — a client
        // naming a fresh id per swing would otherwise dodge every invulnerability window.
        int weaponId = kind == HitKind.MELEE ? sp.inventory().heldItemId() : LivingEntity.NO_WEAPON;
        float healthBefore = le.getHealth();
        boolean caughtUnaware = le.getAwareness() != null && le.getAwareness().caughtUnaware();
        sp.recordHit(pkt.targetNetworkId(), now);
        le.damage(amount, source, type, weaponId, new Vector3f(sp.x(), sp.y(), sp.z()), false);
        float dealt = healthBefore - Math.max(0f, le.getHealth());
        if (dealt > 0f) {
            // The blow is heard (#321) — unless it killed a victim that never saw it coming: then
            // the only sound is the body falling (Loudness.STEALTH_KILL, posted by the death).
            if (le.isAlive() || !caughtUnaware) {
                NoiseBus.emitIn(le.getWorld(), le.getPosition(), sp.quieted(Loudness.MELEE_ATTACK));
            }
            sendKillCredit(sp, le, dealt, !le.isAlive(), source, kind);
            if (kind == HitKind.MELEE) {
                ServerHealthHandler.applyLifesteal(sp, dealt);
            }
        }
    }

    /**
     * C2S: one of the player's abilities put a status effect on a replicated entity (#339).
     * Applied to the REAL entity — where it ticks, expires and acts on the AI — only when
     * the effect may come from a client at all, the player's class may apply it, the target
     * is within the effect's range of the player's server-known position, and the player's
     * status budget allows; duration and magnitude are clamped to the effect's legal maximum.
     * The result replicates to every interested client through {@code EntityStatusS2C}.
     */
    public void handleEntityStatusEffect(ServerPlayer sp,
                                         com.stonebreak.network.packet.entity.EntityStatusEffectC2S pkt) {
        Entity e = entity(pkt.targetNetworkId());
        if (!(e instanceof LivingEntity le) || !le.isAlive() || !sp.isLiveInWorld()
                || le instanceof RemotePlayer) { // no PvP effects either (#374)
            return;
        }
        StatusEffectType type = StatusEffectBits.fromWireId(pkt.effectId());
        CombatRules.StatusIntentRule rule = CombatRules.statusRule(type);
        if (rule == null || !rule.allowsClass(sp.serverStats().getSelectedClassId())) {
            return;
        }
        float duration = pkt.duration();
        float magnitude = pkt.magnitude();
        if (!(duration > 0f) || !Float.isFinite(duration) || !Float.isFinite(magnitude)) {
            return;
        }
        if (distanceSq(sp, le) > rule.maxRange() * rule.maxRange()
                || !sp.combat().tryStatusIntent(System.nanoTime())) {
            return;
        }
        le.applyStatusEffect(type, Math.min(duration, rule.maxDuration()),
                clamp(magnitude, 0f, rule.maxMagnitude()));
    }

    private static float distanceSq(ServerPlayer sp, Entity e) {
        Vector3f p = e.getPosition();
        float dx = p.x - sp.x();
        float dy = p.y - sp.y();
        float dz = p.z - sp.z();
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * An ability's knockback on an entity this player just hit (EntityKnockbackC2S). Only right
     * after an accepted hit from them on that target — knockback never lands on its own — with
     * the forces clamped to the strongest ability's.
     */
    public void handleEntityKnockback(ServerPlayer sp, com.stonebreak.network.packet.entity.EntityKnockbackC2S pkt) {
        Entity e = entity(pkt.targetNetworkId());
        if (!(e instanceof LivingEntity le) || !le.isAlive()) {
            return;
        }
        if (!sp.hitRecently(pkt.targetNetworkId(), System.nanoTime(), KNOCKBACK_AFTER_HIT_NANOS)) {
            return;
        }
        float dx = pkt.dirX();
        float dz = pkt.dirZ();
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (!(len > 1e-4f) || !Float.isFinite(pkt.horizontal()) || !Float.isFinite(pkt.vertical())) {
            return;
        }
        float horizontal = Math.max(0f, Math.min(MAX_KNOCKBACK_HORIZONTAL, pkt.horizontal()));
        float vertical = Math.max(0f, Math.min(MAX_KNOCKBACK_VERTICAL, pkt.vertical()));
        le.applyKnockback(new Vector3f(dx / len, 0f, dz / len), horizontal, vertical);
    }

    /** A knockback intent must follow this player's accepted hit on the target within this window. */
    private static final long KNOCKBACK_AFTER_HIT_NANOS = 500_000_000L;
    /** Strongest ability knockback (Rampage T3); a client asking for more is clamped. */
    private static final float MAX_KNOCKBACK_HORIZONTAL =
            com.stonebreak.player.PlayerConstants.RAMPAGE_KNOCKBACK_HORIZONTAL;
    private static final float MAX_KNOCKBACK_VERTICAL =
            com.stonebreak.player.PlayerConstants.RAMPAGE_KNOCKBACK_VERTICAL;

    /**
     * Authoritative shear application. Validates species/range, the held item (derived
     * by the server from the player's selected hotbar slot and inventory replica) and a
     * per-player rate limit, then shears
     * the real sheep; the variant change replicates to interested players via
     * {@code broadcastVariantIfChanged} on the next tick.
     *
     * <p>Every rejection echoes the sheep's real variant back to the requesting player:
     * the client predicted a sheared swap before sending, and the echo rolls that back so
     * an edge-of-reach shear while moving can't render a sheared sheep forever. See
     * {@code SheepShearing.tryShear}.
     */
    public void handleEntityShear(ServerPlayer sp, com.stonebreak.network.packet.entity.EntityShearC2S pkt,
                                  ServerWorldContext ctx) {
        Entity e = entity(pkt.targetNetworkId());
        if (!(e instanceof Sheep sheep) || !sheep.isAlive()) {
            return; // unknown id (despawn raced the click) — the despawn shadow removal corrects the client
        }
        long now = System.nanoTime();
        if (now - sp.lastShearNanos() < SHEAR_RATE_LIMIT_NANOS) {
            // Echo the real variant even here: tryShear short-circuits on isSheared(), so
            // the client never retries a throttled click — without the echo a predicted
            // swap on a throttled second sheep would render sheared forever.
            sp.send(new EntityVariantS2C(pkt.targetNetworkId(), sheep.getTextureVariant()), false);
            return; // rate-limited
        }
        sp.markShearRequest(now);
        // Server-side shears requirement: the held item is derived from the server's own
        // inventory replica via HotbarSelectC2S (issue #334) — it cannot be forged.
        if (sp.inventory().heldItemId() != com.stonebreak.items.ItemType.SHEARS.getId()) {
            sp.send(new EntityVariantS2C(pkt.targetNetworkId(), sheep.getTextureVariant()), false);
            return;
        }
        Vector3f p = e.getPosition();
        float dx = p.x - sp.x();
        float dy = p.y - sp.y();
        float dz = p.z - sp.z();
        if (dx * dx + dy * dy + dz * dz > MAX_SHEAR_RANGE_SQ) {
            // Range gate measures against the last reported position — an edge-of-reach
            // shear while moving lands here. Echo the real variant so the client reverts
            // its predicted swap instead of rendering a sheared sheep forever.
            sp.send(new EntityVariantS2C(pkt.targetNetworkId(), sheep.getTextureVariant()), false);
            return;
        }
        if (sheep.isSheared()) {
            sp.send(new EntityVariantS2C(pkt.targetNetworkId(), sheep.getTextureVariant()), false); // idempotent echo
            return;
        }
        sheep.shear();
    }

    /**
     * Server-side kill credit from an OWNED hit or projectile (see {@code ProjectileDamage}):
     * grants the victim's XP reward to the player's SERVER-owned progression (issue #334 —
     * level/xp can only come from server-granted kills) and forwards the stat credit to
     * the client. The packet's {@code xpReward} is display-only; the client mirror is
     * corrected by the ProgressS2C echo on level-up.
     */
    public void sendKillCredit(ServerPlayer sp, LivingEntity victim, float dealt, boolean killed,
                               LivingEntity.DamageSource source, HitKind kind) {
        // Carries the target's network id, source and kind: the attacker's damage number,
        // hurt sound, Rage/lifesteal (melee) and Quarry (arrows) run off this result (#371/#374).
        com.stonebreak.network.packet.player.KillCreditS2C credit =
            com.stonebreak.network.packet.player.KillCreditS2C.forHit(victim, dealt, killed, source, kind);
        if (credit.xpReward() > 0) {
            sp.serverStats().addXp(credit.xpReward());
            sp.sendProgression();
        }
        sp.send(credit, false);
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
        // The client already took an arrow out of its mirror: every refusal of an arrow
        // syncs the sender so the mirror gets it back.
        boolean arrow = pkt.kind() == com.stonebreak.network.packet.entity.ProjectileSpawnC2S.KIND_ARROW;
        CombatRules.ProjectileRule rule = CombatRules.projectileRule(pkt.kind());
        if (rule == null || !sp.isLiveInWorld()) {
            // Unknown kind, or no reported position yet (never skip the position check) / dead.
            refuseSpawn(sp, arrow);
            return;
        }
        float dx = pkt.x() - sp.x();
        float dy = pkt.y() - sp.y();
        float dz = pkt.z() - sp.z();
        float distSq = dx * dx + dy * dy + dz * dz;
        boolean zoneKind = pkt.kind() == com.stonebreak.network.packet.entity.ProjectileSpawnC2S.KIND_LEYLINE_BREACH
            || pkt.kind() == com.stonebreak.network.packet.entity.ProjectileSpawnC2S.KIND_CALTROP;
        if (distSq > (zoneKind ? MAX_ZONE_SPAWN_DIST_SQ : MAX_PROJECTILE_SPAWN_DIST_SQ)) {
            refuseSpawn(sp, arrow);
            return;
        }
        // Held item / class + unlocked ability, live cap per owner, then the spawn budget
        // (mirrors the client cooldown) — #339.
        if (!rule.allows(sp.inventory().heldItemId(), sp.serverStats())
                || countOwned(em, sp.playerId(), rule.liveType()) >= rule.maxLive()
                || !sp.combat().tryProjectile(pkt.kind(), rule, System.nanoTime())) {
            refuseSpawn(sp, arrow);
            return;
        }
        Vector3f pos = new Vector3f(pkt.x(), pkt.y(), pkt.z());
        Vector3f v = new Vector3f(pkt.vx(), pkt.vy(), pkt.vz());
        float[] params = pkt.params();
        Entity spawned = switch (pkt.kind()) {
            case com.stonebreak.network.packet.entity.ProjectileSpawnC2S.KIND_ARROW -> {
                if (v.lengthSquared() < 1e-6f) {
                    yield null;
                }
                // An arrow costs one ARROW from the server-owned inventory (issue #334);
                // a refusal spawns nothing and syncs the sender.
                if (!sp.inventory().debit(com.stonebreak.items.ItemType.ARROW.getId(), 1)) {
                    sp.send(sp.inventorySync(), false);
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
                yield em.spawnCaltropCluster(pos,
                    clamp(params[0], 1f, com.stonebreak.player.PlayerConstants.CALTROP_DURATION));
            }
            default -> null;
        };
        if (spawned != null) {
            // Route hit/kill credit for this projectile back to the launching player.
            spawned.setOwnerPlayerId(sp.playerId());
            NoiseBus.emitIn(ctx.world(), new Vector3f(sp.x(), sp.y(), sp.z()), sp.quieted(launchLoudness(pkt.kind())));
        }
    }

    /**
     * How loud loosing each projectile is at the shooter (#321). Spells vary; caltrops are
     * scattered without a sound, so they never give a Rogue away.
     */
    private static float launchLoudness(byte kind) {
        return switch (kind) {
            case com.stonebreak.network.packet.entity.ProjectileSpawnC2S.KIND_ARROW -> Loudness.RANGED_ATTACK;
            case com.stonebreak.network.packet.entity.ProjectileSpawnC2S.KIND_FIRE_BOLT -> 6f;
            case com.stonebreak.network.packet.entity.ProjectileSpawnC2S.KIND_NULL_SPIKE -> 5f;
            case com.stonebreak.network.packet.entity.ProjectileSpawnC2S.KIND_LEYLINE_BREACH -> 7f;
            default -> 0f; // caltrops, and any kind without a voice yet
        };
    }

    private static void refuseSpawn(ServerPlayer sp, boolean arrow) {
        if (arrow) {
            sp.send(sp.inventorySync(), false);
        }
    }

    /** Live entities of {@code type} launched by {@code playerId} — queued spawns included (a caltrop fan lands in one tick). */
    private static int countOwned(EntityManager em, int playerId, EntityType type) {
        int n = 0;
        for (Entity e : em.getAllEntitiesIncludingPending()) {
            if (e.getType() == type && e.getOwnerPlayerId() == playerId && e.isAlive()) {
                n++;
            }
        }
        return n;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    /** Damage type from the wire; out of range falls back to the source's default type. */
    private static DamageType decodeType(byte ordinal, LivingEntity.DamageSource source) {
        DamageType[] values = DamageType.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : source.defaultDamageType();
    }

    private static LivingEntity.DamageSource decodeSource(byte ordinal) {
        LivingEntity.DamageSource[] values = LivingEntity.DamageSource.values();
        if (ordinal < 0 || ordinal >= values.length) {
            return LivingEntity.DamageSource.UNKNOWN;
        }
        LivingEntity.DamageSource source = values[ordinal];
        return switch (source) { // allow-list of client-originated sources
            case PLAYER, ARROW, FIRE, ARCANE -> source; // ARCANE keeps its type (#339)
            default -> LivingEntity.DamageSource.UNKNOWN;
        };
    }

    // ─── Per-player interest ────────────────────────────────────────────────────

    /**
     * Diff one player's interest against the tracked entities: offer spawns for entities
     * entering the view radius, despawn those beyond the exit radius. Interest only changes
     * when a chunk distance does (issue #346): a player who moved to another chunk, changed
     * view distance or is new ({@code full}) is judged against every entity; anyone else
     * only against the entities that crossed into another chunk since the last pass.
     */
    private void updatePlayerInterest(ServerPlayer sp, ServerWorldContext ctx, boolean full) {
        if (!sp.handshakeDone()) {
            return;
        }
        int pcx = chunkOf(anchorX(sp, ctx));
        int pcz = chunkOf(anchorZ(sp, ctx));
        int enterRadius = sp.viewDistanceChunks();
        PlayerView view = views.computeIfAbsent(sp.playerId(), ignored -> new PlayerView());
        if (full || view.changedTo(pcx, pcz, enterRadius)) {
            view.set(pcx, pcz, enterRadius);
            for (Tracked t : byNetworkId.values()) {
                judgeInterest(sp, t, pcx, pcz, enterRadius);
            }
        } else {
            for (int i = 0, n = crossedChunk.size(); i < n; i++) {
                judgeInterest(sp, crossedChunk.get(i), pcx, pcz, enterRadius);
            }
        }
    }

    private void judgeInterest(ServerPlayer sp, Tracked t, int pcx, int pcz, int enterRadius) {
        if (t.removed) {
            return;
        }
        int distance = Math.max(Math.abs(t.chunkX - pcx), Math.abs(t.chunkZ - pcz));
        if (t.hasViewer(sp.playerId())) {
            if (distance > enterRadius + INTEREST_EXIT_MARGIN_CHUNKS) {
                t.removeViewer(sp.playerId());
                sp.send(new EntityDespawnS2C(t.id), false);
            }
        } else if (distance <= enterRadius) {
            t.addViewer(sp);
            sendSpawnTo(sp, t);
        }
    }

    /** Offer one entity to one player (spawn hook path); true if a spawn was sent. */
    private boolean offerToPlayer(ServerPlayer sp, Tracked t, ServerWorldContext ctx) {
        if (!sp.handshakeDone() || t.hasViewer(sp.playerId())) {
            return false;
        }
        if (!withinChunkRadius(t.last[0], t.last[2], anchorX(sp, ctx), anchorZ(sp, ctx), sp.viewDistanceChunks())) {
            return false;
        }
        t.addViewer(sp);
        sendSpawnTo(sp, t);
        return true;
    }

    private void sendSpawnTo(ServerPlayer sp, Tracked t) {
        Entity e = t.entity;
        sp.send(spawnPacketFor(t), false);
        // Current animation state so the shadow starts in the right clip, not the default.
        EntityAnimS2C anim = animPacketFor(e);
        if (anim != null) {
            sp.send(anim, false);
        }
        // Awareness when not the UNAWARE a fresh shadow starts in (#321).
        EntityAwarenessS2C awareness = awarenessPacketFor(e);
        if (awareness != null && awareness.state() != AwarenessController.AwarenessState.UNAWARE) {
            sp.send(awareness, false);
        }
        // Health/status when not the default a fresh shadow starts with (full, no effects).
        if (e instanceof LivingEntity le) {
            EntityStatusS2C status = statusPacketFor(le);
            if (status.statusBits() != 0
                    || EntityStatusS2C.quantize(status.healthFraction()) != EntityStatusS2C.quantize(1f)) {
                sp.send(status, false);
            }
        }
    }

    /** Sends to every player who knows {@code t}; false if any send was dropped. */
    private static boolean sendToInterested(Tracked t, Packet packet, boolean droppable) {
        boolean allSent = true;
        for (ServerPlayer sp : t.viewers) {
            allSent &= sp.send(packet, droppable);
        }
        return allSent;
    }

    /** Interest anchor: the player's last reported position, or the world spawn until the
     *  first PlayerStateC2S lands (a just-joined player is standing at the spawn). */
    private static float anchorX(ServerPlayer sp, ServerWorldContext ctx) {
        return sp.lastStateNs() != 0L ? sp.x() : ctx.spawn().x;
    }

    private static float anchorZ(ServerPlayer sp, ServerWorldContext ctx) {
        return sp.lastStateNs() != 0L ? sp.z() : ctx.spawn().z;
    }

    private static int chunkOf(float coord) {
        return Math.floorDiv((int) Math.floor(coord), com.stonebreak.world.operations.WorldConfiguration.CHUNK_SIZE);
    }

    /** Chebyshev chunk-radius test — the same view shape chunk streaming uses. */
    static boolean withinChunkRadius(float ex, float ez, float px, float pz, int radiusChunks) {
        return Math.max(Math.abs(chunkOf(ex) - chunkOf(px)), Math.abs(chunkOf(ez) - chunkOf(pz))) <= radiusChunks;
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private Tracked registerHostEntity(Entity e) {
        if (e.getNetworkId() < 0) {
            e.setNetworkId(nextNetworkId.getAndIncrement());
        }
        Tracked t = new Tracked(e);
        if (e instanceof LivingEntity le) {
            // Seed the variant tracker with what the EntitySpawnS2C metadata already
            // carried — otherwise the first tick broadcasts one redundant variant
            // packet per living entity.
            t.lastVariant = le.getTextureVariant();
            // Likewise the status tracker: the spawn path sends any non-default state itself.
            t.lastStatus = statusKey(statusPacketFor(le));
        }
        Tracked previous = byNetworkId.put(t.id, t);
        if (previous != null) {
            previous.removed = true;
        }
        return t;
    }
    private static boolean isReplicable(Entity e) {
        // The instanceof guard keeps player-shaped display entities off the entity channel;
        // the server-spawned illusion decoy (#341) is the one RemotePlayer subclass that rides it.
        return e != null && (!(e instanceof RemotePlayer) || e instanceof com.stonebreak.mobs.entities.IllusionDecoy)
                && e.getType().replicates();
    }

    /** Send an {@link EntityAnimS2C} to interested players when an entity's SBE state changed. */
    private void broadcastAnimIfChanged(Tracked t, ServerWorldContext ctx) {
        String state = EntityAnimResolver.sbeState(t.entity);
        if (state == null) {
            return; // not an AI-animated mob (drops/projectiles)
        }
        if (!state.equals(t.lastAnim)) {
            t.lastAnim = state;
            sendToInterested(t, new EntityAnimS2C(t.id, state), false);
        }
    }

    /**
     * Replicates a living entity's appearance variant on change (e.g. a sheep's
     * shear swap or wool regrowth via grazing) as {@link EntityVariantS2C}.
     * Covers the server-side mutation uniformly — {@code handleEntityShear} and
     * the AI-driven regrowth both mutate the real sheep's variant, and this
     * broadcasts it to interested players on the next tick.
     */
    private void broadcastVariantIfChanged(Tracked t, ServerWorldContext ctx) {
        if (!(t.entity instanceof LivingEntity le)) {
            return; // drops/projectiles carry no variant
        }
        String variant = le.getTextureVariant();
        if (!variant.equals(t.lastVariant)) {
            t.lastVariant = variant;
            sendToInterested(t, new EntityVariantS2C(t.id, variant), false);
        }
    }

    /**
     * Replicates a living entity's health fraction and active status effects on change as
     * {@link EntityStatusS2C} (#371): class mechanics read both off the client's shadow (low-HP
     * prey, the Quarry gauge, Flat-Footed crits, Spellmarked/Amplified), and a shadow never
     * simulates either. Change is judged on the quantized wire value, so sub-step regeneration
     * does not resend.
     */
    private void broadcastStatusIfChanged(Tracked t, ServerWorldContext ctx) {
        if (!(t.entity instanceof LivingEntity le)) {
            return;
        }
        float max = le.getMaxHealth();
        float fraction = max > 0f ? Math.max(0f, le.getHealth()) / max : 0f;
        int bits = le.getStatusEffectBits();
        long key = statusKey(fraction, bits);
        if (t.lastStatus != key) {
            t.lastStatus = key;
            sendToInterested(t, new EntityStatusS2C(t.id, fraction, bits), false);
        }
    }

    /**
     * Replicates a mob's stealth awareness state on change as {@link EntityAwarenessS2C} (#321):
     * the stealth markers, Shadow Step and the flat-footed opener read it off the shadow, which
     * never perceives anything itself.
     */
    private void broadcastAwarenessIfChanged(Tracked t) {
        EntityAwarenessS2C awareness = awarenessPacketFor(t.entity);
        if (awareness != null && awareness.state() != t.lastAwareness) {
            t.lastAwareness = awareness.state();
            sendToInterested(t, awareness, false);
        }
    }

    /** The current awareness packet for an entity, or null if it tracks no awareness. */
    private static EntityAwarenessS2C awarenessPacketFor(Entity e) {
        AwarenessController awareness = e instanceof LivingEntity le ? le.getAwareness() : null;
        return awareness != null ? new EntityAwarenessS2C(e.getNetworkId(), awareness.getState()) : null;
    }

    private static EntityStatusS2C statusPacketFor(LivingEntity le) {
        float max = le.getMaxHealth();
        float fraction = max > 0f ? Math.max(0f, le.getHealth()) / max : 0f;
        return new EntityStatusS2C(le.getNetworkId(), fraction, le.getStatusEffectBits());
    }

    /** Change key of a status packet: what actually goes on the wire. */
    private static long statusKey(EntityStatusS2C s) {
        return statusKey(s.healthFraction(), s.statusBits());
    }

    private static long statusKey(float healthFraction, int statusBits) {
        return ((long) EntityStatusS2C.quantize(healthFraction) << 32) | (statusBits & 0xFFFFL);
    }

    /** The current animation-state packet for an entity, or null if it has no AI-driven state. */
    private static EntityAnimS2C animPacketFor(Entity e) {
        String state = EntityAnimResolver.sbeState(e);
        return state != null ? new EntityAnimS2C(e.getNetworkId(), state) : null;
    }

    private static EntitySpawnS2C spawnPacketFor(Tracked t) {
        // Seed the client at the entity's last-broadcast base (not its live position): the
        // move deltas this handler emits are computed against that base, so a late-interest
        // spawn keeps the receiver's delta chain consistent with everyone else's.
        float[] last = t.last;
        return new EntitySpawnS2C(
            t.id, t.entity.getType().ordinal(),
            last[0], last[1], last[2], last[3],
            com.stonebreak.network.EntityReplicationRegistry.metadataFor(t.entity));
    }

    // ─── Tracking state ────────────────────────────────────────────────────────

    /** Replication state of one tracked entity, so a tick reads it with one map lookup. */
    private static final class Tracked {
        private static final ServerPlayer[] NO_VIEWERS = new ServerPlayer[0];

        final Entity entity;
        final int id;
        /** Last broadcast base: x, y, z, yaw — what every viewer's shadow was told. */
        final float[] last;
        int ticksSinceResync;
        /** Last SBE animation state / appearance variant / status key broadcast, resent on change. */
        String lastAnim;
        String lastVariant;
        long lastStatus = Long.MIN_VALUE;
        /** Last awareness state broadcast; a fresh shadow starts UNAWARE (#321). */
        AwarenessController.AwarenessState lastAwareness = AwarenessController.AwarenessState.UNAWARE;
        /** Chunk of the broadcast base: interest is judged on it. */
        int chunkX;
        int chunkZ;
        /** Set once untracked: a stale reference (crossed-chunk list) must not spawn it. */
        volatile boolean removed;
        /**
         * Players sent this entity's spawn — at most one entry per player id. Copy-on-write:
         * replication reads it every tick, it changes only on interest enter/exit.
         */
        volatile ServerPlayer[] viewers = NO_VIEWERS;

        Tracked(Entity e) {
            this.entity = e;
            this.id = e.getNetworkId();
            this.last = new float[]{e.getX(), e.getY(), e.getZ(), e.getYaw()};
            this.chunkX = chunkOf(last[0]);
            this.chunkZ = chunkOf(last[2]);
        }

        /** Recomputes the base's chunk; true if it changed. */
        boolean rebase() {
            int cx = chunkOf(last[0]);
            int cz = chunkOf(last[2]);
            if (cx == chunkX && cz == chunkZ) {
                return false;
            }
            chunkX = cx;
            chunkZ = cz;
            return true;
        }

        boolean hasViewer(int playerId) {
            for (ServerPlayer v : viewers) {
                if (v.playerId() == playerId) {
                    return true;
                }
            }
            return false;
        }

        /** Adds {@code sp}, replacing an earlier session with its player id. */
        synchronized void addViewer(ServerPlayer sp) {
            ServerPlayer[] current = viewers;
            for (int i = 0; i < current.length; i++) {
                if (current[i].playerId() == sp.playerId()) {
                    ServerPlayer[] next = current.clone();
                    next[i] = sp;
                    viewers = next;
                    return;
                }
            }
            ServerPlayer[] next = java.util.Arrays.copyOf(current, current.length + 1);
            next[current.length] = sp;
            viewers = next;
        }

        synchronized void removeViewer(int playerId) {
            ServerPlayer[] current = viewers;
            for (int i = 0; i < current.length; i++) {
                if (current[i].playerId() == playerId) {
                    ServerPlayer[] next = new ServerPlayer[current.length - 1];
                    System.arraycopy(current, 0, next, 0, i);
                    System.arraycopy(current, i + 1, next, i, current.length - i - 1);
                    viewers = next.length == 0 ? NO_VIEWERS : next;
                    return;
                }
            }
        }
    }

    /** The chunk and view distance a player's interest was last fully judged from. */
    private static final class PlayerView {
        private boolean judged;
        private int chunkX;
        private int chunkZ;
        private int viewDistance;

        boolean changedTo(int cx, int cz, int view) {
            return !judged || cx != chunkX || cz != chunkZ || view != viewDistance;
        }

        void set(int cx, int cz, int view) {
            judged = true;
            chunkX = cx;
            chunkZ = cz;
            viewDistance = view;
        }
    }
}
