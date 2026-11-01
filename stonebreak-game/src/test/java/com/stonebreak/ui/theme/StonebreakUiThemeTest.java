package com.stonebreak.ui.theme;

import com.openmason.engine.format.omui.UiVocabulary;
import com.openmason.engine.ui.runtime.widget.WidgetRegistry;
import com.openmason.engine.ui.theme.UiTheme;
import com.openmason.engine.ui.theme.UiThemeWidget;
import com.openmason.engine.ui.theme.UiThemes;
import com.stonebreak.ui.runtime.GameUiHost;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stonebreak's UI theme (#384): found as a module service, every surface and symbol the shipped
 * documents name by its pre-#384 bare name still resolves, and the slot widget is the namespaced
 * {@code stonebreak:ItemSlot} under its legacy {@code ItemSlot} alias.
 */
class StonebreakUiThemeTest {

    /** Every bare surface name a document could use before #384 moved them out of the engine. */
    private static final List<String> LEGACY_SURFACES = List.of("container", "hud", "folder-tab",
        "folder-tab-active", "input-well", "search-field", "search-field-active", "hotbar", "panel-deep", "tab",
        "tab-hover", "tab-active", "tab-dim", "tab-disabled", "mini-button", "mini-button-hover",
        "mini-button-disabled", "slot", "dirt", "tip-strip", "filter-bar");
    private static final List<String> LEGACY_SYMBOLS = List.of("crafting-arrow", "smiley", "heart", "nav-left",
        "nav-right");

    @Test
    void theInstalledThemeIsTheGamesAndEveryLegacyNameStillResolves() {
        UiTheme t = UiThemes.installed();
        for (String name : LEGACY_SURFACES) {
            assertNotNull(t.surface("stonebreak:" + name), name);
            assertSame(t.surface("stonebreak:" + name), t.surface(name), "alias " + name);
            assertTrue(UiThemes.neutral().surface(name) == null, "the engine does not own " + name);
        }
        for (String name : LEGACY_SYMBOLS) {
            assertNotNull(t.symbol("stonebreak:" + name), name);
            assertSame(t.symbol("stonebreak:" + name), t.symbol(name), "alias " + name);
        }
        for (String neutral : List.of("panel", "button", "button-hover", "button-disabled", "inset")) {
            assertSame(UiThemes.neutral().surface(neutral), t.surface(neutral), "neutral " + neutral + " kept");
        }
    }

    @Test
    void theItemSlotIsANamespacedGameWidgetReadThroughItsLegacyName() {
        assertEquals(StonebreakWidgets.ITEM_SLOT, UiVocabulary.canonicalType("ItemSlot"));
        UiThemeWidget w = UiThemes.installed().widget("ItemSlot");
        assertNotNull(w);
        assertSame(w, UiThemes.installed().widget(StonebreakWidgets.ITEM_SLOT));
        assertTrue(w.drawsProvider());
        assertEquals("cell", w.accessibleRole());
        assertSame(StonebreakWidgets.ITEM_SLOT_DESCRIPTOR, WidgetRegistry.installed().get("ItemSlot"));
    }

    @Test
    void theHostOffersTheNamespacedSlotToDocumentsThatDeclareIt() {
        assertEquals(1, GameUiHost.declaredProfile().providers().get(StonebreakWidgets.ITEM_SLOT));
    }
}
