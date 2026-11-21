package com.stonebreak.ui.chest;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.ui.cenda.CendaLua;
import com.openmason.engine.ui.data.CallSite;
import com.openmason.engine.ui.data.UiScope;
import com.openmason.engine.ui.runtime.paint.UiDocumentView;
import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.container.ChestState;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.ui.fidelity.UiRaster;
import com.stonebreak.ui.inventoryScreen.handlers.ContainerSlotInput;
import com.stonebreak.ui.runtime.GameUiDocuments;
import com.stonebreak.ui.runtime.GameUiHost;
import com.stonebreak.ui.runtime.GameUiScriptServices;
import com.stonebreak.ui.runtime.contracts.SettingsContract;
import com.stonebreak.ui.runtime.providers.GameDrawProviders;
import com.stonebreak.ui.runtime.screens.UiLayer;
import com.stonebreak.ui.support.UiTestFixtures;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The shipped {@code chest.sbui} opens bound to the game host, and its slot addresses land on the
 * chest screen's slot rules: pressing {@code chest:0} picks the stack up, pressing {@code main:0}
 * puts it down in the inventory — the path every item slot in the document takes.
 */
@Tag("regression")
class ChestDocumentTest {

    private static final int W = 1920;
    private static final int H = 1080;

    @Test
    void theChestDocumentMovesAStackFromTheChestToTheInventory() throws Exception {
        if (!CendaLua.isAvailable()) {
            assumeTrue(Boolean.getBoolean("ui.script.allowMissingNative"), "Cenda Lua host unavailable");
            throw new AssertionError("Cenda Lua host unavailable (scripts/openmason-local.sh)");
        }
        SbuiArchive sbui = GameUiDocuments.readScreen("chest");
        Inventory inv = UiTestFixtures.emptyInventory();
        ChestController controller = new ChestController(null);
        ChestInputManager input = new ChestInputManager(null, inv, controller);
        controller.setInputManager(input);
        controller.setChestAt(p -> true);
        ChestState chest = new ChestState(new BlockPos(0, 64, 0));
        chest.getSlots()[0] = new ItemStack(ItemType.COOKED_BEEF, 7);
        controller.bind(chest);
        GameUiHost host = new GameUiHost(services(input, inv), MultiplayerSession.Mode.SINGLEPLAYER);

        try (UiRaster raster = new UiRaster(W, H, 1f);
             GameDrawProviders providers = new GameDrawProviders((t, x, y, s) -> { },
                 raster.backend()::getMinecraftTypeface)) {
            host.drain();
            UiDocumentView view = GameUiDocuments.openBound(sbui, Map.of(), raster.backend()::getMinecraftTypeface,
                providers.providers(), host.host(), null, new GameUiScriptServices(null), UiLayer.SCREEN);
            assertNotNull(view, "the document passes its activation, asset and input gates");
            for (int f = 0; f < 3; f++) {
                host.drain();
                GameUiDocuments.frame(view, 0.016, 0);
                view.layout(W, H, 1f, 1f);
            }

            UiScope scope = host.host().openScope("stonebreak:ui/screens/chest", null, p -> { });
            CallSite site = scope.site("test", CallSite.Origin.SCRIPT);
            press(host, scope, site, "chest:0");
            assertTrue(input.getDragState().isDragging(), "picked up from the chest");
            assertTrue(chest.getSlots()[0].isEmpty());

            press(host, scope, site, "main:0");
            assertSame(ItemType.COOKED_BEEF, inv.getMainInventorySlot(0).getItem());
            assertEquals(7, inv.getMainInventorySlot(0).getCount());
            scope.close();
            GameUiDocuments.close(view);
        }
    }

    private static void press(GameUiHost host, UiScope scope, CallSite site, String slot) {
        var call = scope.invoke("stonebreak:inventory.slot-press",
            new UiValue.Obj(Map.of("slot", UiValue.of(slot))), site);
        host.drain();
        assertTrue(call.error() == null, "press " + slot + ": " + call.error());
        scope.invoke("stonebreak:inventory.slot-release", new UiValue.Obj(Map.of()), site);
        host.drain();
    }

    private static GameUiHost.Services services(ContainerSlotInput screen, Inventory inv) {
        return new GameUiHost.Services() {
            @Override public void resume() { }
            @Override public void openStatistics() { }
            @Override public void openGlossary() { }
            @Override public void openSettings() { }
            @Override public void quitToMenu() { }
            @Override public int resync() { return -1; }
            @Override public UiValue.Obj settings() {
                return SettingsContract.read(com.stonebreak.config.Settings.getInstance());
            }
            @Override public void applySettings(UiValue.Obj value) { }
            @Override public ContainerSlotInput openContainer() { return screen; }
            @Override public int[] screenSize() { return new int[]{W, H}; }
            @Override public Inventory inventory() { return inv; }
        };
    }
}
