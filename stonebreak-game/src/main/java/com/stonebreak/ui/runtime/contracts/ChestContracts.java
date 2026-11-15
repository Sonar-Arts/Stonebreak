package com.stonebreak.ui.runtime.contracts;

import com.openmason.engine.ui.data.HostContract;
import com.openmason.engine.ui.data.UiHost;
import com.stonebreak.items.ItemStack;
import com.stonebreak.ui.inventoryScreen.handlers.ContainerSlotInput;

import java.util.function.Supplier;

/**
 * The open chest's slots ({@code stonebreak:chest} 1): collection {@code chest}
 * ({@code chest:0..26}), empty while no chest is open. Slot clicks are the
 * {@code stonebreak:inventory.slot-*} actions at these addresses: no chest rule lives here.
 */
public final class ChestContracts {

    public static final HostContract CHEST = HostContract.of("stonebreak:chest", 1);

    private final Supplier<ContainerSlotInput> open;
    private final SlotGridSource grid = new SlotGridSource("chest");

    /** @param open the open container screen, or null */
    public ChestContracts(UiHost ui, Supplier<ContainerSlotInput> open) {
        this.open = open;
        ui.data().register("chest", grid.collection(), CHEST);
    }

    /** UI thread, once per frame and after every slot action: republishes only what changed. */
    public void poll() {
        ContainerSlotInput screen = open.get();
        ItemStack[] slots = screen == null ? null : screen.containerSlots();
        if (slots == null) {
            grid.refresh(0, i -> null, -1);
        } else {
            grid.refresh(slots.length, i -> slots[i], -1);
        }
    }

    /** Empties the collection (world left). */
    public void clear() {
        grid.clear();
    }
}
