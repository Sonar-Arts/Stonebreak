package com.stonebreak.player.appearance;

import com.stonebreak.mobs.sbe.BodyMounts;
import com.stonebreak.mobs.sbe.Clothing;

/**
 * Puts a look on a body (#459): every equipment slot of the catalog becomes one mount named after the slot, holding
 * its item's model on the item's socket (empty / unknown = the mount comes off). Tints and groups have nothing to
 * apply to on the current player model; the rebuilt player's {@code .omchar} appearance takes them (#462).
 */
public final class AppearanceMounts {

    private AppearanceMounts() {
    }

    /** Dresses {@code mounts} in {@code look}; other mounts (the held object, a weapon) are left alone. */
    public static void apply(PlayerAppearance look, BodyMounts mounts) {
        CosmeticCatalog catalog = CosmeticCatalog.get();
        for (CosmeticCatalog.Slot slot : catalog.slots()) {
            CosmeticCatalog.Item item = catalog.item(look.item(slot.name()));
            if (item == null || !item.slot().equals(slot.name())) {
                mounts.remove(slot.name());
                continue;
            }
            Clothing.wear(mounts, slot.name(), catalog.socketOf(item), item.asset(), item.variant());
        }
    }
}
