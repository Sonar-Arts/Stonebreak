package com.stonebreak.ui.runtime;

import com.openmason.engine.format.omui.UiHostProfile;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.ActionCall;
import com.openmason.engine.ui.data.CallSite;
import com.openmason.engine.ui.data.DataCollection;
import com.openmason.engine.ui.data.DataPath;
import com.openmason.engine.ui.data.ListChange;
import com.openmason.engine.ui.data.Subscription;
import com.openmason.engine.ui.data.UiScope;
import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.furnace.FurnaceState;
import com.stonebreak.config.Settings;
import com.stonebreak.crafting.CraftingManager;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.ui.inventoryScreen.core.InventoryCraftingManager;
import com.stonebreak.ui.inventoryScreen.core.InventoryInputManager;
import com.stonebreak.ui.inventoryScreen.core.InventoryLayoutCalculator;
import com.stonebreak.ui.inventoryScreen.core.InventorySlotManager;
import com.stonebreak.ui.inventoryScreen.core.WorkbenchInputManager;
import com.stonebreak.ui.inventoryScreen.handlers.ContainerSlotInput;
import com.stonebreak.ui.runtime.contracts.SettingsContract;
import com.stonebreak.ui.runtime.contracts.Vitals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.stonebreak.ui.support.UiTestFixtures.emptyInventory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The container, HUD and settings contracts added after the #282 foundation review: slot
 * actions run the legacy screens' own rules, grids/carried/vitals publish only what changed,
 * the furnace can never show a stale or foreign value, and world leave resets per-world data.
 */
class GameUiHostContractsTest {

    private static final int W = 1920;
    private static final int H = 1080;

    /** A game with a real inventory and real legacy slot rules, and nothing else. */
    private static final class Services implements GameUiHost.Services {
        Inventory inventory = emptyInventory();
        ContainerSlotInput container;
        Vitals vitals = Vitals.NONE;
        int closes;
        UiValue.Obj settings = SettingsContract.read(Settings.defaults());

        @Override public void resume() { }
        @Override public void openStatistics() { }
        @Override public void openGlossary() { }
        @Override public void openSettings() { }
        @Override public void quitToMenu() { }
        @Override public int resync() { return 0; }
        @Override public UiValue.Obj settings() { return settings; }

        @Override public void applySettings(UiValue.Obj value) { }

        @Override public ContainerSlotInput openContainer() { return container; }
        @Override public void closeContainer() { closes++; container = null; }
        @Override public int[] screenSize() { return new int[]{W, H}; }
        @Override public Inventory inventory() { return inventory; }
        @Override public Vitals vitals() { return vitals; }
    }

    private Services services;
    private GameUiHost game;
    private UiScope scope;
    private CallSite site;

    @BeforeEach
    void setUp() {
        services = new Services();
        game = new GameUiHost(services, MultiplayerSession.Mode.SINGLEPLAYER);
        scope = game.host().openScope("stonebreak:ui/inventory", null, p -> { });
        site = scope.site("slot", CallSite.Origin.SCRIPT);
    }

    private InventoryInputManager inventoryScreen() {
        InventoryCraftingManager crafting = new InventoryCraftingManager(new CraftingManager());
        return new InventoryInputManager(null, services.inventory,
            new InventorySlotManager(services.inventory, crafting), crafting);
    }

    private ActionCall slot(String action, String slot, int button, boolean shift) {
        return scope.invoke("stonebreak:inventory." + action, new UiValue.Obj(Map.of("slot", UiValue.of(slot),
            "button", UiValue.of(button), "shift", UiValue.of(shift))), site);
    }

    private ActionCall click(String slot) {
        return slot("slot-click", slot, 0, false);
    }

    private UiValue carried() {
        return game.host().data().root("carried").source().state().valueOrNull();
    }

    private static long count(UiValue stack) {
        return (long) ((UiValue.Num) ((UiValue.Obj) stack).get("count")).value();
    }

    private UiValue row(String root, int i) {
        return ((DataCollection) game.host().data().root(root).source()).items().get(i);
    }

