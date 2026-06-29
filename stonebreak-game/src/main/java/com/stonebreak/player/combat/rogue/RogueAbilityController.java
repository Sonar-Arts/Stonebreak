package com.stonebreak.player.combat.rogue;

import static com.stonebreak.player.PlayerConstants.MOMENTUM_T1_MULTIPLIER;
import static com.stonebreak.player.PlayerConstants.MOMENTUM_T2_MULTIPLIER;
import static com.stonebreak.player.PlayerConstants.MOMENTUM_T3_MULTIPLIER;
import static com.stonebreak.player.PlayerConstants.STEALTH_BLEED_DAMAGE_PER_TICK;
import static com.stonebreak.player.PlayerConstants.STEALTH_BLEED_DURATION;
import static com.stonebreak.player.PlayerConstants.STEALTH_CRIPPLE_DURATION;
import static com.stonebreak.player.PlayerConstants.STEALTH_CRIPPLE_SLOW_PERCENT;

import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.status.StatusEffectType;
import com.stonebreak.player.CharacterStats;
import com.stonebreak.player.Player;

/**
 * Owns the Rogue's two active abilities (Shadow Step, Caltrop Scatter) and the Momentum resource
 * that fuels its crits. Mirrors the controller-per-concern pattern used by
 * {@link com.stonebreak.player.combat.ranger.RangerAbilityController}.
 *
 * <p>Momentum is gained via {@link #onDodgeSuccess} (subscribed to the dodge system by the Player)
 * and consumed in {@link #onCritLanded}, called from {@link Player#attackEntity} when a crit lands.
 * Casting and Momentum gain are class-gated so a non-Rogue never builds or spends stacks.</p>
 */
public class RogueAbilityController {

    public static final String CLASS_ID = "rogue";
    public static final String SHADOW_STEP_KEY = CLASS_ID + ":0";
    public static final String CALTROP_KEY = CLASS_ID + ":1";

    private final MomentumController momentum = new MomentumController();
    private final ShadowStepAbility shadowStep = new ShadowStepAbility();
    private final CaltropScatterAbility caltrops = new CaltropScatterAbility();

    public void update(float deltaTime, Player player) {
        shadowStep.update(deltaTime, player);
        caltrops.update(deltaTime, player);
    }

    /**
     * Dodge-success listener (registered by the Player on the dodge system): grants +1 Momentum to
     * a Rogue. Gated on class selection so stacks never accrue for other classes.
     */
    public void onDodgeSuccess(Player player) {
        if (!isRogueSelected(player)) return;
        momentum.addStack();
        // PARRY HOOK — parry success will also grant +1 Momentum stack here
    }

    /**
     * Consumes all Momentum on a landed crit, returning the damage multiplier to apply to that crit
     * and applying the tier debuff (BLEED at 2, CRIPPLE at 3). Returns 1.0 for a non-Rogue or when
     * there is no Momentum, leaving the crit unchanged. Called from {@link Player#attackEntity}.
     */
    public float onCritLanded(Player player, LivingEntity target) {
        if (!isRogueSelected(player)) return 1f;
        return switch (momentum.consumeForCrit()) {
            case 1 -> MOMENTUM_T1_MULTIPLIER;
            case 2 -> {
                target.applyStatusEffect(StatusEffectType.BLEED,
                    STEALTH_BLEED_DURATION, STEALTH_BLEED_DAMAGE_PER_TICK);
                yield MOMENTUM_T2_MULTIPLIER;
            }
            case 3 -> {
                target.applyStatusEffect(StatusEffectType.CRIPPLE,
                    STEALTH_CRIPPLE_DURATION, STEALTH_CRIPPLE_SLOW_PERCENT);
                yield MOMENTUM_T3_MULTIPLIER;
            }
            default -> 1f; // 0 stacks: a normal crit
        };
    }

    public boolean tryCastShadowStep(Player player) {
        if (!isUnlocked(player, SHADOW_STEP_KEY) || shadowStep.isActive()) return false;
        return shadowStep.tryActivate(player);
    }

    public boolean tryCastCaltropScatter(Player player) {
        if (!isUnlocked(player, CALTROP_KEY) || caltrops.isActive()) return false;
        return caltrops.tryActivate(player);
    }

    /** Clears Momentum and ability cooldowns (world reload — entity references go stale). */
    public void reset() {
        momentum.reset();
        shadowStep.reset();
        caltrops.reset();
    }

    private boolean isUnlocked(Player player, String abilityKey) {
        CharacterStats stats = player.getCharacterStats();
        return CLASS_ID.equals(stats.getSelectedClassId()) && stats.getSpentCp(abilityKey) > 0;
    }

    private boolean isRogueSelected(Player player) {
        return CLASS_ID.equals(player.getCharacterStats().getSelectedClassId());
    }

    public MomentumController getMomentum() { return momentum; }
    public ShadowStepAbility getShadowStep() { return shadowStep; }
    public CaltropScatterAbility getCaltrops() { return caltrops; }
}
