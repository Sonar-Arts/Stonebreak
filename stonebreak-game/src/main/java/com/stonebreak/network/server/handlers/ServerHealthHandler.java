package com.stonebreak.network.server.handlers;

import com.stonebreak.audio.EntitySounds;
import com.stonebreak.blocks.cactus.CactusContactRules;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.HitKind;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.ai.perception.Loudness;
import com.stonebreak.mobs.entities.ai.perception.NoiseBus;
import com.stonebreak.network.packet.player.KillCreditS2C;
import com.stonebreak.network.packet.player.PlayerDamageC2S;
import com.stonebreak.network.packet.player.PlayerHealthS2C;
import com.stonebreak.network.packet.player.PlayerKnockbackS2C;
import com.stonebreak.network.packet.player.PlayerLifeC2S;
import com.stonebreak.network.packet.player.PlayerPositionS2C;
import com.stonebreak.network.packet.player.PlayerStateFlags;
import com.stonebreak.network.packet.world.PvpStateS2C;
import com.stonebreak.network.packet.world.SoundEventS2C;
import com.stonebreak.network.server.PlayerVitals;
import com.stonebreak.network.server.ServerLevel;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerSounds;
import com.stonebreak.network.server.ServerWorldContext;
import com.stonebreak.network.server.combat.CombatRules;
import com.stonebreak.world.World;
import org.joml.Vector3f;

import java.util.function.BiConsumer;

import static com.stonebreak.player.PlayerConstants.PLAYER_HEIGHT;
import static com.stonebreak.player.PlayerConstants.RAGE_T3_LIFESTEAL_PCT;
import static com.stonebreak.player.PlayerConstants.PLAYER_WIDTH;

/**
 * Server-owned player health (issue #343). Every hit a player takes is resolved here, on the
 * tick thread, against the {@link PlayerVitals} the server holds — falls and cactus contact
 * from the player's own reports and the server's world, mob blows and arrows from the mob AI,
 * and other players' hits when the world allows PvP. The server also decides the death (and
 * scatters the inventory there) and the respawn point. Clients mirror the result
 * ({@link PlayerHealthS2C}, broadcast once per tick on change): the owner applies it to its
 * body, everyone else hides a dead figure.
 */
public final class ServerHealthHandler {

    /** Upward part of a knockback, blocks per second — a shove lifts the player a little. */
    static final float KNOCKBACK_LIFT = 4f;
    /** Push of a landed PvP swing, blocks per second (a mob's melee knockback, LivingEntityCombat). */
    static final float PVP_MELEE_KNOCKBACK = 3f;
    /** Height of a player's voice: chest-high, where a mob's is mid-body. */
    private static final float VOICE_HEIGHT = PLAYER_HEIGHT * 0.5f;

    /** Runs when a player dies, before the broadcast (vehicle dismount). */
    private final BiConsumer<ServerPlayer, ServerWorldContext> onDeath;

    public ServerHealthHandler(BiConsumer<ServerPlayer, ServerWorldContext> onDeath) {
        this.onDeath = onDeath;
    }

    public ServerHealthHandler() {
        this((sp, ctx) -> { });
    }

    // ─── Inputs ─────────────────────────────────────────────────────────────────

    /** An accepted state report: lands any fall it ends. */
    public void onPlayerState(ServerPlayer sp, short flags, long nowNs, ServerWorldContext ctx) {
        if (!sp.isLiveInWorld()) {
            return;
        }
        float fall = sp.vitals().onStateReport(sp.x(), sp.y(), sp.z(), flags,
                sp.seatedVehicle() != null, nowNs);
        if (fall > 0f) {
            hurt(sp, fall, PlayerHealthS2C.CAUSE_FALL, nowNs, ctx);
        }
    }