    @Test
    void slotClicksRunTheInventoryScreensOwnRules() {
        services.container = inventoryScreen();
        services.inventory.setMainInventorySlot(0, new ItemStack(BlockType.DIRT, 10));
        services.inventory.setMainInventorySlot(5, new ItemStack(BlockType.DIRT, 5));

        assertEquals(ActionCall.State.SUCCEEDED, click("main:0").state());
        assertEquals(10, count(carried()), "the stack is on the cursor, and the document already sees it");
        assertTrue(services.inventory.getMainInventorySlot(0).isEmpty());

        assertEquals(ActionCall.State.SUCCEEDED, click("main:5").state());
        assertEquals(15, services.inventory.getMainInventorySlot(5).getCount(), "stacked by the legacy rule");
        assertEquals(0, count(carried()));
        game.drain();
        assertEquals(15, count(row("inventory", 5)));
    }

    @Test
    void rightButtonSweepDistributesOneItemPerSlot() {
        services.container = inventoryScreen();
        services.inventory.setMainInventorySlot(0, new ItemStack(BlockType.STONE, 10));
        click("main:0");

        slot("slot-press", "main:3", 1, false);
        scope.invoke("stonebreak:inventory.slot-drag", new UiValue.Obj(Map.of("slot", UiValue.of("main:4"),
            "button", UiValue.of(1))), site);
        scope.invoke("stonebreak:inventory.slot-drag", new UiValue.Obj(Map.of("slot", UiValue.of("main:4"),
            "button", UiValue.of(1))), site); // lingering adds nothing
        scope.invoke("stonebreak:inventory.slot-release", null, site);

        assertEquals(1, services.inventory.getMainInventorySlot(3).getCount());
        assertEquals(1, services.inventory.getMainInventorySlot(4).getCount());
        assertEquals(8, count(carried()), "nothing created or lost");
    }

    @Test
    void shiftClickTransfersAndCraftingCellsAreAddressable() {
        services.container = inventoryScreen();
        services.inventory.setHotbarSlot(2, new ItemStack(BlockType.STONE, 7));
        slot("slot-click", "hotbar:2", 0, true);
        assertTrue(services.inventory.getHotbarSlot(2).isEmpty(), "hotbar -> main inventory");

        InventoryCraftingManager grid = new InventoryCraftingManager(new CraftingManager(),
            InventoryLayoutCalculator.getWorkbenchCraftingGridSize());
        services.container = new WorkbenchInputManager(null, services.inventory,
            new InventorySlotManager(services.inventory, grid), grid);
        int main = firstNonEmptyMain();
        click("main:" + main);
        click("craft:8");
        assertEquals(7, grid.getCraftingInputSlots()[8].getCount(), "placed in the 3x3 grid's last cell");
    }

