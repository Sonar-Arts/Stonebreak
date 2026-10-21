package com.stonebreak.input;

import com.stonebreak.core.Game;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemType;
import com.stonebreak.items.ItemStack;
import com.stonebreak.player.Player;
import com.stonebreak.world.World;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Eating the last food in the selected hotbar slot pulls the next matching stack
 * in (#355) — consumption must go through Inventory.removeItem so its depletion
 * auto-switch fires, exactly like arrows on bow release.
 */
class FoodAutoSwitchTest {

    private static final Vector3f SPAWN = new Vector3f(0f, 80f, 0f);
    private static final float HEALED = 4.0f;

    @Test
    void eatingTheLastBananaPullsTheNextHotbarStackIn() {
        try (PlayerSession session = new PlayerSession()) {
            Player player = session.player;
            Inventory inventory = player.getInventory();
            inventory.setHotbarSlot(0, new ItemStack(ItemType.BANANA, 1));
            inventory.setHotbarSlot(2, new ItemStack(ItemType.BANANA, 5));

            eat(player);

            assertMovedStackIn(inventory, 5);
            assertTrue(inventory.getHotbarSlot(2).isEmpty(), "source hotbar slot drained");
            assertHealed(player);
        }
    }

    @Test
    void eatingTheLastBananaPullsFromMainInventoryWhenNoHotbarMatch() {
        try (PlayerSession session = new PlayerSession()) {
            Player player = session.player;
            Inventory inventory = player.getInventory();
            inventory.setHotbarSlot(0, new ItemStack(ItemType.BANANA, 1));
            inventory.setMainInventorySlot(0, new ItemStack(ItemType.BANANA, 3));

            eat(player);

            assertMovedStackIn(inventory, 3);
            assertTrue(inventory.getMainInventorySlot(0).isEmpty(), "source main-inventory slot drained");
            assertHealed(player);
        }
    }

    @Test
    void eatingTheLastBananaWithNoOtherStackLeavesTheSlotEmpty() {
        try (PlayerSession session = new PlayerSession()) {
            Player player = session.player;
            Inventory inventory = player.getInventory();
            inventory.setHotbarSlot(0, new ItemStack(ItemType.BANANA, 1));

            eat(player);

            assertTrue(inventory.getSelectedHotbarSlot().isEmpty(), "nothing to switch in, slot stays empty");
            assertHealed(player);
        }
    }

    @Test
    void eatingOneOfManyLeavesTheStackInPlace() {
        try (PlayerSession session = new PlayerSession()) {
            Player player = session.player;
            Inventory inventory = player.getInventory();
            inventory.setHotbarSlot(0, new ItemStack(ItemType.BANANA, 5));
            inventory.setHotbarSlot(2, new ItemStack(ItemType.BANANA, 3));

            eat(player);

            assertEquals(4, inventory.getSelectedHotbarSlot().getCount(),
                    "the selected stack is drained, not replaced");
            assertEquals(3, inventory.getHotbarSlot(2).getCount(),
                    "other stacks are untouched while the selected one lasts");
            assertHealed(player);
        }
    }

    /** Pre-damages the player so heal(4) is observable (heal clamps at max health), then uses the held food. */
    private void eat(Player player) {
        player.setHealth(player.getMaxHealth() - 10f);
        new WorldMouseHandler().handleUse(player);
    }

    private void assertMovedStackIn(Inventory inventory, int expectedCount) {
        ItemStack selected = inventory.getSelectedHotbarSlot();
        assertSame(ItemType.BANANA, selected.asItemType(), "next matching stack moved into the selected slot");
        assertEquals(expectedCount, selected.getCount());
    }

    private void assertHealed(Player player) {
        assertEquals(player.getMaxHealth() - 10f + HEALED, player.getHealth(), 0.001f);
    }

    /** Headless player: mocked world + statics for the duration of one test. */
    private static final class PlayerSession implements AutoCloseable {
        final World world = mock(World.class);
        final Player player;
        final MockedStatic<Game> globals;

        PlayerSession() {
            when(world.getSpawnPosition()).thenAnswer(i -> new Vector3f(SPAWN));
            globals = mockStatic(Game.class);
            globals.when(Game::getInstance).thenReturn(mock(Game.class));
            globals.when(Game::getWorld).thenReturn(world);
            player = new Player(world);
        }

        @Override
        public void close() {
            globals.close();
        }
    }
}
