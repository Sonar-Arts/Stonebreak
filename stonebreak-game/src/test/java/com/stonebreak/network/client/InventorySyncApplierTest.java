package com.stonebreak.network.client;

import com.stonebreak.core.Game;
import com.stonebreak.items.ItemStackStateCodec;
import com.stonebreak.player.Player;
import com.stonebreak.world.World;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * InventorySyncS2C application (issue #334): the server's payload is exactly 42 tokens
 * (36 slots + carried + 4 craft-grid + selected hotbar slot + held id), so the
 * selected-slot token must apply at that length — an off-by-one guard silently dropped
 * the server's authoritative selection.
 */
class InventorySyncApplierTest {

    /** Encodes an {@code n}-token payload; {@code selectedToken} sits at index 41. */
    private static String payload(int n, String selectedToken) {
        String[] parts = new String[n];
        Arrays.fill(parts, ItemStackStateCodec.encode(ItemStackStateCodec.empty()));
        if (n == 42) {
            parts[41] = selectedToken;
        }
        return String.join("|", parts);
    }

    @Test
    void selectedSlotAppliesFromAFullPayload() {
        World world = mock(World.class);
        try (MockedStatic<Game> globals = mockStatic(Game.class)) {
            globals.when(Game::getInstance).thenReturn(mock(Game.class));

            Player player = new Player(world);
            InventorySyncApplier.apply(player, payload(42, "5"));
            assertEquals(5, player.getInventory().getSelectedHotbarSlotIndex());
        }
    }

    @Test
    void selectedSlotNeedsTheFullPayload() {
        // 41 tokens = no selected-slot token; the mirror keeps its own selection.
        World world = mock(World.class);
        try (MockedStatic<Game> globals = mockStatic(Game.class)) {
            globals.when(Game::getInstance).thenReturn(mock(Game.class));

            Player player = new Player(world);
            InventorySyncApplier.apply(player, payload(41, "5"));
            assertEquals(0, player.getInventory().getSelectedHotbarSlotIndex());
        }
    }

    @Test
    void outOfRangeSelectedTokenIgnored() {
        World world = mock(World.class);
        try (MockedStatic<Game> globals = mockStatic(Game.class)) {
            globals.when(Game::getInstance).thenReturn(mock(Game.class));

            Player player = new Player(world);
            InventorySyncApplier.apply(player, payload(42, "9"));
            assertEquals(0, player.getInventory().getSelectedHotbarSlotIndex());
        }
    }
}
