package com.stonebreak.network.server.combat;

import java.util.HashMap;
import java.util.Map;

/**
 * One player's combat clocks and budgets (#339): the melee swing cooldown and token buckets
 * for ability hits, status intents and projectile/zone spawns. Owned by the player's
 * {@code ServerPlayer} and touched only on the server tick thread. The limits themselves are
 * {@link CombatRules}; a refused action consumes nothing.
 */
public final class CombatLimiter {

    private long lastMeleeNanos;
    private boolean meleeSeen;

    /** Ability budget; rebuilt when the class's profile changes (class switch). */
    private CombatRules.AbilityHitProfile abilityProfile;
    private TokenBucket abilityHits;

    private final TokenBucket statusIntents = new TokenBucket(
            CombatRules.STATUS_INTENT_BURST, CombatRules.STATUS_INTENT_REFILL_PER_SECOND);

    private final Map<Byte, TokenBucket> projectileSpawns = new HashMap<>();

    /** Accepts a melee hit at most once per {@link CombatRules#MELEE_MIN_INTERVAL_NANOS}. */
    public boolean tryMelee(long nowNanos) {
        if (meleeSeen && nowNanos - lastMeleeNanos < CombatRules.MELEE_MIN_INTERVAL_NANOS) {
            return false;
        }
        meleeSeen = true;
        lastMeleeNanos = nowNanos;
        return true;
    }

    private long lastDecoyNanos;
    private boolean decoySeen;

    /** Accepts a decoy cast at most once per {@link CombatRules#DECOY_MIN_INTERVAL_NANOS}. */
    public boolean tryDecoyCast(long nowNanos) {
        if (decoySeen && nowNanos - lastDecoyNanos < CombatRules.DECOY_MIN_INTERVAL_NANOS) {
            return false;
        }
        decoySeen = true;
        lastDecoyNanos = nowNanos;
        return true;
    }

    /** Spends one ability hit from {@code profile}'s budget. */
    public boolean tryAbilityHit(CombatRules.AbilityHitProfile profile, long nowNanos) {
        if (profile != abilityProfile) {
            abilityProfile = profile;
            abilityHits = new TokenBucket(profile.burst(), profile.refillPerSecond());
        }
        return abilityHits.tryTake(nowNanos);
    }

    /** Spends one status intent. */
    public boolean tryStatusIntent(long nowNanos) {
        return statusIntents.tryTake(nowNanos);
    }

    /** Spends one spawn of {@code kind} from its rule's budget. */
    public boolean tryProjectile(byte kind, CombatRules.ProjectileRule rule, long nowNanos) {
        return projectileSpawns
                .computeIfAbsent(kind, k -> new TokenBucket(rule.burst(), rule.refillPerSecond()))
                .tryTake(nowNanos);
    }
}