    /**
     * Once per server tick: maximum health follows the server's own CON score, cactus contact
     * is tested against the server's world, and every change goes out as one broadcast.
     */
    public void tick(ServerWorldContext ctx, float dt, long nowNs) {
        World world = ctx.world();
        for (ServerPlayer sp : ctx.players()) {
            if (!sp.isLiveInWorld()) {
                continue;
            }
            PlayerVitals vitals = sp.vitals();
            vitals.applyMaxHealth(sp.serverStats().computeMaxHealth());
            if (world != null) {
                float half = PLAYER_WIDTH / 2;
                boolean touching = CactusContactRules.touchesCactus(world,
                        sp.x() - half, sp.y(), sp.z() - half,
                        sp.x() + half, sp.y() + PLAYER_HEIGHT, sp.z() + half);
                boolean flying = PlayerStateFlags.has(sp.movementFlags(), PlayerStateFlags.FLYING);
                float cactus = vitals.onCactusContact(touching, flying, dt, nowNs,
                        CactusContactRules.PULSE_INTERVAL, CactusContactRules.DAMAGE_PER_PULSE);
                if (cactus > 0f) {
                    hurt(sp, cactus, PlayerHealthS2C.CAUSE_CACTUS, nowNs, ctx);
                }
            }
        }
        flush(ctx);
    }

    /**
     * PlayerLifeC2S: {@code dead} gives the player's life up (honoured at most once per toss
     * window, {@link ServerPlayer#allowClaimedDeath}); alive asks to respawn, which only a dead
     * player may — at the spot the SERVER picks. The packet's position is not used.
     */
    public void handleLife(ServerPlayer sp, PlayerLifeC2S l, ServerWorldContext ctx, long nowNs) {
        if (l.dead()) {
            if (sp.isLiveInWorld() && sp.allowClaimedDeath()) {
                hurt(sp, sp.vitals().health(), PlayerHealthS2C.CAUSE_SELF, nowNs, ctx);
            }
        } else if (sp.isDead()) {
            respawn(sp, ctx, nowNs);
        }
    }

    /**
     * A joining player: the world's PvP rule, everyone's health (dead figures stay hidden), its
     * own. Spawn protection starts with its first position report, not here — the client may
     * still be building its world.
     */
    public void onPeerJoined(ServerPlayer joined, ServerWorldContext ctx) {
        ServerLevel level = ctx.serverLevel();
        joined.send(new PvpStateS2C(level != null && level.pvpEnabled()), false);
        for (ServerPlayer other : ctx.players()) {
            if (other != joined && other.handshakeDone()) {
                joined.send(healthPacket(other, PlayerHealthS2C.CAUSE_NONE), false);
            }
        }
        joined.vitals().applyMaxHealth(joined.serverStats().computeMaxHealth());
        joined.vitals().markDirty();
    }

    /**
     * PlayerDamageC2S: one player hit another (PvP). Refused unless the world allows PvP and
     * both stand in it; then held to the same rules as a hit on a mob — melee reach from the
     * server's positions, the swing cooldown and the held weapon's cap, or the class's ability
     * profile. The applied result goes back to the attacker as a {@link KillCreditS2C}.
     */
    public void handlePlayerDamage(ServerPlayer attacker, PlayerDamageC2S hit, ServerWorldContext ctx) {
        ServerLevel level = ctx.serverLevel();
        ServerPlayer target = ctx.player(hit.targetPlayerId());
        if (level == null || !level.pvpEnabled() || target == null || target == attacker
                || !attacker.isLiveInWorld() || !target.isLiveInWorld()) {
            return;
        }
        float amount = hit.amount();
        if (!(amount > 0f) || !Float.isFinite(amount)) {
            return;
        }
        HitKind kind = HitKind.fromWire(hit.hitKind());
        long now = System.nanoTime();
        float cap;
        LivingEntity.DamageSource source = LivingEntity.DamageSource.PLAYER;
        if (kind == HitKind.MELEE) {
            if (!CombatRules.withinMeleeReach(attacker.x(), attacker.y(), attacker.z(), bodyBox(target))
                    || !attacker.combat().tryMelee(now)) {
                return;
            }
            cap = CombatRules.maxMeleeDamage(attacker.inventory().heldItemId(),
                    attacker.serverStats().getSelectedClassId());
        } else if (kind == HitKind.ABILITY) {
            CombatRules.AbilityHitProfile profile =
                    CombatRules.abilityProfile(attacker.serverStats().getSelectedClassId());
            if (profile == null || !profile.unlockedFor(attacker.serverStats())
                    || distanceSq(attacker, target) > profile.maxRange() * profile.maxRange()
                    || !attacker.combat().tryAbilityHit(profile, now)) {
                return;
            }
            cap = profile.maxDamage();
            if (hit.sourceOrdinal() == LivingEntity.DamageSource.ARCANE.ordinal()) {
                source = LivingEntity.DamageSource.ARCANE;
            }
        } else {
            return; // projectiles are simulated by the server, never claimed
        }
        float dealt = hurt(target, Math.min(amount, cap), PlayerHealthS2C.CAUSE_PLAYER, now, ctx);
        if (dealt <= 0f) {
            return;
        }
        if (kind == HitKind.MELEE) {
            knockback(target, new Vector3f(attacker.x(), attacker.y(), attacker.z()), PVP_MELEE_KNOCKBACK);
            applyLifesteal(attacker, dealt);
        }
        credit(attacker, target, dealt, source, kind);
    }

