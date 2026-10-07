package com.stonebreak.ui.furnace.core;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.CallSite;
import com.openmason.engine.ui.data.DataPath;
import com.openmason.engine.ui.data.UiScope;
import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.furnace.FurnaceState;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.ui.inventoryScreen.handlers.ContainerSlotInput;
import com.stonebreak.ui.runtime.GameUiHost;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.stonebreak.ui.support.UiTestFixtures.emptyInventory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A furnace screen's slots by address (#298 groundwork): a document's slot clicks run
 * {@link FurnaceInputManager}'s own rules, the edit reaches the server through the controller's
 * per-frame slot sync (#320), and the host publishes the slot contents.
 */
class FurnaceSlotAddressTest {

    private static final int W = 1920;
    private static final int H = 1080;

    @Test
    void documentSlotClicksUseTheFurnaceRulesAndSync() {
        Inventory inv = emptyInventory();
        FurnaceController controller = new FurnaceController(null, inv, null, null, null);
        FurnaceInputManager input = new FurnaceInputManager(null, inv, controller);
        controller.setInputManager(input);
        List<String> sent = new ArrayList<>();
        controller.setSlotSink((pos, slots) -> sent.add(slots));
        FurnaceState furnace = new FurnaceState(new BlockPos(0, 64, 0));
        controller.bind(furnace);

        GameUiHost game = new GameUiHost(new GameUiHost.Services() {
            @Override public void resume() { }
            @Override public void openStatistics() { }
            @Override public void openGlossary() { }
            @Override public void openSettings() { }
            @Override public void quitToMenu() { }
            @Override public int resync() { return 0; }
            @Override public UiValue.Obj settings() { return new UiValue.Obj(Map.of()); }
            @Override public void applySettings(UiValue.Obj value) { }
            @Override public ContainerSlotInput openContainer() { return input; }
            @Override public int[] screenSize() { return new int[]{W, H}; }
            @Override public Inventory inventory() { return inv; }
        }, MultiplayerSession.Mode.SINGLEPLAYER);
        game.furnaceOpened(furnace);
        UiScope scope = game.host().openScope("stonebreak:ui/furnace", null, p -> { });
        CallSite site = scope.site("slot", CallSite.Origin.SCRIPT);

        inv.setMainInventorySlot(0, new ItemStack(BlockType.IRON_ORE, 4));
        scope.invoke("stonebreak:inventory.slot-click", new UiValue.Obj(Map.of("slot", UiValue.of("main:0"))), site);
        scope.invoke("stonebreak:inventory.slot-click", new UiValue.Obj(Map.of("slot", UiValue.of("ingredient"))), site);
        assertEquals(4, furnace.getIngredient().getCount(), "placed by the furnace's own drop rule");

        controller.update(0f);
        assertEquals(1, sent.size(), "the edit reaches the server through the controller's slot sync");

        game.drain();
        UiValue v = game.host().data().root("furnace").source().state().valueOrNull();
        assertEquals(UiValue.of(4), DataPath.parse("furnace.ingredientSlot.count").evaluate(v));

        // the output slot refuses drops, as in the legacy screen
        inv.setMainInventorySlot(1, new ItemStack(BlockType.DIRT, 2));
        scope.invoke("stonebreak:inventory.slot-click", new UiValue.Obj(Map.of("slot", UiValue.of("main:1"))), site);
        scope.invoke("stonebreak:inventory.slot-click", new UiValue.Obj(Map.of("slot", UiValue.of("output"))), site);
        assertTrue(furnace.getOutput().isEmpty());
        scope.close();
    }

    @Test
    void everyFurnaceAddressResolves() {
        Inventory inv = emptyInventory();
        FurnaceController controller = new FurnaceController(null, inv, null, null, null);
        FurnaceInputManager input = new FurnaceInputManager(null, inv, controller);
        for (String s : List.of("ingredient", "fuel", "output", "hotbar:0", "hotbar:8", "main:0", "main:26", "outside")) {
            assertNotNull(input.slotOrigin(s, W, H), s);
        }
        for (String s : List.of("craft:0", "main:27", "hotbar:-1", "nonsense")) {
            assertNull(input.slotOrigin(s, W, H), s);
        }
    }
}
