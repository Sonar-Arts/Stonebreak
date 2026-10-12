package com.stonebreak.input;

import com.stonebreak.core.Game;
import com.stonebreak.items.Inventory;
import com.stonebreak.player.Player;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_1;

/**
 * Applies hotbar selection to the player's inventory, driven by the number keys (1–9) and
 * scroll-wheel cycling. The inventory's selected index is the one source of truth: UI documents
 * select slots too ({@code stonebreak:hotbar.select}), so cycling always starts from it.
 */
final class HotbarSelector {

    /** Polls number keys 1–9 and selects the matching slot. PLAYING-state gating is the caller's job. */
    void pollNumberKeys(long window) {
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            if (PolledKeys.isDown(window, GLFW_KEY_1 + i)) {
                select(i);
            }
        }
    }

    /** Cycles the selection by one slot: positive offset = next, negative = previous. */
    void cycle(double yOffset) {
        int current = current();
        int newIndex = current;
        if (yOffset > 0) {
            newIndex = (current + 1) % Inventory.HOTBAR_SIZE;
        } else if (yOffset < 0) {
            newIndex = (current - 1 + Inventory.HOTBAR_SIZE) % Inventory.HOTBAR_SIZE;
        }
        select(newIndex);
    }

    private static int current() {
        Player player = Game.getPlayer();
        Inventory inventory = player == null ? null : player.getInventory();
        return inventory == null ? 0 : inventory.getSelectedHotbarSlotIndex();
    }

    private void select(int index) {
        if (index < 0 || index >= Inventory.HOTBAR_SIZE) {
            return;
        }
        Player player = Game.getPlayer();
        if (player != null && player.getInventory() != null) {
            player.getInventory().setSelectedHotbarSlotIndex(index);
        }
    }
}
