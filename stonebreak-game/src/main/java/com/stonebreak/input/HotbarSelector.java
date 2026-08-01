package com.stonebreak.input;

import com.stonebreak.core.Game;
import com.stonebreak.items.Inventory;
import com.stonebreak.player.Player;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_1;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.glfwGetKey;

/**
 * Tracks the desired hotbar slot and applies it to the player's inventory,
 * driven by the number keys (1–9) and scroll-wheel cycling.
 */
final class HotbarSelector {

    private int selectedIndex = 0;

    /** Polls number keys 1–9 and selects the matching slot. PLAYING-state gating is the caller's job. */
    void pollNumberKeys(long window) {
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            if (glfwGetKey(window, GLFW_KEY_1 + i) == GLFW_PRESS) {
                select(i);
            }
        }
    }

    /** Cycles the selection by one slot: positive offset = next, negative = previous. */
    void cycle(double yOffset) {
        int newIndex = selectedIndex;
        if (yOffset > 0) {
            newIndex = (selectedIndex + 1) % Inventory.HOTBAR_SIZE;
        } else if (yOffset < 0) {
            newIndex = (selectedIndex - 1 + Inventory.HOTBAR_SIZE) % Inventory.HOTBAR_SIZE;
        }
        select(newIndex);
    }

    private void select(int index) {
        if (index < 0 || index >= Inventory.HOTBAR_SIZE) {
            return;
        }
        selectedIndex = index;
        Player player = Game.getPlayer();
        if (player != null && player.getInventory() != null) {
            player.getInventory().setSelectedHotbarSlotIndex(selectedIndex);
        }
    }
}
