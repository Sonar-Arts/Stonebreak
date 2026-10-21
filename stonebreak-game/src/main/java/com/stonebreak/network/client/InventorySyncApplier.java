package com.stonebreak.network.client;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemStackStateCodec;
import com.stonebreak.player.Player;
import com.stonebreak.ui.inventoryScreen.handlers.ContainerSlotInput;
import com.stonebreak.ui.inventoryScreen.handlers.InventoryDragDropHandler;

/**
 * Applies an {@link com.stonebreak.network.packet.player.InventorySyncS2C} payload (the
 * server's {@code ServerInventory} encoding) onto the local player's {@code Inventory}
 * mirror (issue #334). The client's container screens keep optimistic prediction; the
 * carried cursor stack is adopted by whichever container screen is open, and cleared when
 * none is.
 */
public final class InventorySyncApplier {

    private InventorySyncApplier() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static void apply(Player player, String payload) {
        apply(player, payload, true);
    }

    /**
     * Applies a server sync stamped {@code ack} (issue #334, #353): skipped while a newer
     * slot action of ours is in flight — the sync predates it and would roll back its
     * prediction; the sync answering that action corrects us. The selected hotbar slot is
     * the client's own intent ({@code HotbarSelectC2S}), so it is taken only from the first
     * applied sync of a session (the seeded value): a later sync built before our last
     * selection change would otherwise revert it.
     */
    public static void applyServerSync(Player player, String payload, int ack) {
        if (!com.stonebreak.network.SlotIntentSeq.isCurrent(ack)) {
            return;
        }
        apply(player, payload, !selectionSeeded);
        selectionSeeded = true;
    }

    /** Session start: the next applied sync seeds the hotbar selection again. */
    public static void resetSession() {
        selectionSeeded = false;
    }

    private static volatile boolean selectionSeeded;

    private static void apply(Player player, String payload, boolean applySelection) {
        if (player == null || payload == null || payload.isEmpty()) {
            return;
        }
        String[] parts = payload.split("\\|", -1);
        var inventory = player.getInventory();
        if (inventory == null || parts.length < 37) {
            return;
        }
        for (int i = 0; i < 9; i++) {
            inventory.setHotbarSlot(i, decode(parts[i]));
        }
        for (int i = 0; i < 27; i++) {
            inventory.setMainInventorySlot(i, decode(parts[9 + i]));
        }
        ItemStack carried = decode(parts[36]);

        // Inventory-screen craft grid (4 tokens, when the payload carries them): only the
        // inventory screen's own 2×2 grid is screen state — a bound workbench grid is
        // reconciled through BlockStateS2C instead.
        if (parts.length >= 41) {
            ContainerSlotInput open = openContainerScreen();
            if (open != null) {
                ItemStack[] craft = open.craftingSlots();
                if (craft != null && craft.length == 4) {
                    for (int i = 0; i < 4; i++) {
                        craft[i] = decode(parts[37 + i]);
                    }
                    // The output preview is derived from the grid: recompute it, or a stale
                    // output from the predicted grid stays clickable (a ghost craft).
                    open.refreshCraftingOutput();
                }
            }
        }
        // 42 tokens: 36 slots + carried + 4 craft + selected slot + held id.
        if (applySelection && parts.length >= 42) {
            try {
                inventory.setSelectedHotbarSlotIndex(
                        Integer.parseInt(parts[41].trim()));
            } catch (NumberFormatException ignored) {
            }
        }

        adoptCarried(carried);
    }

    private static ItemStack decode(String token) {
        ItemStack s = ItemStackStateCodec.decode(token);
        return s == null ? new ItemStack(BlockType.AIR.getId(), 0) : s;
    }

    /**
     * The server's carried stack is authoritative: an open container screen adopts it as
     * its drag stack (correcting a diverging prediction). With no screen open the token is
     * simply not applied — the server drains its cursor whenever a screen closes
     * ({@code DEPOSIT_CARRIED}) and before saving, so its cursor is empty outside a
     * screen; a non-empty token here is only a stale in-flight prediction.
     */
    private static void adoptCarried(ItemStack carried) {
        ContainerSlotInput open = openContainerScreen();
        if (open != null) {
            InventoryDragDropHandler.DragState drag = open.getDragState();
            if (carried.isEmpty()) {
                drag.clear();
            } else {
                drag.draggedItemStack = carried.copy();
            }
        }
    }

    private static ContainerSlotInput openContainerScreen() {
        Game game = Game.getInstance();
        if (game == null) {
            return null;
        }
        if (game.getFurnaceScreen() != null && game.getFurnaceScreen().isVisible()
                && game.getFurnaceScreen().getController() != null
                && game.getFurnaceScreen().getController().getInputManager() != null) {
            return game.getFurnaceScreen().getController().getInputManager();
        }
        if (game.getWorkbenchScreen() != null && game.getWorkbenchScreen().isVisible()) {
            return game.getWorkbenchScreen().getSlotInput();
        }
        if (game.getInventoryScreen() != null && game.getInventoryScreen().isVisible()) {
            return game.getInventoryScreen().getSlotInput();
        }
        return null;
    }
}
