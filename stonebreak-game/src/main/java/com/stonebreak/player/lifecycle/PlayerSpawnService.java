package com.stonebreak.player.lifecycle;

import com.stonebreak.items.Inventory;
import com.stonebreak.items.StartingItems;
import com.stonebreak.player.Camera;
import com.stonebreak.player.CharacterStats;
import com.stonebreak.player.combat.AttackController;
import com.stonebreak.player.combat.HealthController;
import com.stonebreak.player.interaction.BlockBreaker;
import com.stonebreak.player.locomotion.FlightController;
import com.stonebreak.player.locomotion.JumpHandler;
import com.stonebreak.player.locomotion.SwimmingController;
import com.stonebreak.player.state.PhysicsState;
import com.stonebreak.rpg.backgrounds.BackgroundRegistry;

import static com.stonebreak.player.PlayerConstants.SPAWN_PROTECTION_DURATION;

/**
 * Owns new-world spawn setup and the load-from-save flag. When a fresh world starts,
 * resets all subsystem state and hands out starting inventory items. When a save is
 * loaded, preserves in-save state and only enables spawn protection.
 */
public class PlayerSpawnService {

    private final PhysicsState state;
    private final Camera camera;
    private final Inventory inventory;
    private final HealthController health;
    private final AttackController attack;
    private final BlockBreaker blockBreaker;
    private final FlightController flight;
    private final JumpHandler jumpHandler;
    private final SwimmingController swimming;
    private final CharacterStats characterStats;

    private boolean loadedFromSave;

    public PlayerSpawnService(PhysicsState state, Camera camera, Inventory inventory,
                              HealthController health, AttackController attack,
                              BlockBreaker blockBreaker, FlightController flight,
                              JumpHandler jumpHandler, SwimmingController swimming,
                              CharacterStats characterStats) {
        this.state = state;
        this.camera = camera;
        this.inventory = inventory;
        this.health = health;
        this.attack = attack;
        this.blockBreaker = blockBreaker;
        this.flight = flight;
        this.jumpHandler = jumpHandler;
        this.swimming = swimming;
        this.characterStats = characterStats;
    }

    public void setLoadedFromSave(boolean loaded) {
        this.loadedFromSave = loaded;
    }

    public void giveStartingItems() {
        if (!loadedFromSave) {
            resetForFreshSpawn();
            if (inventory != null) inventory.resetToStartingItems();

            // The same bonus + item rules the server applies to its fresh-character record
            // (issue #334): the replica sync that follows then agrees with this mirror.
            BackgroundRegistry.findById(characterStats.getSelectedBackground()).ifPresent(bg ->
                    characterStats.applyBackgroundBonuses(bg.abilityScoreBonuses()));
            if (inventory != null) {
                for (StartingItems.Entry e : StartingItems.backgroundEntries(
                        characterStats.getSelectedBackground())) {
                    inventory.addItem(e.itemId(), e.count());
                }
            }

            health.enableSpawnProtection();
            System.out.println("Spawn protection enabled for new world - fall damage disabled for " + SPAWN_PROTECTION_DURATION + " seconds");
            System.out.println("Player data reset for new world");
        } else {
            health.enableSpawnProtection();
            System.out.println("Player data loaded from save - preserving position and state (spawn protection enabled)");
        }
    }

    /**
     * A fresh character the HOST built from this client's creation (#367): the same fresh-spawn
     * reset, full health and spawn protection as {@link #giveStartingItems}, but no items and no
     * background bonuses — the server's creation echo already carries the bonuses (applying
     * them here would double them) and its inventory sync carries the starter + background items.
     */
    public void prepareServerCreatedCharacter() {
        resetForFreshSpawn();
        health.enableSpawnProtection();
        System.out.println("Server-created character - spawn protection enabled for "
                + SPAWN_PROTECTION_DURATION + " seconds");
    }

    private void resetForFreshSpawn() {
        // Position is already set to the authoritative world spawn (via WelcomeS2C ->
        // Game.buildClientWorld -> player.setPosition) before this runs; this only resets
        // physics/state. Snap fall bookkeeping to the current spawn so a low-surface spawn
        // doesn't register phantom fall damage.
        state.setPreviousY(state.getPosition().y);
        state.getVelocity().set(0, 0, 0);
        state.setOnGround(false);

        swimming.reset();
        attack.reset();
        blockBreaker.reset();
        flight.reset();
        jumpHandler.reset();

        health.setHealth(20.0f);

        if (camera != null) camera.reset();
    }
}
