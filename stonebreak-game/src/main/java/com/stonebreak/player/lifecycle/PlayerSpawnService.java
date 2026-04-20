package com.stonebreak.player.lifecycle;

import com.stonebreak.items.Inventory;
import com.stonebreak.player.Camera;
import com.stonebreak.player.combat.AttackController;
import com.stonebreak.player.combat.HealthController;
import com.stonebreak.player.interaction.BlockBreaker;
import com.stonebreak.player.locomotion.FlightController;
import com.stonebreak.player.locomotion.JumpHandler;
import com.stonebreak.player.locomotion.SwimmingController;
import com.stonebreak.player.state.PhysicsState;

import static com.stonebreak.player.PlayerConstants.SPAWN_PROTECTION_DURATION;
import static com.stonebreak.player.PlayerConstants.SPAWN_X;
import static com.stonebreak.player.PlayerConstants.SPAWN_Y;
import static com.stonebreak.player.PlayerConstants.SPAWN_Z;

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

    private boolean loadedFromSave;

    public PlayerSpawnService(PhysicsState state, Camera camera, Inventory inventory,
                              HealthController health, AttackController attack,
                              BlockBreaker blockBreaker, FlightController flight,
                              JumpHandler jumpHandler, SwimmingController swimming) {
        this.state = state;
        this.camera = camera;
        this.inventory = inventory;
        this.health = health;
        this.attack = attack;
        this.blockBreaker = blockBreaker;
        this.flight = flight;
        this.jumpHandler = jumpHandler;
        this.swimming = swimming;
    }

    public void setLoadedFromSave(boolean loaded) {
        this.loadedFromSave = loaded;
    }

    public void giveStartingItems() {
        if (!loadedFromSave) {
            state.getPosition().set(SPAWN_X, SPAWN_Y, SPAWN_Z);
            state.getVelocity().set(0, 0, 0);
            state.setOnGround(false);

            swimming.reset();
            attack.reset();
            blockBreaker.reset();
            flight.reset();
            jumpHandler.reset();

            health.setHealth(20.0f);

            if (camera != null) camera.reset();
            if (inventory != null) inventory.resetToStartingItems();

            health.enableSpawnProtection();
            System.out.println("Spawn protection enabled for new world - fall damage disabled for " + SPAWN_PROTECTION_DURATION + " seconds");
            System.out.println("Player data reset for new world");
        } else {
            health.enableSpawnProtection();
            System.out.println("Player data loaded from save - preserving position and state (spawn protection enabled)");
        }
    }
}
