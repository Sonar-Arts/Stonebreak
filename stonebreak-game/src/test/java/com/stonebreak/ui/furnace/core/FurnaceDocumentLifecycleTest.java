package com.stonebreak.ui.furnace.core;

import com.openmason.engine.cenda.CendaLua;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.ui.data.CallSite;
import com.openmason.engine.ui.data.UiScope;
import com.openmason.engine.ui.runtime.paint.UiDocumentView;
import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.furnace.FurnaceState;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.ui.fidelity.LegacyUiRaster;
import com.stonebreak.ui.inventoryScreen.handlers.ContainerSlotInput;
import com.stonebreak.ui.runtime.GameUiDocuments;
import com.stonebreak.ui.runtime.GameUiHost;
import com.stonebreak.ui.runtime.GameUiInput;
import com.stonebreak.ui.runtime.GameUiScriptServices;
import com.stonebreak.ui.runtime.providers.GameDrawProviders;
import com.stonebreak.ui.runtime.screens.UiLayer;
import com.stonebreak.ui.support.UiTestFixtures;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * #298: the world-bound lifecycle. One game host serves furnace after furnace, as in a play
 * session: every close of the document releases its scope, its data subscriptions and its place in
 * the input stack, so reopening twenty times leaves nothing behind; and a slot action that arrives
 * after the screen closed is refused without touching any item.
 */
@Tag("regression")
class FurnaceDocumentLifecycleTest {

    private static final int W = 1920;
    private static final int H = 1080;
    private static final List<String> ROOTS = List.of("furnace", "inventory", "carried", "hotbar");

    @Test
    void reopeningReleasesEverythingAndLateActionsAreRefused() throws Exception {
        if (!CendaLua.isAvailable()) {
            assumeTrue(Boolean.getBoolean("ui.script.allowMissingNative"), "Cenda Lua host unavailable");
            throw new AssertionError("Cenda Lua host unavailable (build openmason-engine/cenda/build-kernels.sh)");
        }
        SbuiArchive sbui = GameUiDocuments.readScreen("furnace");
        Inventory inv = UiTestFixtures.emptyInventory();
        inv.setMainInventorySlot(0, new ItemStack(BlockType.IRON_ORE, 10));
        FurnaceController controller = new FurnaceController(null, inv, null, DocumentFurnaceCapture.smelting(), null);
        FurnaceInputManager input = new FurnaceInputManager(null, inv, controller);
        controller.setInputManager(input);
        controller.setSlotSink((p, s) -> { });
        controller.setFurnaceAt(p -> true);
        boolean[] open = {false};
        GameUiHost host = new GameUiHost(services(input, inv, open), MultiplayerSession.Mode.SINGLEPLAYER);

        try (LegacyUiRaster raster = new LegacyUiRaster(W, H, 1f);
             GameDrawProviders providers = new GameDrawProviders((t, x, y, s) -> { },
                 raster.backend()::getMinecraftTypeface)) {
            host.drain();
            int views = GameUiInput.get().views().size();
            Map<String, Integer> subscribers = subscribers(host);

            for (int i = 0; i < 20; i++) {
                FurnaceState furnace = new FurnaceState(new BlockPos(i, 64, 0));
                controller.bind(furnace);
                open[0] = true;
                host.furnaceOpened(furnace);
                UiDocumentView view = GameUiDocuments.openBound(sbui, Map.of(), raster.backend()::getMinecraftTypeface,
                    providers.providers(), host.host(), null, new GameUiScriptServices(null), UiLayer.SCREEN);
                for (int f = 0; f < 3; f++) { // frames without painting: block icons need the game's GL
                    host.drain();
                    GameUiDocuments.frame(view, 0.016, 0);
                    view.layout(W, H, 1f, 1f);
                }
                assertEquals(1, host.host().openScopes().size(), "one scope while open");

                GameUiDocuments.close(view);
                controller.close();
                open[0] = false;
                host.furnaceClosed();
                host.drain();

                assertTrue(host.host().openScopes().isEmpty(), "close " + i + " released the scope");
                assertEquals(subscribers, subscribers(host), "close " + i + " released every data subscription");
                assertEquals(views, GameUiInput.get().views().size(), "close " + i + " left the input stack");
            }

            // a slot click that arrives after the screen closed (a queued script request, a stale handle)
            UiScope late = host.host().openScope("stonebreak:ui/screens/furnace", null, p -> { });
            CallSite site = late.site("late", CallSite.Origin.SCRIPT);
            String before = inv.getMainInventorySlot(0).getCount() + "/" + input.getDragState().isDragging();
            var call = late.invoke("stonebreak:inventory.slot-press",
                new UiValue.Obj(Map.of("slot", UiValue.of("main:0"))), site);
            host.drain();
            assertTrue(call.error() != null, "refused: no container screen is open, got " + call.state());
            assertEquals(before, inv.getMainInventorySlot(0).getCount() + "/" + input.getDragState().isDragging(),
                "nothing moved");
            late.close();
        }
    }

    private static Map<String, Integer> subscribers(GameUiHost host) {
        java.util.LinkedHashMap<String, Integer> out = new java.util.LinkedHashMap<>();
        for (String root : ROOTS) {
            out.put(root, host.host().data().root(root).source().subscriberCount());
        }
        return out;
    }

    private static GameUiHost.Services services(ContainerSlotInput screen, Inventory inv, boolean[] open) {
        GameUiHost.Services base = DocumentFurnaceCapture.services(screen, inv, W, H);
        return new GameUiHost.Services() {
            @Override public void resume() { }
            @Override public void openStatistics() { }
            @Override public void openGlossary() { }
            @Override public void openSettings() { }
            @Override public void quitToMenu() { }
            @Override public int resync() { return -1; }
            @Override public UiValue.Obj settings() { return base.settings(); }
            @Override public void applySettings(UiValue.Obj value) { }
            @Override public ContainerSlotInput openContainer() { return open[0] ? screen : null; }
            @Override public int[] screenSize() { return new int[]{W, H}; }
            @Override public Inventory inventory() { return inv; }
        };
    }
}
