package com.stonebreak.network.server.handlers;

import com.openmason.engine.net.protocol.codec.EntityDeltaCodec;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.HitKind;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.RemotePlayer;
import com.stonebreak.mobs.sbe.EntityAnimResolver;
import com.stonebreak.mobs.entities.status.StatusEffectBits;
import com.stonebreak.mobs.entities.status.StatusEffectType;
import com.stonebreak.mobs.sheep.Sheep;
import com.stonebreak.network.packet.entity.EntityAnimS2C;
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
    private final Map<Integer, Entity> byNetworkId = new ConcurrentHashMap<>();
    private final Map<Integer, float[]> lastBroadcast = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> ticksSinceResync = new ConcurrentHashMap<>();
    /** Last SBE animation-state name broadcast per entity, so we only resend on change. */
    private final Map<Integer, String> lastAnimState = new ConcurrentHashMap<>();

    /** Last appearance variant broadcast per entity, so we only resend on change. */
    private final Map<Integer, String> lastVariantState = new ConcurrentHashMap<>();
    /** Last health/status state broadcast per living entity ({@link #statusKey}), resent on change (#371). */
    private final Map<Integer, Long> lastStatusState = new ConcurrentHashMap<>();
    /** playerId → network ids this player has been sent a spawn for (their interest set). */
    private final Map<Integer, Set<Integer>> interestByPlayer = new ConcurrentHashMap<>();

    /** Reset, then snapshot existing entities so they're tracked + replicable. */
    public void onSessionStart(ServerWorldContext ctx) {
        nextNetworkId.set(1);
        byNetworkId.clear();
        lastBroadcast.clear();
        ticksSinceResync.clear();
        lastAnimState.clear();
        lastVariantState.clear();
        lastStatusState.clear();
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
        lastVariantState.clear();
        lastStatusState.clear();
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
        lastVariantState.remove(id);
        lastStatusState.remove(id);
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
            // Replicate appearance variant on change (shear swap, wool regrowth via grazing).
            broadcastVariantIfChanged(e, ctx);
            // Replicate health fraction + status effects on change (#371).
            broadcastStatusIfChanged(e, ctx);

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
            // Angular difference: the codec wraps yaw to (-360, 360), so the sent base can
            // differ from an unwrapped live yaw by whole turns.
            float dyaw = Math.abs(((yaw - last[3]) % 360f + 540f) % 360f - 180f);

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
                boolean allSent = sendToInterested(ctx, id, new EntityMoveS2C(id, edx, edy, edz, eyaw), true);
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
        Entity e = byNetworkId.get(pkt.targetNetworkId());
        if (!(e instanceof LivingEntity le) || !le.isAlive()) {
            return; // unknown id (despawn raced the hit) or already dead — drop silently
        }
        if (le instanceof RemotePlayer) {
            return; // players and their decoys take no player hits until PvP exists (#343/#374)
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

        // Never credit through the local-player path (on the server thread that would be
        // the HOST's player object regardless of attacker). Credit routes back to the
        // actual attacker via KillCreditS2C — uniform for host (Local channel) and remotes.
        float healthBefore = le.getHealth();
        sp.recordHit(pkt.targetNetworkId(), now);
        le.damage(amount, source, new Vector3f(sp.x(), sp.y(), sp.z()), false);
        float dealt = healthBefore - Math.max(0f, le.getHealth());
        if (dealt > 0f) {
            sendKillCredit(sp, le, dealt, !le.isAlive(), source, kind);
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
        Entity e = byNetworkId.get(pkt.targetNetworkId());
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
        Entity e = byNetworkId.get(pkt.targetNetworkId());
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
        Entity e = byNetworkId.get(pkt.targetNetworkId());
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
        int xp = killed ? victim.getXpReward() : 0;
        if (xp > 0) {
            sp.serverStats().addXp(xp);
            sp.sendProgression();
        }
        // Carries the target's network id, source and kind: the attacker's damage number,
        // hurt sound, Rage/lifesteal (melee) and Quarry (arrows) run off this result (#371/#374).
        sp.send(new com.stonebreak.network.packet.player.KillCreditS2C(
            victim.getType().ordinal(), dealt, killed, xp,
            victim.getNetworkId(), (byte) source.ordinal(), kind.wireId()), false);
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
        }
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
        // Health/status when not the default a fresh shadow starts with (full, no effects).
        if (e instanceof LivingEntity le) {
            EntityStatusS2C status = statusPacketFor(le);
            if (status.statusBits() != 0
                    || EntityStatusS2C.quantize(status.healthFraction()) != EntityStatusS2C.quantize(1f)) {
                sp.send(status, false);
            }
        }
    }

    /** Sends to every player who knows {@code networkId}; false if any send was dropped. */
    private boolean sendToInterested(ServerWorldContext ctx, int networkId, Packet packet, boolean droppable) {
        boolean allSent = true;
        for (ServerPlayer sp : ctx.players()) {
            Set<Integer> known = interestByPlayer.get(sp.playerId());
            if (known != null && known.contains(networkId)) {
                allSent &= sp.send(packet, droppable);
            }
        }
        return allSent;
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
        if (e instanceof LivingEntity le) {
            // Seed the variant tracker with what the EntitySpawnS2C metadata already
            // carried — otherwise the first tick broadcasts one redundant variant
            // packet per living entity.
            lastVariantState.put(e.getNetworkId(), le.getTextureVariant());
            // Likewise the status tracker: the spawn path sends any non-default state itself.
            lastStatusState.put(e.getNetworkId(), statusKey(statusPacketFor(le)));
        }
    }

    private static boolean isReplicable(Entity e) {
        // The instanceof guard keeps player-shaped display entities off the entity channel;
        // the server-spawned illusion decoy (#341) is the one RemotePlayer subclass that rides it.
        return e != null && (!(e instanceof RemotePlayer) || e instanceof com.stonebreak.mobs.entities.IllusionDecoy)
                && e.getType().replicates();
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

    /**
     * Replicates a living entity's appearance variant on change (e.g. a sheep's
     * shear swap or wool regrowth via grazing) as {@link EntityVariantS2C}.
     * Covers the server-side mutation uniformly — {@code handleEntityShear} and
     * the AI-driven regrowth both mutate the real sheep's variant, and this
     * broadcasts it to interested players on the next tick.
     */
    private void broadcastVariantIfChanged(Entity e, ServerWorldContext ctx) {
        if (!(e instanceof LivingEntity le)) {
            return; // drops/projectiles carry no variant
        }
        String variant = le.getTextureVariant();
        int id = e.getNetworkId();
        if (!variant.equals(lastVariantState.get(id))) {
            lastVariantState.put(id, variant);
            sendToInterested(ctx, id, new EntityVariantS2C(id, variant), false);
        }
    }

    /**
     * Replicates a living entity's health fraction and active status effects on change as
     * {@link EntityStatusS2C} (#371): class mechanics read both off the client's shadow (low-HP
     * prey, the Quarry gauge, Flat-Footed crits, Spellmarked/Amplified), and a shadow never
     * simulates either. Change is judged on the quantized wire value, so sub-step regeneration
     * does not resend.
     */
    private void broadcastStatusIfChanged(Entity e, ServerWorldContext ctx) {
        if (!(e instanceof LivingEntity le)) {
            return;
        }
        EntityStatusS2C status = statusPacketFor(le);
        long key = statusKey(status);
        int id = e.getNetworkId();
        Long last = lastStatusState.get(id);
        if (last == null || last != key) {
            lastStatusState.put(id, key);
            sendToInterested(ctx, id, status, false);
        }
    }

    private static EntityStatusS2C statusPacketFor(LivingEntity le) {
        float max = le.getMaxHealth();
        float fraction = max > 0f ? Math.max(0f, le.getHealth()) / max : 0f;
        return new EntityStatusS2C(le.getNetworkId(), fraction, le.getStatusEffectBits());
    }

    /** Change key of a status packet: what actually goes on the wire. */
    private static long statusKey(EntityStatusS2C s) {
        return ((long) EntityStatusS2C.quantize(s.healthFraction()) << 32) | (s.statusBits() & 0xFFFFL);
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
