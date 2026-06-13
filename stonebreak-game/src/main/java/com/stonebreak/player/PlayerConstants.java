package com.stonebreak.player;

/**
 * Shared constants for the Player subsystem. Centralized to avoid duplication across
 * the master controller and its delegated subsystems.
 */
public final class PlayerConstants {
    private PlayerConstants() {}

    public static final float PLAYER_HEIGHT = 1.8f;
    public static final float PLAYER_WIDTH = 0.6f;

    public static final float MOVE_SPEED = 20.0f;
    public static final float SWIM_SPEED = 11.5f;
    public static final float JUMP_FORCE = 8.5f;
    public static final float GRAVITY = 35.0f;
    public static final float WATER_GRAVITY = 12.0f;
    public static final float WATER_BUOYANCY = 25.0f;
    public static final float WATER_JUMP_BOOST = 5.0f;

    public static final float RAY_CAST_DISTANCE = 5.0f;
    public static final float ATTACK_ANIMATION_DURATION = 0.25f;

    public static final float HEALTH_PER_HEART = 2.0f;
    public static final int MAX_HEARTS = 10;
    public static final float MAX_HEALTH = MAX_HEARTS * HEALTH_PER_HEART;

    public static final float SPRINT_MULTIPLIER       = 1.5f;

    public static final float HEALTH_PER_CON_POINT     = 2f;   // CON 10 → 20 HP
    public static final float STAMINA_PER_DEX_POINT    = 10f;  // DEX 10 → 100 stamina
    public static final float STAMINA_DRAIN_RATE       = 10f;  // per sec while sprinting
    public static final float STAMINA_REGEN_RATE       = 15f;  // per sec while not sprinting
    public static final float MANA_PER_WIS_POINT       = 5f;   // WIS 10 → 50 mana
    public static final float MANA_REGEN_PER_WIS_POINT = 0.2f; // WIS 10 → 2/sec regen

    public static final float SPAWN_PROTECTION_DURATION = 2.0f;

    public static final float WATER_EXIT_ANTI_FLOAT_DURATION = 0.5f;
    public static final float NORMAL_JUMP_GRACE_PERIOD = 0.2f;
    public static final float DOUBLE_TAP_WINDOW = 0.3f;

    public static final float FLY_SPEED = MOVE_SPEED * 2.5f;
    public static final float FLY_VERTICAL_SPEED = 15.0f;

    public static final float SPAWN_X = 0.0f;
    public static final float SPAWN_Y = 100.0f;
    public static final float SPAWN_Z = 0.0f;

    public static final float CAMERA_EYE_OFFSET = PLAYER_HEIGHT * 0.8f;

    // ── Berserker: Rage resource ──────────────────────────────────────────────
    public static final float RAGE_MAX             = 100f;
    public static final float RAGE_T1_THRESHOLD    = 33f;
    public static final float RAGE_T2_THRESHOLD    = 66f;
    public static final float RAGE_T3_THRESHOLD    = 100f;

    public static final float RAGE_GAIN_PER_HIT_DEALT    = 6f;
    public static final float RAGE_GAIN_PER_HIT_RECEIVED = 9f;
    public static final float RAGE_COMBAT_TIMEOUT        = 4f;   // seconds of no combat before decay starts
    public static final float RAGE_DECAY_PER_SECOND      = 18f;

    public static final float RAGE_T1_DAMAGE_BONUS       = 0.20f; // +20% melee damage at T1+
    public static final float RAGE_T2_ATTACK_SPEED_BONUS = 0.30f; // +30% swing speed at T2+
    public static final float RAGE_T3_LIFESTEAL_PCT      = 0.25f; // heal for 25% of melee damage dealt at T3

    // ── Berserker: Rampage ability ────────────────────────────────────────────
    public static final float RAMPAGE_BASE_RANGE            = 5f;
    public static final float RAMPAGE_T1_RANGE              = 9f;
    public static final float RAMPAGE_SPEED                 = 14f;
    public static final float RAMPAGE_DAMAGE                = 6f;
    public static final float RAMPAGE_HIT_RADIUS            = 1.2f;
    public static final float RAMPAGE_TRAIL_WAYPOINT_GAP    = 0.75f; // distance between trail zones
    public static final float RAMPAGE_TRAIL_TICK_DAMAGE     = 2f;    // burning DOT per second
    public static final float RAMPAGE_TRAIL_DURATION        = 4f;
    public static final float RAMPAGE_KNOCKBACK_HORIZONTAL  = 9f;
    public static final float RAMPAGE_KNOCKBACK_VERTICAL    = 4f;
    public static final float RAMPAGE_STAGGER_DURATION      = 1.2f;
    public static final float RAMPAGE_MAX_CLEAVE_HALF_WIDTH = 6f;
    public static final float RAMPAGE_COOLDOWN              = 6f;

