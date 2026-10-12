package com.stonebreak.ui.runtime.contracts;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.ActionSpec;
import com.openmason.engine.ui.data.DataCell;
import com.openmason.engine.ui.data.DataType;
import com.openmason.engine.ui.data.HostContract;
import com.openmason.engine.ui.data.UiHost;
import com.stonebreak.items.ItemStack;
import com.stonebreak.ui.inventoryScreen.handlers.ContainerSlotInput;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * The open container screen's crafting grid ({@code stonebreak:crafting} 1, #300): collection
 * {@code crafting} ({@code craft:0..n-1}, the 2x2 grid of the inventory screen or the 3x3 of a
 * crafting table) and root {@code craftOutput} (the {@code craft-output} slot record, empty when the
 * grid makes nothing), both empty while no crafting screen is open; action
 * {@code stonebreak:crafting.recipes} opens the recipe book over the screen, as its Recipes button
 * does. Slot clicks are the {@code stonebreak:inventory.slot-*} actions, at these addresses: no
 * crafting rule lives here.
 */
public final class CraftingContracts {

    public static final HostContract CRAFTING = HostContract.of("stonebreak:crafting", 1);

    private final Supplier<ContainerSlotInput> open;
    private final SlotGridSource grid = new SlotGridSource("craft");
    private final DataCell output;
    private final int[] lastOutput = {0, 0};
    private String lastOutputState;
    private boolean published;

    /**
     * @param open    the open container screen, or null
     * @param recipes opens the recipe book; null on success or why it refused
     */
    public CraftingContracts(UiHost ui, Supplier<ContainerSlotInput> open, Supplier<String> recipes) {
        this.open = open;
        ui.data().register("crafting", grid.collection(), CRAFTING);
        output = ui.data().register("craftOutput", new DataCell(SlotRecords.SLOT, empty()), CRAFTING);
        ui.actions().register(ActionSpec.of(CRAFTING.id() + ".recipes", CRAFTING, null, DataType.ANY), (args, ctx) -> {
            String problem = recipes.get();
            return problem == null ? CompletableFuture.completedFuture(UiValue.NULL)
                : CompletableFuture.failedFuture(new IllegalStateException(problem));
        });
    }

    /** UI thread, once per frame and after every slot action: republishes only what changed. */
    public void poll() {
        ContainerSlotInput screen = open.get();
        ItemStack[] slots = screen == null ? null : screen.craftingSlots();
        if (slots == null) {
            grid.refresh(0, i -> null, -1);
            publishOutput(null);
            return;
        }
        grid.refresh(slots.length, i -> slots[i], -1);
        publishOutput(screen.craftingOutput());
    }

    /** Empties both roots (world left). */
    public void clear() {
        grid.clear();
        publishOutput(null);
    }

    private void publishOutput(ItemStack s) {
        boolean empty = s == null || s.isEmpty();
        int id = empty ? 0 : s.getBlockTypeId();
        int count = empty ? 0 : s.getCount();
        String state = empty ? null : s.getState();
        if (published && lastOutput[0] == id && lastOutput[1] == count && Objects.equals(lastOutputState, state)) {
            return;
        }
        published = true;
        lastOutput[0] = id;
        lastOutput[1] = count;
        lastOutputState = state;
        output.set(SlotRecords.slot("craft-output", s, false));
    }

    private static UiValue.Obj empty() {
        return SlotRecords.slot("craft-output", null, false);
    }
}
