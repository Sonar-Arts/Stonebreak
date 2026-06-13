package com.openmason.main.systems.menus.mainHub.components;

import com.openmason.main.systems.LogoManager;
import com.openmason.main.systems.menus.mainHub.model.NavigationItem;
import com.openmason.main.systems.menus.mainHub.state.HubState;
import com.openmason.main.systems.mortar.core.MortarFrameResult;
import com.openmason.main.systems.mortar.core.MortarRegion;
import com.openmason.main.systems.mortar.paint.MortarPainter;
import com.openmason.main.systems.mortar.parts.MortarButton;
import com.openmason.main.systems.mortar.parts.MortarNavItem;
import com.openmason.main.systems.mortar.parts.MortarSectionLabel;
import com.openmason.main.systems.mortar.parts.MortarSeparator;
import com.openmason.main.systems.skija.SkijaFontStore.Weight;
import com.openmason.main.systems.themes.core.ThemeManager;
import imgui.ImGui;

/**
 * Left sidebar: app identity (logo + title) over a Skija-painted navigation
 * region (Home, Learn) with Preferences pinned to the bottom. The logo stays a
 * GL-textured ImGui image; everything below is one {@link MortarRegion} so the
 * accent bar, hover and selection animate as one crisp surface.
 */
public class HubSidebarNav {

    private static final float LOGO_SIZE = 84.0f;
    private static final float PAD = 8.0f;
    private static final float NAV_ITEM_HEIGHT = 38.0f;
    private static final float NAV_ITEM_GAP = 4.0f;

    private final HubState hubState;
    private final LogoManager logoManager;
    private final MortarRegion region = new MortarRegion();

    private Runnable onPreferencesClicked;

    public HubSidebarNav(ThemeManager themeManager, HubState hubState, LogoManager logoManager) {
        this.hubState = hubState;
        this.logoManager = logoManager;
        hubState.setCurrentView(NavigationItem.ViewType.HOME);
    }

    public void setOnPreferencesClicked(Runnable callback) {
        this.onPreferencesClicked = callback;
    }

    public void render() {
        // Logo — GL texture, centered, above the Skija region.
        ImGui.dummy(0, 6f);
        float availW = ImGui.getContentRegionAvailX();
        ImGui.setCursorPosX(ImGui.getCursorPosX() + (availW - LOGO_SIZE) / 2f);
        logoManager.renderLogo(LOGO_SIZE);
        ImGui.dummy(0, 8f);

        float width = ImGui.getContentRegionAvailX();
        float height = ImGui.getContentRegionAvailY();
        if (width < 1f || height < 1f) {
            return;
        }

        float x = PAD;
        float w = width - PAD * 2f;

        region.begin(width, height);

        // Identity text.
        region.add("title", x, 4f, w, 22f, (g, px, py, pw, ph, st) ->
                g.text("Project Hub", px + pw / 2f, py + ph / 2f,
                        MortarPainter.Align.CENTER, Weight.MEDIUM, 16f, g.theme().text));
        region.add("subtitle", x, 28f, w, 16f, (g, px, py, pw, ph, st) ->
                g.text("Open Mason", px + pw / 2f, py + ph / 2f,
                        MortarPainter.Align.CENTER, Weight.REGULAR, 11f, g.theme().textDim));

        region.add("sep", x, 52f, w, 8f, new MortarSeparator(true));

        // Primary action — opens the New Project preview (like selecting a card).
        region.add("new", x, 66f, w, 36f,
                new MortarButton("+  New Project", MortarButton.Variant.PRIMARY));

        region.add("navlabel", x + 6f, 112f, w - 6f, 16f, new MortarSectionLabel("Navigation"));

        float navY = 134f;
        boolean homeSel = hubState.getCurrentView() == NavigationItem.ViewType.HOME;
        boolean learnSel = hubState.getCurrentView() == NavigationItem.ViewType.LEARN;
        region.add("nav.home", x, navY, w, NAV_ITEM_HEIGHT, homeSel, new MortarNavItem("Home"));
        region.add("nav.learn", x, navY + NAV_ITEM_HEIGHT + NAV_ITEM_GAP, w, NAV_ITEM_HEIGHT,
                learnSel, new MortarNavItem("Learn"));

        // Preferences pinned to the bottom.
        float prefsY = height - NAV_ITEM_HEIGHT - PAD;
        region.add("nav.prefs", x, prefsY, w, NAV_ITEM_HEIGHT, false, new MortarNavItem("Preferences"));

        MortarFrameResult input = region.render();
        if (input.isClicked("new")) {
            hubState.selectNewProject();
        } else if (input.isClicked("nav.home")) {
            switchView(NavigationItem.ViewType.HOME);
        } else if (input.isClicked("nav.learn")) {
            switchView(NavigationItem.ViewType.LEARN);
        } else if (input.isClicked("nav.prefs") && onPreferencesClicked != null) {
            onPreferencesClicked.run();
        }
    }

    private void switchView(NavigationItem.ViewType view) {
        hubState.setCurrentView(view);
        hubState.clearSelection();
    }

    public void update(float deltaTime) {
        region.update(deltaTime);
    }

    public void dispose() {
        region.close();
    }
}
