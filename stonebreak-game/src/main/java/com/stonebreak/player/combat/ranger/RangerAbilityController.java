package com.stonebreak.player.combat.ranger;

import static com.stonebreak.player.PlayerConstants.RANGER_PREY_SPEED_BONUS;
import static com.stonebreak.player.PlayerConstants.RANGER_PREY_SPEED_TOWARD_DOT;

import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.player.CharacterStats;
import com.stonebreak.player.Player;
import com.stonebreak.player.combat.QuarryController;
import org.joml.Vector3f;

/**
 * Owns the Ranger's two active abilities (Snare, Culling Shot) and the Quarry/Study
 * resource that fuels them. Mirrors the controller-per-concern pattern used by
 * {@link com.stonebreak.player.combat.berserker.BerserkerAbilityController}.
 *
 * <p>Casting an ability requires the Ranger class to be selected and its CP slot to be
 * unlocked. The Quarry hit hooks are likewise class-gated so a non-Ranger never marks
 * prey (the mark has visible HUD/world effects).</p>
 */
public class RangerAbilityController {

    public static final String CLASS_ID = "ranger";
    public static final String SNARE_KEY = CLASS_ID + ":0";
    public static final String CULLING_SHOT_KEY = CLASS_ID + ":1";

    private final QuarryController quarry = new QuarryController();
    private final SnareAbility snare = new SnareAbility();
    private final CullingShotAbility cullingShot = new CullingShotAbility();

    public void update(float deltaTime, Player player) {
        quarry.update(deltaTime);
        snare.update(deltaTime, quarry);
        cullingShot.update(deltaTime, player, quarry);

        // Record weakness discovery when Quarry is fully studied (Marked Prey).
        // O(1), idempotent — covers all stack paths (melee, arrow, Snare).
        if (quarry.isMarkedPrey() && quarry.getQuarry() != null) {
            player.getEntityDiscoveries().recordWeaknessDiscovered(quarry.getQuarry().getType());
        }
    }

    public boolean tryCastSnare(Player player) {
        if (!isUnlocked(player, SNARE_KEY) || snare.isActive()) return false;
        return snare.tryActivate(player);
    }

    public boolean tryCastCullingShot(Player player) {
        if (!isUnlocked(player, CULLING_SHOT_KEY) || cullingShot.isActive()) return false;
        return cullingShot.tryActivate(player);
    }

    /** Registers a player melee hit for Quarry marking/Study building (Ranger only). */
    public void onPlayerMeleeHit(Player player, LivingEntity target) {
        if (!isRangerSelected(player)) return;
        quarry.onPlayerHit(target);
    }

    /** Registers a player arrow hit for Quarry marking/Study building (Ranger only). */
    public void onPlayerArrowHit(Player player, LivingEntity target) {
        if (!isRangerSelected(player)) return;
        quarry.onPlayerHit(target);
    }

    /** True while the post-shot dash drives the player and should override movement input. */
    public boolean isMovementLocked() {
        return cullingShot.isDashing();
    }

    /**
     * Movement speed multiplier for the Marked Prey chase buff: applies while the Quarry
     * is fully studied and below the low-HP threshold, and the player's intended move
     * direction points toward it (within the configured bearing cone). Returns 1 otherwise.
     */
    public float getSpeedMultiplier(Player player, Vector3f intendedHorizontalDir) {
        if (!isRangerSelected(player)) return 1f;
        if (!quarry.isMarkedPrey() || !quarry.isPreyLowHp()) return 1f;
        if (intendedHorizontalDir.lengthSquared() < 0.0001f) return 1f;

        Vector3f toPrey = quarry.getQuarry().getPosition().sub(player.getPosition());
        toPrey.y = 0f;
        if (toPrey.lengthSquared() < 0.0001f) return 1f;
        toPrey.normalize();

        return intendedHorizontalDir.dot(toPrey) >= RANGER_PREY_SPEED_TOWARD_DOT
            ? 1f + RANGER_PREY_SPEED_BONUS
            : 1f;
    }

    /** Clears Quarry, trap, and ability state (world reload — entity references go stale). */
    public void reset() {
        quarry.reset();
        snare.reset();
        cullingShot.reset();
    }

    private boolean isUnlocked(Player player, String abilityKey) {
        CharacterStats stats = player.getCharacterStats();
        return CLASS_ID.equals(stats.getSelectedClassId()) && stats.getSpentCp(abilityKey) > 0;
    }

    private boolean isRangerSelected(Player player) {
        return CLASS_ID.equals(player.getCharacterStats().getSelectedClassId());
    }

    public QuarryController getQuarry() { return quarry; }
    public SnareAbility getSnare() { return snare; }
    public CullingShotAbility getCullingShot() { return cullingShot; }
}
