package com.stonebreak.ui.theme;

import com.openmason.engine.format.omui.UiVocabularyProvider;
import com.openmason.engine.ui.masonry.MPainter;
import com.openmason.engine.ui.masonry.MStyle;
import com.openmason.engine.ui.theme.SurfacePainter;
import com.openmason.engine.ui.theme.SymbolPainter;
import com.openmason.engine.ui.theme.UiTheme;
import com.openmason.engine.ui.theme.UiThemeProvider;

import java.util.Map;

/**
 * Stonebreak's UI theme (#384): the stone screens' {@code stonebreak:} surfaces and symbols and the
 * {@code stonebreak:ItemSlot} widget, each with the bare name documents used before the engine
 * stopped naming them (so every shipped document paints byte-for-byte as before). Found by the
 * engine with {@link java.util.ServiceLoader} (this module {@code provides} it); Open Mason's
 * Stonebreak plugin registers it explicitly for its preview.
 *
 * <p>Surface radii are device pixels, as the legacy painters drew them.
 */
public final class StonebreakUiTheme implements UiThemeProvider, UiVocabularyProvider {

    public static final String NAMESPACE = "stonebreak";

    @Override
    public Map<String, String> widgetAliases() {
        return Map.of(StonebreakWidgets.ITEM_SLOT_LEGACY, StonebreakWidgets.ITEM_SLOT);
    }

    @Override
    public void contribute(UiTheme.Builder t) {
        surface(t, "container", (c, r, s) -> StonePainter.containerPanel(c, r.x(), r.y(), r.width(), r.height()));
        surface(t, "hud", (c, r, s) -> MPainter.hudFrame(c, r.x(), r.y(), r.width(), r.height()));
        // the chat's folder tabs and input well (#301), at the legacy chat's device-px radii
        surface(t, "folder-tab", (c, r, s) -> StonePainter.folderTab(c, r.x(), r.y(), r.width(), r.height(), false));
        surface(t, "folder-tab-active", (c, r, s) ->
            StonePainter.folderTab(c, r.x(), r.y(), r.width(), r.height(), true));
        surface(t, "input-well", (c, r, s) -> StonePainter.inputWell(c, r.x(), r.y(), r.width(), r.height()));
        surface(t, "search-field", (c, r, s) -> MPainter.searchWell(c, r.x(), r.y(), r.width(), r.height(), false));
        surface(t, "search-field-active", (c, r, s) ->
            MPainter.searchWell(c, r.x(), r.y(), r.width(), r.height(), true));
        // the gameplay hotbar's frame (#300): dark translucent stone, radius 8
        surface(t, "hotbar", (c, r, s) -> MPainter.stoneSurface(c, r.x(), r.y(), r.width(), r.height(), 8f,
            0xC8282828, 0xFF505050, 0x22FFFFFF, 0x44000000, 0x78000000,
            MStyle.PANEL_NOISE_DARK, MStyle.PANEL_NOISE_LIGHT));
        // a stone panel on the deep fill: the hotbar's item-name tooltip (#300)
        surface(t, "panel-deep", (c, r, s) -> MPainter.stoneSurface(c, r.x(), r.y(), r.width(), r.height(),
            MStyle.PANEL_RADIUS, MStyle.PANEL_FILL_DEEP, MStyle.PANEL_BORDER, MStyle.PANEL_HIGHLIGHT,
            MStyle.PANEL_SHADOW, MStyle.PANEL_DROP_SHADOW, MStyle.PANEL_NOISE_DARK, MStyle.PANEL_NOISE_LIGHT));
        // the inventory/character sheet tab strip: a button without the drop shadow (#300); also the
        // character screens' list rows and row buttons: hovered-unselected rows dim, disabled (#301)
        surface(t, "tab", tab(MStyle.BUTTON_FILL));
        surface(t, "tab-hover", tab(MStyle.BUTTON_FILL_HI));
        surface(t, "tab-active", tab(0xFF7A7A7A));
        surface(t, "tab-dim", tab(0xFF3C3C3C));
        surface(t, "tab-disabled", tab(MStyle.BUTTON_FILL_DIS));
        // the ability score tiles' [-]/[+]: radius 2, no drop shadow (#301)
        surface(t, "mini-button", miniButton(MStyle.BUTTON_FILL));
        surface(t, "mini-button-hover", miniButton(MStyle.BUTTON_FILL_HI));
        surface(t, "mini-button-disabled", miniButton(MStyle.BUTTON_FILL_DIS));
        // the character sheet's earthy ability pane, its skill hint strip and the feat filter bar (#301)
        surface(t, "dirt", (c, r, s) -> MPainter.stoneSurface(c, r.x(), r.y(), r.width(), r.height(),
            MStyle.PANEL_RADIUS, 0xBF5C4033, 0xFF2A1A0A, MStyle.PANEL_HIGHLIGHT, MStyle.PANEL_SHADOW, 0,
            0x38000000, 0x1A7A5A2A));
        surface(t, "tip-strip", (c, r, s) -> MPainter.stoneSurface(c, r.x(), r.y(), r.width(), r.height(), 3f,
            0xCC1A1A1A, 0xFF141414, 0x1AFFFFFF, 0x1A000000, 0, 0, 0));
        surface(t, "filter-bar", (c, r, s) -> MPainter.stoneSurface(c, r.x(), r.y(), r.width(), r.height(),
            MStyle.PANEL_RADIUS, 0xBF3C3C3C, MStyle.PANEL_BORDER, MStyle.PANEL_HIGHLIGHT, MStyle.PANEL_SHADOW, 0,
            MStyle.PANEL_NOISE_DARK, MStyle.PANEL_NOISE_LIGHT));
        // the item slot's recessed frame (MItemSlot) on any element: the ability score tiles (#301)
        surface(t, "slot", (c, r, s) -> MPainter.stoneSurface(c, r.x(), r.y(), r.width(), r.height(), 3f,
            0xFF252525, 0xFF151515, 0x55000000, 0x1AFFFFFF, 0, MStyle.PANEL_NOISE_DARK, MStyle.PANEL_NOISE_LIGHT));

        symbol(t, "crafting-arrow", StoneSymbols.CRAFTING_ARROW);
        symbol(t, "smiley", StoneSymbols.SMILEY);
        symbol(t, "heart", StoneSymbols.HEART);
        symbol(t, "nav-left", StoneSymbols.NAV_LEFT);
        symbol(t, "nav-right", StoneSymbols.NAV_RIGHT);

        t.widget(StonebreakWidgets.itemSlot());
    }

    /** {@code stonebreak:<name>} plus the bare legacy alias {@code <name>}. */
    private static void surface(UiTheme.Builder t, String name, SurfacePainter painter) {
        t.surface(NAMESPACE + ":" + name, painter).surfaceAlias(name, NAMESPACE + ":" + name);
    }

    private static void symbol(UiTheme.Builder t, String name, SymbolPainter painter) {
        t.symbol(NAMESPACE + ":" + name, painter).symbolAlias(name, NAMESPACE + ":" + name);
    }

    private static SurfacePainter tab(int fill) {
        return (c, r, s) -> MPainter.stoneSurface(c, r.x(), r.y(), r.width(), r.height(), MStyle.BUTTON_RADIUS,
            fill, MStyle.BUTTON_BORDER, MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, 0,
            MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);
    }

    private static SurfacePainter miniButton(int fill) {
        return (c, r, s) -> MPainter.stoneSurface(c, r.x(), r.y(), r.width(), r.height(), 2f,
            fill, MStyle.BUTTON_BORDER, MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, 0,
            MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);
    }
}
