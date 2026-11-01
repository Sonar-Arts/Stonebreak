package com.stonebreak.blocks.blueberrybush;

import org.joml.Vector3f;

import com.stonebreak.core.Game;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.player.Player;
import com.stonebreak.world.World;

/**
 * Right-click handler for blueberry bushes. Picking is an intent sent to the authoritative
 * server ({@code BlockToggleC2S}), the same packet a door uses. The server sets the bush back to
 * Mature, drops the berries, and echoes the new stage to every client as {@code BlockStateS2C}.
 */
public final class BlueberryBushInteraction {

    private BlueberryBushInteraction() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Picks the bush if this client sees it ripe. Returns {@code false} for an unripe bush so
     * the right-click falls through to normal placement.
     */
    public static boolean tryHarvest(Player player, int x, int y, int z) {
        World world = Game.getWorld();
        if (world == null) {
            return false;
        }
        BlueberryBushState state = BlueberryBushState.parse(world.getBlockStateAt(x, y, z));
        if (state == null || !state.isRipe()) {
            return false;
        }
        if (MultiplayerSession.sendBlockToggle(x, y, z)) {
            return true; // the server will echo the new stage and spawn the berries
        }
        // No live connection (bare/test world): pick locally.
        Vector3f picker = player == null ? null : new Vector3f(player.getPosition());
        return BlueberryBushHarvest.harvest(world, x, y, z, picker);
    }
}
