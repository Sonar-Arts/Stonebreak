package com.stonebreak.ui.hotbar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.openmason.engine.cenda.CendaLua;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.DataCell;
import com.openmason.engine.ui.data.DataCollection;
import com.openmason.engine.ui.runtime.UiElement;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.ui.inventoryScreen.core.InventoryFixtures;
import com.stonebreak.ui.inventoryScreen.core.InventoryInputManager;
import com.stonebreak.ui.runtime.GameUiDocuments;
import com.stonebreak.ui.runtime.GameUiHost;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * #300: the HUD and container contracts as live data. A hotbar selection moves the HUD's ring and its
 * tooltip without rebuilding the slots; leaving the world (the host epoch) empties every per-world
 * root the new screens read (the crafting grid, the character, the HUD and its hearts).
 */
@Tag("regression")
class HudContractsTest {

    @Test
    void aSelectionMovesTheRingAndTheTooltipWithoutRebuildingSlots() throws Exception {
        assumeTrue(CendaLua.isAvailable(), "Cenda Lua host unavailable");
        HudFixtures.Hud fixture = HudFixtures.hud("tip");
        try (DocumentHudCapture.Stage hud = new DocumentHudCapture.Stage(
                GameUiDocuments.readScreen(DocumentHudCapture.ID), 1920, 1080, 1f, fixture)) {
            List<UiElement> before = hud.view.instance().qAll(".hud-slot");
            float tipBefore = hud.q("#tip-box").rect().x();
            fixture.inventory().setSelectedHotbarSlotIndex(0);
            fixture.hotbar().displayItemTooltip(fixture.inventory().getHotbarSlot(0));
            hud.settle();
            List<UiElement> after = hud.view.instance().qAll(".hud-slot");
            for (int i = 0; i < before.size(); i++) {
                assertTrue(before.get(i) == after.get(i), "slot " + i + " kept its element");
            }
            assertTrue(selected(after.get(0)) && !selected(after.get(4)), "the ring moved to slot 0");
            float slot0 = after.get(0).rect().x() + after.get(0).rect().width() / 2f;
            var tip = hud.q("#tip-box").rect();
            assertEquals(Math.floor(slot0), Math.floor(tip.x() + tip.width() / 2f), 1.0, "the tooltip follows the selection");
            assertTrue(tip.x() < tipBefore, "it moved left, from slot 4 to slot 0");
        }
    }

    private static boolean selected(UiElement slot) {
        return slot.prop("stack") instanceof UiValue.Obj o && o.get("selected") instanceof UiValue.Bool b && b.value();
    }

    @Test
    void leavingTheWorldEmptiesThePerWorldRoots() {
        InventoryFixtures.Player3 player = InventoryFixtures.player("stocked");
        InventoryInputManager input = InventoryFixtures.input(player);
        HudFixtures.Hud fixture = HudFixtures.hud("hurt");
        GameUiHost.Services hudServices = DocumentHudCapture.services(fixture);
        GameUiHost host = new GameUiHost(new GameUiHost.Services() {
            @Override public void resume() { }
            @Override public void openStatistics() { }
            @Override public void openGlossary() { }
            @Override public void openSettings() { }
            @Override public void quitToMenu() { }
            @Override public int resync() { return -1; }
            @Override public UiValue.Obj settings() { return hudServices.settings(); }
            @Override public void applySettings(UiValue.Obj value) { }
            @Override public com.stonebreak.ui.inventoryScreen.handlers.ContainerSlotInput openContainer() { return input; }
            @Override public com.stonebreak.items.Inventory inventory() { return player.inventory(); }
            @Override public com.stonebreak.player.CharacterStats characterStats() { return player.stats(); }
            @Override public com.stonebreak.player.Player hudPlayer() { return fixture.player(); }
            @Override public com.stonebreak.ui.HotbarScreen hotbarScreen() { return fixture.hotbar(); }
        }, MultiplayerSession.Mode.SINGLEPLAYER);
        host.drain();
        assertEquals(4, items("crafting", host));
        assertEquals(10, items("hearts", host));
        assertTrue(bool(cell("character", host), "present") && bool(cell("hud", host), "present"));
        assertTrue(count(cell("craftOutput", host)) > 0, "the grid makes planks");

        host.sessionChanged(MultiplayerSession.Mode.MENU);
        host.host().drain(); // the epoch advance runs on the UI queue

        assertEquals(0, items("crafting", host));
        assertEquals(0, items("hearts", host));
        assertTrue(!bool(cell("character", host), "present") && !bool(cell("hud", host), "present"));
        assertEquals(0, count(cell("craftOutput", host)));
    }

    private static int items(String root, GameUiHost host) {
        return ((DataCollection) host.host().data().root(root).source()).items().size();
    }

    private static UiValue.Obj cell(String root, GameUiHost host) {
        return (UiValue.Obj) ((DataCell) host.host().data().root(root).source()).value();
    }

    private static boolean bool(UiValue.Obj o, String k) {
        return o.get(k) instanceof UiValue.Bool b && b.value();
    }

    private static int count(UiValue.Obj o) {
        return o.get("count") instanceof UiValue.Num n ? (int) n.value() : 0;
    }
}
