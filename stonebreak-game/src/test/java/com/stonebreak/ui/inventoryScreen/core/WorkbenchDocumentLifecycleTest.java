package com.stonebreak.ui.inventoryScreen.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openmason.engine.cenda.CendaLua;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.ui.data.CallSite;
import com.openmason.engine.ui.data.UiScope;
import com.openmason.engine.ui.runtime.paint.UiDocumentView;
import com.openmason.engine.util.BlockPos;
import com.stonebreak.core.GameState;
import com.stonebreak.crafting.CraftingManager;
import com.stonebreak.input.InputHandler;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.ui.fidelity.LegacyUiRaster;
import com.stonebreak.ui.inventoryScreen.handlers.ContainerSlotInput;
import com.stonebreak.ui.runtime.GameUiDocuments;
import com.stonebreak.ui.runtime.GameUiHost;
import com.stonebreak.ui.runtime.GameUiInput;
import com.stonebreak.ui.runtime.GameUiScriptServices;
import com.stonebreak.ui.runtime.providers.GameDrawProviders;
import com.stonebreak.ui.runtime.screens.ScreenPresentation;
import com.stonebreak.ui.runtime.screens.UiLayer;
import com.stonebreak.ui.support.UiTestFixtures;
import com.stonebreak.ui.workbench.WorkbenchScreen;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * #300: the crafting table's world-bound lifecycle. One game host serves table after table: every
 * close of the document releases its scope, its data subscriptions and its place in the input stack,
 * so reopening twenty times leaves nothing behind; a slot action after the screen closed is refused
 * without touching any item; and the screen shows its document only in WORKBENCH_UI (the recipe book
 * opened over the table hides it, Escape back shows it again, closing hides it).
 */
@Tag("regression")
class WorkbenchDocumentLifecycleTest {

    private static final int W = 1920;
    private static final int H = 1080;
    private static final List<String> ROOTS = List.of("inventory", "carried", "hotbar", "crafting", "craftOutput");

    @Test
    void reopeningReleasesEverythingAndLateActionsAreRefused() throws Exception {
        if (!CendaLua.isAvailable()) {
            assumeTrue(Boolean.getBoolean("ui.script.allowMissingNative"), "Cenda Lua host unavailable");
            throw new AssertionError("Cenda Lua host unavailable (build openmason-engine/cenda/build-kernels.sh)");
        }
        SbuiArchive sbui = GameUiDocuments.readScreen(WorkbenchScreen.DOCUMENT_ID);
        WorkbenchFixtures.Table table = WorkbenchFixtures.table("stocked");
        WorkbenchInputManager[] in = new WorkbenchInputManager[1];
        WorkbenchController controller = WorkbenchFixtures.controller(table, in);
        boolean[] open = {false};
        GameUiHost host = new GameUiHost(services(in[0], open), MultiplayerSession.Mode.SINGLEPLAYER);

        try (LegacyUiRaster raster = new LegacyUiRaster(W, H, 1f);
             GameDrawProviders providers = new GameDrawProviders(null, raster.backend()::getMinecraftTypeface)) {
            host.drain();
            int views = GameUiInput.get().views().size();
            Map<String, Integer> subscribers = subscribers(host);

            for (int i = 0; i < 20; i++) {
                controller.bind(new com.stonebreak.blocks.workbench.WorkbenchState(new BlockPos(i, 64, 0)));
                open[0] = true;
                UiDocumentView view = GameUiDocuments.openBound(sbui, Map.of(), raster.backend()::getMinecraftTypeface,
                    providers.providers(), host.host(), null, new GameUiScriptServices(null), UiLayer.SCREEN);
                for (int f = 0; f < 3; f++) {
                    host.drain();
                    GameUiDocuments.frame(view, 0.016, 0);
                    view.layout(W, H, 1f, 1f);
                }
                assertEquals(1, host.host().openScopes().size(), "one scope while open");

                GameUiDocuments.close(view);
                controller.close();
                open[0] = false;
                host.drain();

                assertTrue(host.host().openScopes().isEmpty(), "close " + i + " released the scope");
                assertEquals(subscribers, subscribers(host), "close " + i + " released every data subscription");
                assertEquals(views, GameUiInput.get().views().size(), "close " + i + " left the input stack");
            }

            UiScope late = host.host().openScope("stonebreak:ui/screens/workbench", null, p -> { });
            CallSite site = late.site("late", CallSite.Origin.SCRIPT);
            String before = ContainerParity.stack(table.inventory().getMainInventorySlot(0));
            var call = late.invoke("stonebreak:inventory.slot-press",
                new UiValue.Obj(Map.of("slot", UiValue.of("main:0"))), site);
            host.drain();
            assertTrue(call.error() != null, "refused: no container screen is open, got " + call.state());
            assertEquals(before, ContainerParity.stack(table.inventory().getMainInventorySlot(0)), "nothing moved");
            assertFalse(in[0].getDragState().isDragging());
            late.close();
        }
    }

    @Test
    void theDocumentShowsOnlyInTheWorkbenchState() {
        try (LegacyUiRaster raster = new LegacyUiRaster(W, H, 1f)) {
            Renderer renderer = mock(Renderer.class);
            when(renderer.getSkijaBackend()).thenReturn(raster.backend());
            WorkbenchScreen screen = new WorkbenchScreen(null, UiTestFixtures.emptyInventory(), renderer,
                mock(UIRenderer.class), mock(InputHandler.class), new CraftingManager());
            List<String> events = new ArrayList<>();
            screen.setPresentation(new ScreenPresentation() {
                boolean shown;
                @Override public void shown() { shown = true; events.add("shown"); }
                @Override public void hidden() { shown = false; events.add("hidden"); }
                @Override public boolean paint(int w, int h) { return shown; }
                @Override public boolean showing() { return shown; }
            });

            screen.open(new BlockPos(0, 64, 0));
            assertTrue(screen.presentationShowing());
            screen.syncPresentation(GameState.RECIPE_BOOK_UI);   // the recipe book over the table
            assertFalse(screen.presentationShowing());
            assertTrue(screen.isVisible(), "the table stays open underneath");
            screen.syncPresentation(GameState.WORKBENCH_UI);     // Escape back
            assertTrue(screen.presentationShowing());
            screen.close();
            assertFalse(screen.presentationShowing());
            screen.syncPresentation(GameState.WORKBENCH_UI);     // a closed table never shows
            assertFalse(screen.presentationShowing());
            assertEquals(List.of("shown", "hidden", "shown", "hidden"), events);
        }
    }

    private static Map<String, Integer> subscribers(GameUiHost host) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String root : ROOTS) {
            out.put(root, host.host().data().root(root).source().subscriberCount());
        }
        return out;
    }

    private static GameUiHost.Services services(ContainerSlotInput screen, boolean[] open) {
        GameUiHost.Services base = DocumentWorkbenchCapture.services(screen, null, W, H, new ArrayList<>());
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
        };
    }
}