    private int firstNonEmptyMain() {
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            if (!services.inventory.getMainInventorySlot(i).isEmpty()) {
                return i;
            }
        }
        throw new AssertionError("no stack in the main inventory");
    }

    @Test
    void everyAddressedSlotLandsOnThatSlot() {
        // A held stack placed by address must land in exactly that slot of the legacy layout.
        services.container = inventoryScreen();
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            hold(i + 1);
            click("main:" + i);
            assertEquals(i + 1, services.inventory.getMainInventorySlot(i).getCount(), "main:" + i);
        }
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            hold(i + 1);
            click("hotbar:" + i);
            assertEquals(i + 1, services.inventory.getHotbarSlot(i).getCount(), "hotbar:" + i);
        }
    }

    private void hold(int count) {
        var drag = services.container.getDragState();
        drag.draggedItemStack = new ItemStack(BlockType.DIRT, count);
        drag.dragSource = com.stonebreak.ui.inventoryScreen.handlers.InventoryDragDropHandler.DragSource.NONE;
        drag.draggedItemOriginalSlotIndex = -1;
    }

    @Test
    void slotActionsFailClearlyWithoutAContainer() {
        assertEquals(ActionCall.State.FAILED, click("main:0").state());
        services.container = inventoryScreen();
        assertEquals(ActionCall.State.FAILED, click("main:99").state());
        assertEquals(ActionCall.State.FAILED, slot("slot-click", "main:0", 7, false).state());
        scope.invoke("stonebreak:inventory.close", null, site);
        assertEquals(1, services.closes);
    }

    @Test
    void aFurnaceRefreshQueuedBeforeCloseOrSwitchNeverLandsStale() {
        FurnaceState a = new FurnaceState(new BlockPos(1, 1, 1));
        FurnaceState b = new FurnaceState(new BlockPos(2, 2, 2));
        game.furnaceOpened(a);
        a.applyStateString("furnace:state=Lit;burn=50;burnTotal=100;cook=10"); // queues a refresh
        game.furnaceClosed();
        game.drain();
        assertEquals(UiValue.FALSE, DataPath.parse("furnace.open").evaluate(game.furnaceCell().value()));

        game.furnaceOpened(a);
        a.applyStateString("furnace:state=Lit;burn=50;burnTotal=100;cook=20");
        b.setIngredient(new ItemStack(BlockType.SAND, 3));
        game.furnaceOpened(b); // switched before the drain
        game.drain();
        assertEquals(3, count(DataPath.parse("furnace.ingredientSlot").evaluate(game.furnaceCell().value())),
            "shows the furnace that is open now");
        assertEquals(UiValue.FALSE, DataPath.parse("furnace.lit").evaluate(game.furnaceCell().value()));
    }

    @Test
    void gridsRepublishOnlyTheRowThatChangedInPlace() {
        DataCollection inv = (DataCollection) game.host().data().root("inventory").source();
        game.drain();
        List<List<ListChange>> seen = new ArrayList<>();
        Subscription sub = inv.subscribe((state, changes) -> seen.add(changes));
        game.drain();
        assertTrue(seen.isEmpty(), "an unchanged frame publishes nothing");

        services.inventory.setMainInventorySlot(7, new ItemStack(BlockType.DIRT, 1));
        game.drain();
        services.inventory.getMainInventorySlot(7).incrementCount(4); // in place, no event anywhere
        game.drain();
        assertEquals(2, seen.size());
        assertEquals(1, seen.get(1).size());
        ListChange.Updated u = assertInstanceOf(ListChange.Updated.class, seen.get(1).get(0));
        assertEquals(7, u.index());
        assertEquals(5, count(u.item()));
        sub.close();
    }

    @Test
    void hotbarSelectionMovesTheSelectedFlag() {
        game.drain();
        assertEquals(UiValue.TRUE, ((UiValue.Obj) row("hotbar", 0)).get("selected"));
        scope.invoke("stonebreak:hotbar.select", new UiValue.Obj(Map.of("index", UiValue.of(4))), site);
        assertEquals(4, services.inventory.getSelectedHotbarSlotIndex());
        game.drain();
        assertEquals(UiValue.FALSE, ((UiValue.Obj) row("hotbar", 0)).get("selected"));
        assertEquals(UiValue.TRUE, ((UiValue.Obj) row("hotbar", 4)).get("selected"));
        assertEquals(ActionCall.State.FAILED, scope.invoke("stonebreak:hotbar.select",
            new UiValue.Obj(Map.of("index", UiValue.of(9))), site).state());
    }

    @Test
    void leavingTheWorldResetsPerWorldData() {
        services.inventory.setHotbarSlot(0, new ItemStack(BlockType.STONE, 3));
        services.vitals = new Vitals(10, 20, 5, 10, 1, 2, 3, 4, 5, false, true);
        game.furnaceOpened(new FurnaceState(new BlockPos(0, 0, 0)));
        game.drain();
        assertEquals(UiValue.of(10), DataPath.parse("vitals.health").evaluate(
            game.host().data().root("vitals").source().state().valueOrNull()));

        services.inventory = null;
        services.vitals = Vitals.NONE;
        game.sessionChanged(MultiplayerSession.Mode.MENU);
        game.drain();
        assertEquals(UiValue.FALSE, DataPath.parse("furnace.open").evaluate(game.furnaceCell().value()));
        assertTrue(((DataCollection) game.host().data().root("hotbar").source()).items().isEmpty());
        assertEquals(UiValue.FALSE, DataPath.parse("vitals.present").evaluate(
            game.host().data().root("vitals").source().state().valueOrNull()));
    }

    @Test
    void theDeclaredProfileNeedsNoGame() {
        UiHostProfile p = GameUiHost.declaredProfile();
        for (String c : List.of("stonebreak:session", "stonebreak:furnace", "stonebreak:settings",
            "stonebreak:screen.pause", "stonebreak:network.resync", "stonebreak:inventory", "stonebreak:hotbar",
            "stonebreak:player.vitals")) {
            assertTrue(p.hostApis().containsKey(c), c);
        }
        assertEquals(2, p.hostApis().get("stonebreak:furnace"));
        assertEquals(2, p.hostApis().get("stonebreak:settings"));
        assertFalse(p.providers().isEmpty(), "draw providers are declared");
        assertTrue(p.providers().containsKey("stonebreak:item-icon"));
    }
}
