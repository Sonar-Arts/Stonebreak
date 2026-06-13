package com.stonebreak.player.combat.illusionist;

import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.player.CharacterStats;
import com.stonebreak.player.Player;
import com.stonebreak.player.combat.DoubtController;

/**
 * Owns the Illusionist's two active abilities (Mirrored Deceit, Fracture) and the Doubt resource
 * that fuels Fracture. Mirrors the controller-per-concern pattern used by
 * {@link com.stonebreak.player.combat.ranger.RangerAbilityController}.
 *
 * <p>Casting an ability requires the Illusionist class to be selected and its CP slot to be
 * unlocked. Doubt builds passively when enemies destroy the player's decoys, so there are no
 * player-hit hooks here.</p>
 */
public class IllusionistAbilityController {

    public static final String CLASS_ID = "illusionist";
    public static final String MIRRORED_DECEIT_KEY = CLASS_ID + ":0";
    public static final String FRACTURE_KEY = CLASS_ID + ":1";

    private final DoubtController doubt = new DoubtController();
    private final MirroredDeceitAbility mirroredDeceit = new MirroredDeceitAbility();
    private final FractureAbility fracture = new FractureAbility();

    public void update(float deltaTime, Player player) {
        doubt.update(deltaTime);
        mirroredDeceit.update(deltaTime, player, doubt);
        fracture.update(deltaTime);
    }

    public boolean tryCastMirroredDeceit(Player player) {
        if (!isUnlocked(player, MIRRORED_DECEIT_KEY) || mirroredDeceit.isActive()) return false;
        return mirroredDeceit.tryActivate(player);
    }

    public boolean tryCastFracture(Player player) {
        if (!isUnlocked(player, FRACTURE_KEY)) return false;
        return fracture.tryActivate(player, doubt);
    }

    /** Builds a Doubt stack on an enemy that struck a decoy (called from decoy-death handling). */
    public void onDecoyHit(LivingEntity attacker) {
        doubt.addStack(attacker);
    }

    /** Clears all Illusionist state (world reload — entity references go stale). */
    public void reset() {
        doubt.reset();
        mirroredDeceit.reset();
        fracture.reset();
    }

    private boolean isUnlocked(Player player, String abilityKey) {
        CharacterStats stats = player.getCharacterStats();
        return CLASS_ID.equals(stats.getSelectedClassId()) && stats.getSpentCp(abilityKey) > 0;
    }

    public DoubtController getDoubt() { return doubt; }
    public MirroredDeceitAbility getMirroredDeceit() { return mirroredDeceit; }
    public FractureAbility getFracture() { return fracture; }
}
