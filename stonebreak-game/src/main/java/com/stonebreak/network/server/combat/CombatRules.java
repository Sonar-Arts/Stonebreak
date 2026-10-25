package com.stonebreak.network.server.combat;

import static com.stonebreak.player.PlayerConstants.*;

import com.stonebreak.items.ItemType;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.status.StatusEffectType;
import com.stonebreak.network.packet.entity.ProjectileSpawnC2S;
import com.stonebreak.player.CharacterStats;
import com.stonebreak.player.combat.arcanist.ArcanistAbilityController;
import com.stonebreak.player.combat.berserker.BerserkerAbilityController;
import com.stonebreak.player.combat.illusionist.IllusionistAbilityController;
import com.stonebreak.player.combat.ranger.RangerAbilityController;
import com.stonebreak.player.combat.rogue.RogueAbilityController;
import com.stonebreak.player.interaction.RaycastEngine;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the server accepts from a client's combat intents (#339), as data. Every limit is
 * derived from the same gameplay constants the client abilities use, plus an explicit
 * latency/jitter allowance — so tuning an ability can never silently desync its server check.
 * Stateless; the per-player clocks and budgets live in {@link CombatLimiter}.
 *
 * <ul>
 *   <li><b>Melee</b> — reach from the server's own player position, a swing cooldown, and a
 *       damage cap from the SERVER-known held item.</li>
 *   <li><b>Ability hits</b> — per-class profile (only classes whose abilities deal direct
 *       client-resolved damage have one): damage cap, range, hit budget, unlocked ability.</li>
 *   <li><b>Status effects</b> — per-effect allow-list: which classes may apply it, its max
 *       duration/magnitude and range. Server-only effects (Amplified, Spellmarked) are absent.</li>
 *   <li><b>Projectile/zone spawns</b> — per-kind requirement (held item or class ability),
 *       live cap per owner and a spawn budget that mirrors the client cooldown.</li>
 * </ul>
 */
public final class CombatRules {

    private CombatRules() {}

    // ─── Melee ──────────────────────────────────────────────────────────────────

    /** Extra reach for movement during the round trip and 20 Hz position reports (blocks). */
    public static final float MELEE_LATENCY_MARGIN = 2.5f;

    /** Client reach (eye to target box) plus the latency allowance. */
    public static final float MELEE_MAX_REACH = RaycastEngine.ENTITY_ATTACK_RANGE + MELEE_LATENCY_MARGIN;

    /** Accepted swings may arrive this much closer together than the fastest swing (jitter). */
    private static final float SWING_JITTER_ALLOWANCE = 0.75f;

    /**
     * Shortest accepted interval between two melee hits: the fastest swing (Rage T2 attack
     * speed) minus a jitter allowance — about 0.14 s.
     */
    public static final long MELEE_MIN_INTERVAL_NANOS = (long) (ATTACK_ANIMATION_DURATION
            / (1f + RAGE_T2_ATTACK_SPEED_BONUS) * SWING_JITTER_ALLOWANCE * 1_000_000_000L);

    /**
     * Strongest melee multiplier any class can stack on a weapon's base damage: Rage T1+ and a
     * crit. The client applies the Rage bonus whatever the class, so the cap does too.
     */
    public static final float MAX_MELEE_MULTIPLIER = (1f + RAGE_T1_DAMAGE_BONUS) * PLAYER_CRIT_MULTIPLIER;

    /** Base melee damage of the item the server says is held — the client's {@code Player.getAttackDamage} rule. */
    public static float weaponBaseDamage(int heldItemId) {
        ItemType item = ItemType.getById(heldItemId);
        return item != null ? item.getDamage() : 1.0f;
    }

    /**
     * Largest melee hit the server accepts with {@code heldItemId} in hand from a {@code classId}
     * character: a Rogue's crit may also spend full Momentum ({@code RogueAbilityController.onCritLanded}).
     */
    public static float maxMeleeDamage(int heldItemId, String classId) {
        float momentum = RogueAbilityController.CLASS_ID.equals(classId) ? MOMENTUM_T3_MULTIPLIER : 1f;
        return weaponBaseDamage(heldItemId) * MAX_MELEE_MULTIPLIER * momentum;
    }

    /** True when the target's box is within melee reach of the player's eye at {@code (px,py,pz)} (feet). */
    public static boolean withinMeleeReach(float px, float py, float pz, Entity.BoundingBox box) {
        float ex = px;
        float ey = py + CAMERA_EYE_OFFSET;
        float ez = pz;
        float dx = Math.max(0f, Math.max(box.minX - ex, ex - box.maxX));
        float dy = Math.max(0f, Math.max(box.minY - ey, ey - box.maxY));
        float dz = Math.max(0f, Math.max(box.minZ - ez, ez - box.maxZ));
        return dx * dx + dy * dy + dz * dz <= MELEE_MAX_REACH * MELEE_MAX_REACH;
    }

