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
import static org.mockito.Mockito.when;

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

    /**
     * PR #410 review: a sync that rewrites the inventory's 2×2 grid must recompute the craft
     * output, or the stale output of the predicted grid stays clickable (a ghost craft).
     */
    @Test
    void aSyncedCraftGridRecomputesTheOutput() {
        World world = mock(World.class);
        com.stonebreak.crafting.CraftingManager recipes = new com.stonebreak.crafting.CraftingManager();
        recipes.registerRecipe(new com.stonebreak.crafting.Recipe("planks",
                java.util.List.of(java.util.List.of(new com.stonebreak.items.ItemStack(
                        com.stonebreak.blocks.BlockType.WOOD, 1))),
                new com.stonebreak.items.ItemStack(com.stonebreak.blocks.BlockType.WOOD_PLANKS, 4)));
        try (MockedStatic<Game> globals = mockStatic(Game.class)) {
            Game game = mock(Game.class);
            globals.when(Game::getInstance).thenReturn(game);
            Player player = new Player(world);
            var grid = new com.stonebreak.ui.inventoryScreen.core.InventoryCraftingManager(recipes);
            var input = new com.stonebreak.ui.inventoryScreen.core.InventoryInputManager(null,
                    player.getInventory(),
                    new com.stonebreak.ui.inventoryScreen.core.InventorySlotManager(player.getInventory(), grid),
                    grid);
            var screen = mock(com.stonebreak.ui.inventoryScreen.InventoryScreen.class);
            when(screen.isVisible()).thenReturn(true);
            when(screen.getSlotInput()).thenReturn(input);
            when(game.getInventoryScreen()).thenReturn(screen);

            // the prediction: wood in the grid shows planks
            grid.getCraftingInputSlots()[0] = new com.stonebreak.items.ItemStack(
                    com.stonebreak.blocks.BlockType.WOOD, 1);
            grid.updateCraftingOutput();
            assertEquals(com.stonebreak.blocks.BlockType.WOOD_PLANKS.getId(),
                    grid.getCraftingOutputSlot().getBlockTypeId());

            // the server says the grid is empty
            InventorySyncApplier.apply(player, payload(42, "0"));

            assertEquals(true, grid.getCraftingInputSlots()[0].isEmpty());
            assertEquals(true, grid.getCraftingOutputSlot().isEmpty(), "no ghost output left to click");
        }
    }

    /* ── Issue #353 on the player inventory: a sync older than our last action ── */

    private static String payloadWithSlot0(int count, String selectedToken) {
        String[] parts = new String[42];
        Arrays.fill(parts, ItemStackStateCodec.encode(ItemStackStateCodec.empty()));
        parts[0] = ItemStackStateCodec.encode(
                new com.stonebreak.items.ItemStack(com.stonebreak.blocks.BlockType.DIRT, count));
        parts[41] = selectedToken;
        return String.join("|", parts);
    }

    @Test
    void aSyncOlderThanOurLastActionKeepsThePrediction() {
        World world = mock(World.class);
        try (MockedStatic<Game> globals = mockStatic(Game.class)) {
            globals.when(Game::getInstance).thenReturn(mock(Game.class));
            com.stonebreak.network.SlotIntentSeq.markSessionStart();
            InventorySyncApplier.resetSession();
            Player player = new Player(world);

            InventorySyncApplier.applyServerSync(player, payloadWithSlot0(5, "0"), 0);
            assertEquals(5, player.getInventory().getHotbarSlot(0).getCount());

            int seq = com.stonebreak.network.SlotIntentSeq.next(); // we predicted + sent an action
            InventorySyncApplier.applyServerSync(player, payloadWithSlot0(9, "0"), seq - 1);
            assertEquals(5, player.getInventory().getHotbarSlot(0).getCount(), "stale sync ignored");

            InventorySyncApplier.applyServerSync(player, payloadWithSlot0(9, "0"), seq);
            assertEquals(9, player.getInventory().getHotbarSlot(0).getCount(), "the answer applies");
        }
    }

    @Test
    void theSelectionIsSeededOnceThenStaysTheClients() {
        World world = mock(World.class);
        try (MockedStatic<Game> globals = mockStatic(Game.class)) {
            globals.when(Game::getInstance).thenReturn(mock(Game.class));
            com.stonebreak.network.SlotIntentSeq.markSessionStart();
            InventorySyncApplier.resetSession();
            Player player = new Player(world);

            InventorySyncApplier.applyServerSync(player, payloadWithSlot0(1, "4"), 0);
            assertEquals(4, player.getInventory().getSelectedHotbarSlotIndex(), "seeded from the server");

            player.getInventory().setSelectedHotbarSlotIndex(6); // scrolled; HotbarSelectC2S in flight
            InventorySyncApplier.applyServerSync(player, payloadWithSlot0(1, "4"), 0);
            assertEquals(6, player.getInventory().getSelectedHotbarSlotIndex(), "a pickup sync never reverts it");
        }
    }
}
