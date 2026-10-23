package com.stonebreak.player.lifecycle;

import com.stonebreak.items.Inventory;
import com.stonebreak.player.Camera;
import com.stonebreak.player.CharacterStats;
import com.stonebreak.player.combat.AttackController;
import com.stonebreak.player.combat.HealthController;
import com.stonebreak.player.interaction.BlockBreaker;
import com.stonebreak.player.locomotion.FlightController;
import com.stonebreak.player.locomotion.JumpHandler;
import com.stonebreak.player.locomotion.SwimmingController;
import com.stonebreak.player.state.PhysicsState;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * A character the host built from a joining client's creation (#367) gets the fresh-spawn
 * reset, full health and spawn protection, but no client-side kit or background bonuses: the
 * server's echo already carries the bonuses and its inventory sync the items.
 */
class ServerCreatedCharacterSpawnTest {

    @Test
    void resetsAndProtectsWithoutHandingOutItemsOrBonuses() {
        PhysicsState state = new PhysicsState();
        Inventory inventory = mock(Inventory.class);
        HealthController health = mock(HealthController.class);
        CharacterStats stats = mock(CharacterStats.class);
        PlayerSpawnService spawn = new PlayerSpawnService(state, mock(Camera.class), inventory, health,
                mock(AttackController.class), mock(BlockBreaker.class), mock(FlightController.class),
                mock(JumpHandler.class), mock(SwimmingController.class), stats);

        spawn.prepareServerCreatedCharacter();

        verify(health).setHealth(20.0f);
        verify(health).enableSpawnProtection();
        verify(inventory, never()).resetToStartingItems();
        verify(inventory, never()).addItem(anyInt(), anyInt());
        verify(stats, never()).applyBackgroundBonuses(any());
    }
}
