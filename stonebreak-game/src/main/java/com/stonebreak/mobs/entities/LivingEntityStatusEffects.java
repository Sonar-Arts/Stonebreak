package com.stonebreak.mobs.entities;

import com.stonebreak.mobs.entities.LivingEntity.DamageSource;
import com.stonebreak.mobs.entities.status.StatusEffect;
import com.stonebreak.mobs.entities.status.StatusEffectBits;
import com.stonebreak.mobs.entities.status.StatusEffectType;
import com.stonebreak.player.PlayerConstants;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Status-effect component of a {@link LivingEntity}: owns the list of timed debuffs (burning,
 * bleed, stun, root, armor break, ...), ticks and prunes them, folds DOT ticks into a single
 * {@code damage()} call, and answers the derived queries (stunned/rooted, incoming-damage and
 * move-speed multipliers). Extracted from {@code LivingEntity} (issue #233); the entity remains
 * the public facade.
 *
 * <p>On a network shadow the list stays empty (effects are applied to the real mob on the
 * server, #339); what the shadow reports instead is the server's replicated set
 * ({@link #setReplicatedBits}, #371), which every query folds in.
 */
final class LivingEntityStatusEffects {

    /**
     * A shadow re-forwards the same effect at most this often unless it got stronger or
     * longer: zones (Rampage trail, Skull Crusher crater) re-apply every frame while a mob
     * stands in them, and the server's refresh keeps the longest duration anyway.
     */
    static final long FORWARD_COALESCE_NANOS = 500_000_000L;

    private static final int TYPE_COUNT = StatusEffectType.values().length;

    private final LivingEntity owner;
    private final List<StatusEffect> statusEffects = new ArrayList<>();

    /** Shadow only: the server's active-effect set (StatusEffectBits). */
    private int replicatedBits;

    /** Shadow only: last forwarded application per effect type (lazily allocated). */
    private long[] lastForwardNanos;
    private float[] lastForwardDuration;
    private float[] lastForwardMagnitude;

    LivingEntityStatusEffects(LivingEntity owner) {
        this.owner = owner;
    }

    /** Applies (or refreshes) a timed debuff. Same-type effects are refreshed rather than stacked. */
    void apply(StatusEffectType type, float duration, float magnitude) {
        if (!owner.alive) return;
        for (StatusEffect existing : statusEffects) {
            if (existing.getType() == type) {
                existing.refresh(duration, resolveRefreshMagnitude(existing, magnitude));
                return;
            }
        }
        statusEffects.add(new StatusEffect(type, duration, magnitude));
    }

    /**
     * The magnitude an existing effect should carry after a re-application. DOTs and SHAKEN
     * adopt the latest application's value, so their strength tracks the most recent
     * application — e.g. an Illusionist's SHAKEN hesitation grows as Doubt stacks rise
     * (issue #232) and a re-applied burn ticks at its own rate. Potency bonuses are
     * strongest-wins, so re-applying with a weaker source can never weaken an active debuff.
     */
    private static float resolveRefreshMagnitude(StatusEffect existing, float magnitude) {
        return switch (existing.getType()) {
            case ARMOR_BREAK, AMPLIFIED, CRIPPLE, EXPOSED ->
                Math.max(existing.getMagnitude(), magnitude);
            default -> magnitude;
        };
    }

    // Tick and prune first so damage()/onDamage() (which may itself touch statusEffects,
    // e.g. via applyStatusEffect) never runs while we're iterating the live list.
    void update(float deltaTime) {
        if (statusEffects.isEmpty()) return;

        float burningTickDamage = 0f;
        float bleedTickDamage = 0f;
        Iterator<StatusEffect> it = statusEffects.iterator();
        while (it.hasNext()) {
            StatusEffect effect = it.next();
            boolean dotTick = effect.tick(deltaTime);
            if (dotTick && effect.getType() == StatusEffectType.BURNING) {
                burningTickDamage += effect.getMagnitude() * StatusEffect.DOT_TICK_INTERVAL;
            }
            if (dotTick && effect.getType() == StatusEffectType.BLEED) {
                bleedTickDamage += effect.getMagnitude() * StatusEffect.DOT_TICK_INTERVAL;
            }
            if (effect.isExpired()) {
                it.remove();
            }
        }

        // Combine concurrent DOT ticks into a single damage() call — the 0.5s
        // invulnerability window would otherwise swallow the second application.
        float totalTickDamage = burningTickDamage + bleedTickDamage;
        if (totalTickDamage > 0f && owner.alive) {
            DamageSource source;
            if (bleedTickDamage <= 0f) {
                source = DamageSource.FIRE;
            } else if (burningTickDamage <= 0f) {
                source = DamageSource.BLEED;
            } else {
                source = DamageSource.UNKNOWN;
            }
            // Never credits the local player: on the server this ticks the real mob, where
            // the 2-arg path would resolve the HOST's client Player from the tick thread.
            owner.damage(totalTickDamage, source, null, false);
        }
    }

    /** Removes any active status effect of the given type (no-op if absent). */
    void remove(StatusEffectType type) {
        statusEffects.removeIf(effect -> effect.getType() == type);
    }

    /** True while a status effect of the given type is active (locally or, on a shadow, on the server). */
    boolean has(StatusEffectType type) {
        if (StatusEffectBits.has(replicatedBits, type)) {
            return true;
        }
        for (StatusEffect effect : statusEffects) {
            if (effect.getType() == type) {
                return true;
            }
        }
        return false;
    }

    /** The active-effect set as a {@link StatusEffectBits} bitset (what the server replicates). */
    int activeBits() {
        int bits = replicatedBits;
        for (StatusEffect effect : statusEffects) {
            bits |= StatusEffectBits.bit(effect.getType());
        }
        return bits;
    }

    /** Shadow: adopts the server's replicated active-effect set. */
    void setReplicatedBits(int bits) {
        this.replicatedBits = StatusEffectBits.sanitize(bits);
    }

    /**
     * Shadow: whether an application should be forwarded to the server now — false for a
     * repeat of the same effect inside {@link #FORWARD_COALESCE_NANOS} that is neither
     * longer nor stronger than the last one sent. Records the forward when it returns true.
     */
    boolean shouldForward(StatusEffectType type, float duration, float magnitude, long nowNanos) {
        if (lastForwardNanos == null) {
            lastForwardNanos = new long[TYPE_COUNT];
            lastForwardDuration = new float[TYPE_COUNT];
            lastForwardMagnitude = new float[TYPE_COUNT];
            java.util.Arrays.fill(lastForwardNanos, Long.MIN_VALUE);
        }
        int i = type.ordinal();
        boolean recent = lastForwardNanos[i] != Long.MIN_VALUE
                && nowNanos - lastForwardNanos[i] < FORWARD_COALESCE_NANOS;
        if (recent && duration <= lastForwardDuration[i] && magnitude <= lastForwardMagnitude[i]) {
            return false;
        }
        lastForwardNanos[i] = nowNanos;
        lastForwardDuration[i] = duration;
        lastForwardMagnitude[i] = magnitude;
        return true;
    }

    /** Magnitude of the active effect of the given type, or {@code 0f} if none is active. */
    float magnitude(StatusEffectType type) {
        for (StatusEffect effect : statusEffects) {
            if (effect.getType() == type) {
                return effect.getMagnitude();
            }
        }
        return 0f;
    }

    /** True while any STUNNED effect is active — suppresses AI updates. */
    boolean isStunned() {
        return has(StatusEffectType.STUNNED);
    }

    /** True while any ROOT (or STUNNED — stun implies immobility) effect is active. */
    boolean isRooted() {
        return has(StatusEffectType.ROOT) || has(StatusEffectType.STUNNED);
    }

    /** Multiplier applied to incoming damage; {@code 1.0} with no Armor Break active, higher otherwise. */
    float armorBreakDamageMultiplier() {
        float bonus = 0f;
        for (StatusEffect effect : statusEffects) {
            if (effect.getType() == StatusEffectType.ARMOR_BREAK) {
                bonus = Math.max(bonus, effect.getMagnitude());
            }
        }
        return 1f + bonus;
    }

    /**
     * Combined multiplier applied to all incoming damage: Armor Break composed with Exposed
     * (multiplicative across the two debuff types, max-of-magnitude within each); magical
     * sources are additionally amplified by any active Amplified debuff. Pure — Spellmarked
     * consumption (a mutation) is {@link #consumeSpellmark()}.
     */
    float incomingDamageMultiplier(DamageSource source) {
        float exposedBonus = 0f;
        float amplifiedBonus = 0f;
        for (StatusEffect effect : statusEffects) {
            if (effect.getType() == StatusEffectType.EXPOSED) {
                exposedBonus = Math.max(exposedBonus, effect.getMagnitude());
            }
            if (effect.getType() == StatusEffectType.AMPLIFIED) {
                amplifiedBonus = Math.max(amplifiedBonus, effect.getMagnitude());
            }
        }
        float multiplier = armorBreakDamageMultiplier() * (1f + exposedBonus);
        if (source.isMagical()) {
            multiplier *= 1f + amplifiedBonus;
        }
        return multiplier;
    }

    /**
     * Consumes an active Spellmarked debuff: removes it and returns the one-shot bonus
     * multiplier for the arcane hit that triggered it, or {@code 1.0} when unmarked.
     */
    float consumeSpellmark() {
        Iterator<StatusEffect> it = statusEffects.iterator();
        while (it.hasNext()) {
            if (it.next().getType() == StatusEffectType.SPELLMARKED) {
                it.remove();
                return 1f + PlayerConstants.SPELLMARKED_BONUS_DAMAGE_MULT;
            }
        }
        return 1f;
    }

    /** Multiplier applied to movement speed; {@code 1.0} with no Cripple active, lower otherwise. */
    float moveSpeedMultiplier() {
        float reduction = 0f;
        for (StatusEffect effect : statusEffects) {
            if (effect.getType() == StatusEffectType.CRIPPLE) {
                reduction = Math.max(reduction, effect.getMagnitude());
            }
        }
        return Math.max(0f, 1f - reduction);
    }
}
