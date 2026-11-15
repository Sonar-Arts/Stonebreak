package com.stonebreak.blocks.campfire;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.core.Game;
import com.stonebreak.items.ItemStack;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.player.Player;
import com.stonebreak.world.World;

/**
 * Right-clicking a campfire with something cookable puts one on the fire. The intent goes to the
 * authoritative server ({@code BlockToggleC2S}, like a door); this client predicts the item leaving
 * the hand, and the server's inventory sync undoes that if it refuses.
 */
public final class CampfireInteraction {

    private CampfireInteraction() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Cooks the held item on the fire at {@code (x,y,z)} if this client sees room for it. False
     * when there is nothing to cook or the fire is full, so the click falls through (to eating,
     * or placing).
     */
    public static boolean tryCook(Player player, ItemStack held, int x, int y, int z) {
        World world = Game.getWorld();
        Game game = Game.getInstance();
        if (world == null || game == null || held == null || held.isEmpty()
                || !CampfireState.isCookable(game.getSmeltingManager(), held.getItem())) {
            return false;
        }
        if (!CampfireState.parse(new BlockPos(x, y, z), world.getBlockStateAt(x, y, z)).hasFreeSpot()) {
            return false;
        }
        if (!MultiplayerSession.sendBlockToggle(x, y, z)) {
            return false; // no server to cook on
        }
        player.getInventory().removeItem(held.getItem(), 1);
        return true;
    }
}
