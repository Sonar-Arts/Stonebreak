package com.stonebreak.player.combat.berserker;

import com.stonebreak.player.CharacterStats;
import com.stonebreak.player.Player;
import com.stonebreak.player.combat.RageController;
import com.stonebreak.player.combat.RageTier;
import com.stonebreak.player.interaction.RaycastEngine;

/**
 * Owns the Berserker's two active abilities (Rampage, Skull Crusher) and the Rage
 * resource that fuels them. Mirrors the controller-per-concern pattern used elsewhere
 * on {@link Player} (e.g. {@code AttackController}, {@code BowController}).
 *
 * <p>Casting an ability requires the Berserker class to be selected and its CP slot to be
 * unlocked; the active Rage tier is captured and consumed at the moment of casting, and
 * abilities scale their effects off that captured tier (per the Berserker design).</p>
 */
public class BerserkerAbilityController {

    public static final String CLASS_ID = "berserker";
    public static final String RAMPAGE_KEY = CLASS_ID + ":0";
    public static final String SKULL_CRUSHER_KEY = CLASS_ID + ":1";

    private final RageController rage = new RageController();
    private final RampageAbility rampage = new RampageAbility();
    private final SkullCrusherAbility skullCrusher = new SkullCrusherAbility();

    public void update(float deltaTime, Player player) {
        rage.update(deltaTime);
        rampage.update(deltaTime, player);
        skullCrusher.update(deltaTime, player);
    }

    public boolean tryCastRampage(Player player) {
        if (!isUnlocked(player, RAMPAGE_KEY) || rampage.isActive()) return false;
        RageTier tierAtCast = rage.consumeThresholdForCast();
        return rampage.tryActivate(player, tierAtCast);
    }

    public boolean tryCastSkullCrusher(Player player, RaycastEngine raycastEngine) {
        if (!isUnlocked(player, SKULL_CRUSHER_KEY) || skullCrusher.isActive()) return false;
        RageTier tierAtCast = rage.consumeThresholdForCast();
        return skullCrusher.tryActivate(player, raycastEngine, tierAtCast);
    }

    /** True while either ability is mid-execution and should override normal player movement input. */
    public boolean isMovementLocked() {
        return rampage.isActive() || skullCrusher.isWindingUp();
    }

    private boolean isUnlocked(Player player, String abilityKey) {
        CharacterStats stats = player.getCharacterStats();
        return CLASS_ID.equals(stats.getSelectedClassId()) && stats.getSpentCp(abilityKey) > 0;
    }

    public RageController getRage() { return rage; }
    public RampageAbility getRampage() { return rampage; }
    public SkullCrusherAbility getSkullCrusher() { return skullCrusher; }
}
