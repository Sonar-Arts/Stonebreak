package com.stonebreak.ui.runtime.contracts;

import com.openmason.engine.format.omui.UiValue;
import com.stonebreak.config.Settings;
import com.stonebreak.ui.fidelity.WorldSelectFixtures;
import com.stonebreak.ui.worldSelect.SectionBounds;
import com.stonebreak.ui.worldSelect.WorldSelectLayout;
import com.stonebreak.ui.worldSelect.WorldSelectScreen;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What {@code stonebreak:screen.world-select} publishes and which actions it refuses (#299). */
class WorldSelectContractsTest {

    private static final int[] WINDOW = {1920, 1080};
    private float scale;

    @BeforeEach
    void pinScale() {
        scale = Settings.getInstance().getUiScale();
        Settings.getInstance().setUiScale(1f);
    }

    @AfterEach
    void restoreScale() {
        Settings.getInstance().setUiScale(scale);
    }

    private static UiValue.Obj obj(UiValue v) {
        return (UiValue.Obj) v;
    }

    private static double num(UiValue.Obj o, String k) {
        return ((UiValue.Num) o.get(k)).value();
    }

    @Test
    void theVisiblePageAndTheThumbFollowTheLegacyMaths() {
        WorldSelectScreen s = WorldSelectFixtures.screen(null, "many");
        UiValue.Obj v = WorldSelectContracts.value(s, WINDOW);
        UiValue.Arr rows = (UiValue.Arr) v.get("rows");
        assertEquals(8, rows.items().size());
        assertEquals("World 04", ((UiValue.Str) obj(rows.items().getFirst()).get("name")).value());
        assertEquals(UiValue.of(true), obj(rows.items().get(7)).get("selected"));
        WorldSelectLayout layout = WorldSelectLayout.compute(WINDOW[0], WINDOW[1]);
        float thumbH = layout.listHeight * 8 / 12f;
        assertEquals(thumbH, num(v, "thumbHeight"), 1e-4);
        assertEquals((layout.listHeight - thumbH) * 3 / 4, num(v, "thumbOffset"), 1e-4);
        assertEquals(UiValue.of(true), v.get("scrollbar"));
    }

    @Test
    void theCardSitsWhereTheLegacyLayoutPutsIt() {
        WorldSelectScreen s = WorldSelectFixtures.screen(null, "card-running");
        UiValue.Obj card = obj(WorldSelectContracts.value(s, WINDOW).get("card"));
        SectionBounds b = WorldSelectLayout.compute(WINDOW[0], WINDOW[1]).cardBounds(0);
        assertEquals(UiValue.of(true), card.get("shown"));
        assertEquals(b.x, num(card, "x"), 0);
        assertEquals(b.y, num(card, "y"), 0);
        assertEquals("Backing Up", ((UiValue.Str) card.get("backupLabel")).value());
        assertEquals(UiValue.of(false), card.get("backupEnabled"));
        assertEquals(0.4, num(card, "progress"), 1e-6);
    }

    @Test
    void actionsFollowTheLegacyRules() {
        WorldSelectScreen s = WorldSelectFixtures.screen(null, "worlds");
        assertNull(WorldSelectContracts.perform(s, "select", 2));
        assertEquals(2, s.getStateManager().getSelectedIndex());
        assertNotNull(WorldSelectContracts.perform(s, "select", 9), "no such world");
        assertNotNull(WorldSelectContracts.perform(s, "open-folder", 0), "no card open");
        assertNotNull(WorldSelectContracts.perform(s, "confirm-delete", 0), "nothing to confirm");
        assertNull(WorldSelectContracts.perform(s, "delete", 0));
        assertTrue(s.getStateManager().isShowDeleteDialog());
        assertNotNull(WorldSelectContracts.perform(s, "select", 0), "the confirmation is modal");
        assertNull(WorldSelectContracts.perform(s, "cancel-delete", 0));
        assertFalse(s.getStateManager().isShowDeleteDialog());

        WorldSelectScreen empty = WorldSelectFixtures.screen(null, "empty");
        assertNotNull(WorldSelectContracts.perform(empty, "play", 0), "Play is disabled without a world");
        assertNotNull(WorldSelectContracts.perform(empty, "delete", 0));
        assertNotNull(WorldSelectContracts.perform(null, "back", 0), "no screen showing");
    }

    @Test
    void theLegacyWheelScrollsUpOnWheelUp() {
        WorldSelectScreen s = WorldSelectFixtures.screen(null, "many");
        assertEquals(3, s.getScrollOffset());
        s.handleMouseWheel(1.0); // GLFW: positive = wheel up
        assertEquals(2, s.getScrollOffset());
        s.handleMouseWheel(-1.0);
        s.handleMouseWheel(-1.0);
        assertEquals(4, s.getScrollOffset());
    }
}