    // ─── Decoy cast (Mirrored Deceit) ───────────────────────────────────────────

    /** Decoy casts may come this fraction of the client's interval apart (latency jitter). */
    private static final float DECOY_JITTER_ALLOWANCE = 0.8f;

    /**
     * Shortest accepted interval between two decoy casts. The client's cooldown starts once the
     * decoys are gone, so a cast blocks the next one for the decoys' life plus the cooldown.
     */
    public static final long DECOY_MIN_INTERVAL_NANOS = (long) ((ILLUSIONIST_DECOY_DURATION
            + ILLUSIONIST_MIRRORED_DECEIT_COOLDOWN) * DECOY_JITTER_ALLOWANCE * 1_000_000_000L);

    /** An Illusionist with Mirrored Deceit unlocked — the client's own gate. */
    public static boolean mayCastDecoy(CharacterStats stats) {
        return IllusionistAbilityController.CLASS_ID.equals(stats.getSelectedClassId())
                && stats.hasAbility(IllusionistAbilityController.MIRRORED_DECEIT_KEY);
    }

    // ─── Ability hits ───────────────────────────────────────────────────────────

    /** Extra range for ability hits: the caster moves while charging/dashing (blocks). */
    public static final float ABILITY_LATENCY_MARGIN = 4f;

    /** Ability budgets refill at this fraction of the client cooldown (latency jitter). */
    private static final float COOLDOWN_JITTER_ALLOWANCE = 0.8f;