    // ── Berserker: Skull Crusher ability ──────────────────────────────────────
    public static final float SKULLCRUSHER_WINDUP_DURATION       = 1.1f;
    public static final float SKULLCRUSHER_DAMAGE                = 14f;
    public static final float SKULLCRUSHER_RANGE                 = 5f;
    public static final float SKULLCRUSHER_SHOCKWAVE_RADIUS      = 4f;
    public static final float SKULLCRUSHER_SHOCKWAVE_DAMAGE      = 5f;
    public static final float SKULLCRUSHER_STUN_DURATION         = 1.5f;
    public static final float SKULLCRUSHER_CRATER_RADIUS         = 4.5f;
    public static final float SKULLCRUSHER_CRATER_DURATION       = 6f;
    public static final float SKULLCRUSHER_ARMOR_BREAK_MAGNITUDE = 0.35f; // +35% damage taken
    public static final float SKULLCRUSHER_ARMOR_BREAK_DURATION  = 3f;
    public static final float SKULLCRUSHER_COOLDOWN              = 8f;

    // ── Ranger: Quarry / Study resource ───────────────────────────────────────
    public static final int   RANGER_STUDY_MAX_STACKS         = 3;
    public static final float RANGER_STUDY_DECAY_TIMEOUT      = 6f;   // sec without hitting Quarry before decay
    public static final float RANGER_STUDY_DECAY_INTERVAL     = 3f;   // one stack lost per interval after timeout
    public static final float RANGER_MARKED_PREY_VISION_RANGE = 48f;  // through-terrain marker range
    public static final float RANGER_PREY_LOW_HP_FRACTION     = 0.30f;
    public static final float RANGER_PREY_SPEED_BONUS         = 0.30f; // +30% move speed toward low-HP Marked Prey
    public static final float RANGER_PREY_SPEED_TOWARD_DOT    = 0.5f;  // move dir · prey bearing ≥ this counts as "toward"
    public static final float RANGER_CRIT_MULTIPLIER          = 2.0f;  // guaranteed crit at Marked Prey

    // ── Ranger: Snare ability ─────────────────────────────────────────────────
    public static final float RANGER_SNARE_PLACE_RANGE       = 12f;
    public static final float RANGER_SNARE_ARM_TIME          = 1.0f;
    public static final float RANGER_SNARE_RADIUS            = 1.5f;
    public static final float RANGER_SNARE_ROOT_DURATION     = 2.0f;
    public static final float RANGER_SNARE_ROOT_EXTENSION    = 1.5f;  // extra root vs an already-studied Quarry
    public static final int   RANGER_SNARE_STACKS_ON_TRIGGER = 2;
    public static final float RANGER_SNARE_COOLDOWN          = 5f;
    public static final float RANGER_EXPOSED_DURATION        = 4f;
    public static final float RANGER_EXPOSED_MAGNITUDE       = 0.25f; // +25% damage taken from all sources

    // ── Ranger: Culling Shot ability ──────────────────────────────────────────
    public static final float RANGER_CULLING_CHANNEL_TIME    = 0.9f;
    public static final float RANGER_CULLING_RANGE           = 40f;
    public static final float RANGER_CULLING_BASE_DAMAGE     = 10f;
    public static final float RANGER_CULLING_WEAKPOINT_BONUS = 6f;
    public static final float RANGER_CULLING_COOLDOWN        = 7f;
    public static final float RANGER_BLEED_DURATION          = 5f;
    public static final float RANGER_BLEED_DPS               = 2f;
    public static final float RANGER_CRIPPLE_DURATION        = 3f;
    public static final float RANGER_CRIPPLE_MAGNITUDE       = 0.6f;  // 60% move-speed reduction
    public static final float RANGER_DASH_DISTANCE           = 6f;
    public static final float RANGER_DASH_SPEED              = 18f;

    // ── Arcanist: Resonance resource ──────────────────────────────────────────
    public static final int   ARCANIST_RESONANCE_MAX_STACKS               = 4;
    public static final float ARCANIST_SAME_SCHOOL_COST_REDUCTION_PER_CAST = 0.08f; // 8% cheaper per consecutive same-school cast
    public static final float ARCANIST_SAME_SCHOOL_MAX_COST_REDUCTION      = 0.35f; // cap at 35% cost reduction
    public static final float ARCANIST_SAME_SCHOOL_DAMAGE_PENALTY_PER_CAST = 0.10f; // 10% weaker per consecutive same-school cast
    public static final float ARCANIST_SAME_SCHOOL_MIN_DAMAGE_MULTIPLIER   = 0.50f; // floor at 50% damage
    public static final float ARCANIST_INT_SCALING                         = 0.04f; // damage scaling per INT point
    public static final float ARCANIST_INT_DURATION_SCALING                = 0.015f; // duration scaling per INT point
    public static final float SPELLMARKED_BONUS_DAMAGE_MULT                = 0.35f; // +35% damage on the consuming arcane hit

