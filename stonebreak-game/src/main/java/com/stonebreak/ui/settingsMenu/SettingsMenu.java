package com.stonebreak.ui.settingsMenu;

import com.stonebreak.config.Settings;
import com.stonebreak.core.GameState;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.settingsMenu.components.ScrollableSettingsContainer;
import com.stonebreak.ui.settingsMenu.handlers.ActionHandler;
import com.stonebreak.ui.settingsMenu.handlers.InputHandler;
import com.stonebreak.ui.settingsMenu.handlers.MouseHandler;
import com.stonebreak.ui.settingsMenu.managers.SettingsManager;
import com.stonebreak.ui.settingsMenu.managers.StateManager;
import com.stonebreak.ui.settingsMenu.renderers.SkijaSettingsRenderer;

/**
 * Facade coordinating the MasonryUI-backed settings screen.
 *
 * Constructed with a {@link SkijaUIBackend} — the same backend used by the
 * main menu and world select. Keeps every screen on a single GL client so
 * the dirt-background corruption that arose from NanoVG↔Skija cohabitation
 * cannot recur here.
 */
public final class SettingsMenu {

    private final StateManager stateManager;
    private final SettingsManager settingsManager;
    private final ActionHandler actionHandler;
    private final InputHandler inputHandler;
    private final MouseHandler mouseHandler;
    private final ScrollableSettingsContainer scrollContainer;
    private final MasonryUI ui;
    private final SkijaSettingsRenderer renderer;

    public SettingsMenu(SkijaUIBackend skijaBackend) {
        Settings settings = Settings.getInstance();

        this.stateManager = new StateManager(settings);
        this.settingsManager = new SettingsManager(settings);
        this.actionHandler = new ActionHandler(stateManager, settingsManager, settings);
        this.inputHandler = new InputHandler(stateManager, settings, actionHandler);
        this.mouseHandler = new MouseHandler(stateManager);

        this.scrollContainer = new ScrollableSettingsContainer(stateManager);
        this.ui = new MasonryUI(skijaBackend);
        this.renderer = new SkijaSettingsRenderer(ui, stateManager, scrollContainer);

        mouseHandler.setScrollableContainer(scrollContainer);

        stateManager.setCallbacks(
                actionHandler::applySettings,
                actionHandler::goBack,
                actionHandler::onResolutionChange,
                actionHandler::onArmModelChange,
                actionHandler::onCrosshairStyleChange,
                actionHandler::onVolumeChange,
                actionHandler::onCrosshairSizeChange,
                actionHandler::toggleLeafTransparency,
                actionHandler::toggleWaterShader
        );
    }

    // ─────────────────────────────────────────────── Input

    public void handleInput(long window) {
        inputHandler.handleInput(window);
    }

    public void handleMouseMove(double mouseX, double mouseY, int windowWidth, int windowHeight) {
        mouseHandler.handleMouseMove(mouseX, mouseY, windowWidth, windowHeight);
    }

    public void handleMouseClick(double mouseX, double mouseY, int windowWidth, int windowHeight, int button, int action) {
        mouseHandler.handleMouseClick(mouseX, mouseY, windowWidth, windowHeight, button, action);
    }

    public boolean handleMouseWheel(double mouseX, double mouseY, double scrollDelta) {
        return mouseHandler.handleMouseWheel(mouseX, mouseY, scrollDelta);
    }

    // ─────────────────────────────────────────────── Public API

    public void setPreviousState(GameState state) { stateManager.setPreviousState(state); }
    public int getSelectedButton() { return stateManager.getSelectedButton(); }

    public void render(int windowWidth, int windowHeight) {
        renderer.render(windowWidth, windowHeight);
    }

    public void dispose() {
        renderer.dispose();
        ui.dispose();
    }
}
