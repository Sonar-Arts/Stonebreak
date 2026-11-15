package com.stonebreak.blocks.meatrack;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.core.Game;
import com.stonebreak.items.ItemStack;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.player.Player;
import com.stonebreak.world.World;

/**
 * Right-clicking a meat rack: with food in hand, the held stack goes on its hooks (as much as
 * fits); empty-handed, the food on the last filled hook comes back. Both go to the authoritative
 * server as the block-toggle intent ({@code BlockToggleC2S}, like a campfire); the server decides
 * which by what the player holds, and syncs the inventory after. This client predicts the hung
 * food leaving the hand; the hooks themselves change when the server's state echo lands.
 */
public final class MeatRackInteraction {

    private MeatRackInteraction() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Hangs the held food on the rack at {@code (x,y,z)} if this client sees room for any. False
     * when it is not food or the rack is full, so the click falls through (to eating).
     */
    public static boolean tryHang(Player player, ItemStack held, int x, int y, int z) {
        World world = Game.getWorld();
        if (world == null || held == null || held.isEmpty() || !MeatRackState.accepts(held.getItem())) {
            return false;
        }
        int fits = rackAt(world, x, y, z).roomFor(held);
        if (fits <= 0 || !MultiplayerSession.sendBlockToggle(x, y, z)) {
            return false;
        }
        player.getInventory().removeItem(held.getItem(), fits);
        return true;
    }

    /** Asks for the food on the last filled hook back. False when this client sees a bare rack. */
    public static boolean tryTake(int x, int y, int z) {
        World world = Game.getWorld();
        if (world == null || rackAt(world, x, y, z).lastFilledHook() < 0) {
            return false;
        }
        return MultiplayerSession.sendBlockToggle(x, y, z);
    }

    /** This client's display copy of the rack; an empty stand-in when it has none yet. */
    private static MeatRackState rackAt(World world, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        MeatRackState s = world.getMeatRackRegistry().get(pos);
        return s != null ? s : new MeatRackState(pos);
    }
}