    // ── Arcanist: Leyline Breach ability ──────────────────────────────────────
    public static final float LEYLINE_BREACH_MANA_COST            = 30f;
    public static final float LEYLINE_BREACH_COOLDOWN             = 14f;
    public static final float LEYLINE_BREACH_MAX_RANGE            = 18f;
    public static final float LEYLINE_BREACH_RADIUS               = 5f;
    public static final float LEYLINE_BREACH_DURATION             = 8f;
    public static final float LEYLINE_BREACH_PULSE_INTERVAL       = 1.2f;
    public static final float LEYLINE_BREACH_PULSE_BASE           = 18f;
    public static final float LEYLINE_BREACH_PULL_FORCE           = 2.5f;
    public static final float LEYLINE_BREACH_AMPLIFY_BONUS        = 0.20f; // +20% magical damage taken
    public static final float LEYLINE_BREACH_AMPLIFY_DURATION     = 2.5f;
    public static final float LEYLINE_BREACH_OVERLOAD_RADIUS_MULT = 1.7f;
    public static final float LEYLINE_BREACH_OVERLOAD_PULL_MULT   = 2.2f;
    public static final float LEYLINE_BREACH_OVERLOAD_DAMAGE_MULT = 1.8f;
    public static final float LEYLINE_BREACH_ROOT_DURATION        = 2f;

    // ── Arcanist: Null Spike ability ──────────────────────────────────────────
    public static final float NULL_SPIKE_MANA_COST            = 15f;
    public static final float NULL_SPIKE_COOLDOWN             = 8f;
    public static final float NULL_SPIKE_MAX_RANGE            = 22f;
    public static final float NULL_SPIKE_PROJECTILE_SPEED     = 28f;
    public static final float NULL_SPIKE_BASE_DAMAGE          = 35f;
    public static final float NULL_SPIKE_STACK_BONUS          = 0.09f; // +9% damage per Resonance stack at fire
    public static final float NULL_SPIKE_SPELLMARKED_DURATION = 4f;    // doubled when Overloaded
    public static final float NULL_SPIKE_BURST_DAMAGE         = 25f;
    public static final float NULL_SPIKE_BURST_RADIUS         = 4f;

    // ── Illusionist: Doubt passive ────────────────────────────────────────────
    public static final int   ILLUSIONIST_DOUBT_MAX_STACKS         = 3;
    public static final float ILLUSIONIST_DOUBT_DECAY_TIMEOUT      = 6f;    // sec without illusory contact before decay starts
    public static final float ILLUSIONIST_DOUBT_DECAY_INTERVAL     = 3f;    // one stack lost per interval after timeout
    public static final float ILLUSIONIST_SHAKEN_ATTACK_DELAY      = 0.15f; // sec hesitation added per Doubt stack
    public static final float ILLUSIONIST_SHAKEN_DURATION          = 2.0f;  // duration of SHAKEN refresh on stack change

    // ── Illusionist: Mirrored Deceit ability ──────────────────────────────────
    public static final float ILLUSIONIST_DECOY_DURATION           = 5.0f;  // sec decoys live
    public static final float ILLUSIONIST_DECOY_HIT_SLOW_DURATION  = 1.0f;
    public static final float ILLUSIONIST_DECOY_HIT_SLOW_MAG       = 0.5f;  // 50% speed reduction
    public static final float ILLUSIONIST_DECOY_REVEAL_DURATION    = 8.0f;  // sec mark persists after decoy hit
    public static final float ILLUSIONIST_MIRRORED_DECEIT_COOLDOWN = 12f;

    // ── Illusionist: Fracture ability ─────────────────────────────────────────
    public static final float ILLUSIONIST_FRACTURE_RANGE               = 32f;  // max range to detect Doubt-stacked enemies
    public static final float ILLUSIONIST_FRACTURE_STUN_BASE           = 0.5f; // sec stun at 1 stack
    public static final float ILLUSIONIST_FRACTURE_STUN_PER_STACK      = 0.4f; // added per stack above 1
    public static final float ILLUSIONIST_FRACTURE_BEWILDERED_DURATION = 3.0f; // friendly-fire/panic duration at 3 stacks
    public static final float ILLUSIONIST_FRACTURE_CONTAGION_RADIUS    = 6f;   // spread 1 Doubt to bystanders at Bewildered
    public static final float ILLUSIONIST_FRACTURE_COOLDOWN            = 14f;
}
