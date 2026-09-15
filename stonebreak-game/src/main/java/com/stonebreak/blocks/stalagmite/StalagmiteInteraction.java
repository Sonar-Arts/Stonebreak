package com.stonebreak.blocks.stalagmite;

import com.stonebreak.core.Game;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.player.Player;
import com.stonebreak.world.World;

/**
 * Right-click on a stalagmite with a full water bucket: grow it one size and empty the bucket.
 *
 * <p>Growth is an intent to the authoritative server over {@code BlockToggleC2S} (every
 * session mode runs one, singleplayer included), which applies {@link Stalagmite#grow} and
 * replicates the cells. The bucket is client inventory and is emptied here, but only once the
 * client's own world agrees the stalagmite can grow — so a click on a full-size or capped
 * stalagmite never wastes the water.
 */
public final class StalagmiteInteraction {

    private StalagmiteInteraction() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** @return true when the click was consumed as a watering (the caller must not place a block) */
    public static boolean tryGrow(Player player, int x, int y, int z) {
        Inventory inventory = player.getInventory();
        ItemStack held = inventory.getSelectedHotbarSlot();
        if (held == null || held.isEmpty() || held.getItem() != ItemType.WOODEN_BUCKET
                || !ItemType.BUCKET_STATE_WATER.equals(held.getState())) {
            return false;
        }
        World world = Game.getWorld();
        if (world == null) {
            return false;
        }
        Stalagmite.Cells cells = Stalagmite.of(world);
        if (!Stalagmite.canGrow(cells, x, y, z)) {
            return true; // still a watering attempt — don't pour the bucket out beside it
        }
        if (!MultiplayerSession.sendBlockToggle(x, y, z)) {
            // No live connection (bare/test world) — grow locally.
            Stalagmite.grow(cells, x, y, z);
        }
        emptyHeldBucket(inventory, held);
        return true;
    }

    /** Same stack rule as pouring water: a stack of full buckets gives up one. */
    private static void emptyHeldBucket(Inventory inventory, ItemStack held) {
        int slot = inventory.getSelectedHotbarSlotIndex();
        int count = held.getCount();
        if (count == 1) {
            inventory.setHotbarSlot(slot, new ItemStack(ItemType.WOODEN_BUCKET, 1, ItemType.BUCKET_STATE_EMPTY));
        } else {
            inventory.setHotbarSlot(slot,
                    new ItemStack(ItemType.WOODEN_BUCKET, count - 1, ItemType.BUCKET_STATE_WATER));
            inventory.addItem(new ItemStack(ItemType.WOODEN_BUCKET, 1, ItemType.BUCKET_STATE_EMPTY));
        }
    }
}