    /**
     * What one class's abilities may claim per hit.
     *
     * @param maxDamage       cap on one ability hit
     * @param maxRange        player position → target position (blocks)
     * @param burst           hits accepted back to back (an AoE sweep)
     * @param refillPerSecond sustained hit rate
     * @param abilityKeys     at least one of these must be unlocked (CP spent)
     */
    public record AbilityHitProfile(float maxDamage, float maxRange, int burst, float refillPerSecond,
                                    List<String> abilityKeys) {
        public boolean unlockedFor(CharacterStats stats) {
            for (String key : abilityKeys) {
                if (stats.hasAbility(key)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final Map<String, AbilityHitProfile> ABILITY_PROFILES = Map.of(
        // Rampage (charge + T3 cleave) and Skull Crusher (slam + shockwave).
        BerserkerAbilityController.CLASS_ID, new AbilityHitProfile(
            Math.max(SKULLCRUSHER_DAMAGE, Math.max(RAMPAGE_DAMAGE, SKULLCRUSHER_SHOCKWAVE_DAMAGE)),
            Math.max(RAMPAGE_T1_RANGE + RAMPAGE_MAX_CLEAVE_HALF_WIDTH,
                     SKULLCRUSHER_RANGE + SKULLCRUSHER_SHOCKWAVE_RADIUS) + ABILITY_LATENCY_MARGIN,
            16, 2f,
            List.of(BerserkerAbilityController.RAMPAGE_KEY, BerserkerAbilityController.SKULL_CRUSHER_KEY)),
        // Culling Shot: weak-point bonus, then the Marked Prey crit.
        RangerAbilityController.CLASS_ID, new AbilityHitProfile(
            (RANGER_CULLING_BASE_DAMAGE + RANGER_CULLING_WEAKPOINT_BONUS) * RANGER_CRIT_MULTIPLIER,
            RANGER_CULLING_RANGE + ABILITY_LATENCY_MARGIN,
            2, 1f / (RANGER_CULLING_COOLDOWN * COOLDOWN_JITTER_ALLOWANCE),
            List.of(RangerAbilityController.CULLING_SHOT_KEY)));

    /** The ability-hit profile of {@code classId}, or null when that class has no direct ability hits. */
    public static AbilityHitProfile abilityProfile(String classId) {
        return classId != null ? ABILITY_PROFILES.get(classId) : null;
    }

    // ─── Status effects ─────────────────────────────────────────────────────────

    /** Snare traps and decoys act away from the caster; this bounds how far (blocks). */
    public static final float STATUS_INTENT_MAX_RANGE = 48f;

    /** Status intents: burst and sustained rate per player (zones re-apply while mobs stand in them). */
    public static final int STATUS_INTENT_BURST = 64;
    public static final float STATUS_INTENT_REFILL_PER_SECOND = 32f;

    /**
     * One client-appliable effect.
     *
     * @param classes classes allowed to apply it; empty = any class
     */
    public record StatusIntentRule(float maxDuration, float maxMagnitude, float maxRange, Set<String> classes) {
        public boolean allowsClass(String classId) {
            return classes.isEmpty() || (classId != null && classes.contains(classId));
        }
    }

    private static final Map<StatusEffectType, StatusIntentRule> STATUS_RULES = buildStatusRules();

    private static Map<StatusEffectType, StatusIntentRule> buildStatusRules() {
        String berserker = BerserkerAbilityController.CLASS_ID;
        String ranger = RangerAbilityController.CLASS_ID;
        String arcanist = ArcanistAbilityController.CLASS_ID;
        String illusionist = IllusionistAbilityController.CLASS_ID;
        String rogue = RogueAbilityController.CLASS_ID;
        float fractureMaxStun = ILLUSIONIST_FRACTURE_STUN_BASE
                + (ILLUSIONIST_DOUBT_MAX_STACKS - 1) * ILLUSIONIST_FRACTURE_STUN_PER_STACK;

        Map<StatusEffectType, StatusIntentRule> m = new EnumMap<>(StatusEffectType.class);
        // Rampage T3 stagger, Skull Crusher T2 shockwave, Fracture.
        m.put(StatusEffectType.STUNNED, new StatusIntentRule(
            Math.max(fractureMaxStun, Math.max(RAMPAGE_STAGGER_DURATION, SKULLCRUSHER_STUN_DURATION)), 0f,
            ILLUSIONIST_FRACTURE_RANGE + ABILITY_LATENCY_MARGIN, Set.of(berserker, illusionist)));
        // Rampage T2 burning trail.
        m.put(StatusEffectType.BURNING, new StatusIntentRule(
            RAMPAGE_TRAIL_DURATION, RAMPAGE_TRAIL_TICK_DAMAGE, STATUS_INTENT_MAX_RANGE, Set.of(berserker)));
        // Skull Crusher T3 crater.
        m.put(StatusEffectType.ARMOR_BREAK, new StatusIntentRule(
            SKULLCRUSHER_ARMOR_BREAK_DURATION, SKULLCRUSHER_ARMOR_BREAK_MAGNITUDE, STATUS_INTENT_MAX_RANGE,
            Set.of(berserker)));
        // Snare (studied Quarry gets the extension), overloaded Leyline Breach.
        m.put(StatusEffectType.ROOT, new StatusIntentRule(
            Math.max(RANGER_SNARE_ROOT_DURATION + RANGER_SNARE_ROOT_EXTENSION, LEYLINE_BREACH_ROOT_DURATION), 0f,
            STATUS_INTENT_MAX_RANGE, Set.of(ranger, arcanist)));
        m.put(StatusEffectType.EXPOSED, new StatusIntentRule(
            RANGER_EXPOSED_DURATION, RANGER_EXPOSED_MAGNITUDE, STATUS_INTENT_MAX_RANGE, Set.of(ranger)));
        // Culling Shot, Rogue Momentum T2.
        m.put(StatusEffectType.BLEED, new StatusIntentRule(
            Math.max(RANGER_BLEED_DURATION, STEALTH_BLEED_DURATION),
            Math.max(RANGER_BLEED_DPS, STEALTH_BLEED_DAMAGE_PER_TICK),
            STATUS_INTENT_MAX_RANGE, Set.of(ranger, rogue)));
        // Culling Shot at Marked Prey, Rogue Momentum T3, a destroyed decoy.
        m.put(StatusEffectType.CRIPPLE, new StatusIntentRule(
            Math.max(RANGER_CRIPPLE_DURATION, Math.max(STEALTH_CRIPPLE_DURATION, ILLUSIONIST_DECOY_HIT_SLOW_DURATION)),
            Math.max(RANGER_CRIPPLE_MAGNITUDE, Math.max(STEALTH_CRIPPLE_SLOW_PERCENT, ILLUSIONIST_DECOY_HIT_SLOW_MAG)),
            STATUS_INTENT_MAX_RANGE, Set.of(ranger, rogue, illusionist)));
        // Doubt stacks.
        m.put(StatusEffectType.SHAKEN, new StatusIntentRule(
            ILLUSIONIST_SHAKEN_DURATION, ILLUSIONIST_DOUBT_MAX_STACKS * ILLUSIONIST_SHAKEN_ATTACK_DELAY,
            STATUS_INTENT_MAX_RANGE, Set.of(illusionist)));
        // A destroyed decoy reveals its killer.
        m.put(StatusEffectType.REVEALED, new StatusIntentRule(
            ILLUSIONIST_DECOY_REVEAL_DURATION, 0f, STATUS_INTENT_MAX_RANGE, Set.of(illusionist)));
        // Any class's stealth opener; Shadow Step.
        m.put(StatusEffectType.FLAT_FOOTED, new StatusIntentRule(
            FLAT_FOOTED_DURATION, 0f, SHADOW_STEP_RANGE + ABILITY_LATENCY_MARGIN, Set.of()));
        // AMPLIFIED / SPELLMARKED: applied by server-simulated zones/projectiles only.
        return m;
    }

    /** The rule for a client-applied {@code type}, or null when clients may not apply it. */
    public static StatusIntentRule statusRule(StatusEffectType type) {
        return type != null ? STATUS_RULES.get(type) : null;
    }

    // ─── Projectile / zone spawns ───────────────────────────────────────────────

    /**
     * What one {@code ProjectileSpawnC2S} kind requires.
     *
     * @param liveType        the spawned entity's type (live cap counts these)
     * @param maxLive         most live entities of this type one player may own
     * @param burst           spawns accepted back to back (Caltrop Scatter sends its whole fan)
     * @param refillPerSecond sustained spawn rate — the client cooldown, with jitter allowance
     * @param heldItemId      item that must be held (-1: none)
     * @param classId         class that must be selected (null: any)
     * @param abilityKey      ability that must be unlocked (null: none)
     */
    public record ProjectileRule(EntityType liveType, int maxLive, int burst, float refillPerSecond,
                                 int heldItemId, String classId, String abilityKey) {
        /** True when the player's server-known held item and class satisfy this kind. */
        public boolean allows(int playerHeldItemId, CharacterStats stats) {
            if (heldItemId >= 0 && playerHeldItemId != heldItemId) {
                return false;
            }
            if (classId != null && (stats == null || !classId.equals(stats.getSelectedClassId()))) {
                return false;
            }
            return abilityKey == null || (stats != null && stats.hasAbility(abilityKey));
        }
    }

    /** Most clusters a Rogue can legitimately have down: every cast within one cluster lifetime. */
    public static final int MAX_LIVE_CALTROPS =
            CALTROP_CLUSTER_COUNT * (int) Math.ceil(CALTROP_DURATION / CALTROP_COOLDOWN);

    private static final Map<Byte, ProjectileRule> PROJECTILE_RULES = Map.of(
        // Bow: one arrow per release after a minimum draw (0.25 s).
        ProjectileSpawnC2S.KIND_ARROW, new ProjectileRule(
            EntityType.ARROW, 32, 3, 4f, ItemType.BOW.getId(), null, null),
        // Staff: 0.4 s cast cooldown.
        ProjectileSpawnC2S.KIND_FIRE_BOLT, new ProjectileRule(
            EntityType.FIRE_BOLT, 16, 2, 2.5f / COOLDOWN_JITTER_ALLOWANCE, ItemType.STAFF.getId(), null, null),
        ProjectileSpawnC2S.KIND_NULL_SPIKE, new ProjectileRule(
            EntityType.NULL_SPIKE, 2, 1, 1f / (NULL_SPIKE_COOLDOWN * COOLDOWN_JITTER_ALLOWANCE), -1,
            ArcanistAbilityController.CLASS_ID, ArcanistAbilityController.NULL_SPIKE_KEY),
        ProjectileSpawnC2S.KIND_LEYLINE_BREACH, new ProjectileRule(
            EntityType.LEYLINE_BREACH_ZONE, 2, 1, 1f / (LEYLINE_BREACH_COOLDOWN * COOLDOWN_JITTER_ALLOWANCE), -1,
            ArcanistAbilityController.CLASS_ID, ArcanistAbilityController.LEYLINE_BREACH_KEY),
        ProjectileSpawnC2S.KIND_CALTROP, new ProjectileRule(
            EntityType.CALTROP_CLUSTER, MAX_LIVE_CALTROPS, CALTROP_CLUSTER_COUNT,
            CALTROP_CLUSTER_COUNT / (CALTROP_COOLDOWN * COOLDOWN_JITTER_ALLOWANCE), -1,
            RogueAbilityController.CLASS_ID, RogueAbilityController.CALTROP_KEY));

    /** The rule for spawn {@code kind}, or null for an unknown kind. */
    public static ProjectileRule projectileRule(byte kind) {
        return PROJECTILE_RULES.get(kind);
    }
}