    /**
     * A player's arrow inside another player: lands only where the world allows PvP (otherwise
     * it flies on). The shooter, when still connected, gets the hit's result.
     *
     * @return whether the arrow is spent
     */
    public boolean hitByPlayerProjectile(ServerPlayer shooter, ServerPlayer target, float amount,
                                         ServerWorldContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null || !level.pvpEnabled()) {
            return false;
        }
        float dealt = hurt(target, amount, PlayerHealthS2C.CAUSE_PLAYER, System.nanoTime(), ctx);
        if (dealt > 0f && shooter != null) {
            credit(shooter, target, dealt, LivingEntity.DamageSource.ARROW, HitKind.PROJECTILE);
        }
        return true;
    }

    /**
     * Rage T3 lifesteal on a melee hit that dealt {@code dealt} (issue #343): the attacker's
     * client reports the tier ({@code LIFESTEAL}); only a Berserker's report is honoured. Rage
     * lives on the client alone, so this is the one heal a client's word decides — bounded by
     * the hit the server itself applied.
     */
    public static void applyLifesteal(ServerPlayer attacker, float dealt) {
        if (dealt > 0f && !attacker.isDead()
                && PlayerStateFlags.has(attacker.movementFlags(), PlayerStateFlags.LIFESTEAL)
                && com.stonebreak.player.combat.berserker.BerserkerAbilityController.CLASS_ID
                        .equals(attacker.serverStats().getSelectedClassId())) {
            attacker.vitals().heal(dealt * RAGE_T3_LIFESTEAL_PCT);
        }
    }

    /** Whether {@code point} is inside {@code player}'s body, grown by {@code margin}. */
    public static boolean insideBody(ServerPlayer player, Vector3f point, float margin) {
        float halfWidth = PLAYER_WIDTH * 0.5f + margin;
        return Math.abs(point.x - player.x()) <= halfWidth
                && Math.abs(point.z - player.z()) <= halfWidth
                && point.y >= player.y() - margin
                && point.y <= player.y() + PLAYER_HEIGHT + margin;
    }

    // ─── Outcomes ───────────────────────────────────────────────────────────────

    /**
     * {@code amount} of {@code cause} damage to {@code sp}; returns what it took (a dead or
     * unpositioned player takes nothing, a dodge negates combat damage). A blow that empties
     * the health kills.
     */
    public float hurt(ServerPlayer sp, float amount, byte cause, long nowNs, ServerWorldContext ctx) {
        if (!sp.isLiveInWorld()) {
            return 0f;
        }
        float taken = sp.vitals().damage(amount, cause, nowNs);
        if (taken <= 0f) {
            return 0f;
        }
        boolean dies = sp.vitals().health() <= 0f;
        voice(sp, dies ? EntitySounds.EVENT_DEATH : EntitySounds.EVENT_HURT, ctx);
        if (dies) {
            kill(sp, ctx);
        }
        return taken;
    }

    /** A blow's push, horizontally away from {@code from} at {@code speed} blocks per second. */
    public void knockback(ServerPlayer sp, Vector3f from, float speed) {
        if (!(speed > 0f) || sp.isDead()) {
            return;
        }
        float dx = sp.x() - from.x;
        float dz = sp.z() - from.z;
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (len < 0.01f) {
            return; // standing on the attacker: no direction to push in
        }
        sp.send(new PlayerKnockbackS2C(dx / len * speed, KNOCKBACK_LIFT, dz / len * speed), false);
    }

    /** Heals a living player (food, lifesteal); returns what was restored. */
    public float heal(ServerPlayer sp, float amount) {
        return sp.isDead() ? 0f : sp.vitals().heal(amount);
    }

    /**
     * The player dies where the server holds them: dead before anything else (no pickups, no
     * mob attention), out of any vehicle, and the inventory the server holds is scattered at the
     * corpse — the client only clears its mirror (#342).
     */
    void kill(ServerPlayer sp, ServerWorldContext ctx) {
        sp.setDead(true);
        sp.vitals().markDirty();
        onDeath.accept(sp, ctx);
        ServerPlayerHandler.scatterInventory(sp, ctx);
        NoiseBus.emitIn(ctx.world(), new Vector3f(sp.x(), sp.y(), sp.z()), Loudness.DEATH); // #321
    }

    /**
     * Back at the world spawn with full health and a moment of spawn protection. The server
     * picks the spot and puts the client there; the client's own prediction (the same spawn)
     * normally makes that a no-op.
     */
    void respawn(ServerPlayer sp, ServerWorldContext ctx, long nowNs) {
        Vector3f spawn = ctx.spawn();
        sp.setDead(false);
        sp.vitals().applyMaxHealth(sp.serverStats().computeMaxHealth());
        sp.vitals().restore(nowNs);
        sp.updateState(spawn.x, spawn.y, spawn.z, sp.yaw(), sp.pitch());
        sp.movement().reset(nowNs);
        sp.send(new PlayerPositionS2C(spawn.x, spawn.y, spawn.z), false);
    }

    /** Broadcasts the health of every player whose health changed since the last flush. */
    void flush(ServerWorldContext ctx) {
        for (ServerPlayer sp : ctx.players()) {
            if (sp.handshakeDone() && sp.vitals().takeDirty()) {
                ctx.broadcast(healthPacket(sp, sp.vitals().takeCause()), false);
            }
        }
    }

    /** The attacker's result for a hit on another player: number, Rage, lifesteal on their side. */
    private static void credit(ServerPlayer attacker, ServerPlayer target, float dealt,
                               LivingEntity.DamageSource source, HitKind kind) {
        attacker.send(new KillCreditS2C(EntityType.REMOTE_PLAYER.ordinal(), dealt, target.isDead(), 0, -1,
                (byte) source.ordinal(), kind.wireId(),
                target.x(), target.y() + PLAYER_HEIGHT * 0.9f, target.z()), false);
    }

    private static Entity.BoundingBox bodyBox(ServerPlayer p) {
        float half = PLAYER_WIDTH / 2;
        return new Entity.BoundingBox(p.x() - half, p.y(), p.z() - half,
                p.x() + half, p.y() + PLAYER_HEIGHT, p.z() + half);
    }

    private static float distanceSq(ServerPlayer a, ServerPlayer b) {
        float dx = a.x() - b.x();
        float dy = a.y() - b.y();
        float dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }

    static PlayerHealthS2C healthPacket(ServerPlayer sp, byte cause) {
        PlayerVitals v = sp.vitals();
        return new PlayerHealthS2C(sp.playerId(), v.health(), v.maxHealth(), sp.isDead(), cause);
    }

    /** The player model's own hurt/death voice, heard by everyone near — the victim too. */
    private static void voice(ServerPlayer sp, String event, ServerWorldContext ctx) {
        ServerSounds.sendNear(ctx, SoundEventS2C.entity(EntityType.REMOTE_PLAYER.ordinal(), event,
                sp.x(), sp.y() + VOICE_HEIGHT, sp.z()), null);
    }
}
