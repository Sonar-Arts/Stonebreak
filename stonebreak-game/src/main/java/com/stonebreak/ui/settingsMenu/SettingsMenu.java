package com.stonebreak.ui.settingsMenu;

import com.stonebreak.config.Settings;
import com.stonebreak.core.GameState;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.openmason.engine.ui.masonry.MButton;
import com.openmason.engine.ui.masonry.MDropdown;
import com.openmason.engine.ui.masonry.MSlider;
import com.openmason.engine.ui.masonry.MWidget;
import com.openmason.engine.ui.masonry.MasonryUI;
import com.stonebreak.ui.settingsMenu.config.CategoryState;
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
                actionHandler::onMusicVolumeChange,
                actionHandler::toggleMusic,
                actionHandler::onCrosshairSizeChange,
                actionHandler::togglePlayerNameTags,
                actionHandler::toggleLeafTransparency,
                actionHandler::toggleWaterShader,
                actionHandler::toggleClouds,
                actionHandler::toggleGodRays,
                actionHandler::toggleShadows,
                actionHandler::onShadowQualityChange,
                actionHandler::onShadowDistanceChange,
                actionHandler::toggleSmoothLighting,
                actionHandler::onRenderDistanceChange,
                actionHandler::onLodDistanceChange,
                actionHandler::toggleLodEnabled,
                actionHandler::onLodQualityChange,
                actionHandler::toggleVsync,
                actionHandler::onMaxFpsChange,
                actionHandler::onUiScaleChange,
                actionHandler::confirmUiScale,
                actionHandler::revertUiScale
        );
    }

    // ─────────────────────────────────────────────── Input

    public void handleInput(long window) {
        // Per-frame: auto-revert the UI scale if the confirmation popup times out.
        actionHandler.tickUiScaleConfirmation();
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

    // ─────────────────────────────────────────────── Document API (#299)
    // The shipped document (ui/documents/settings.sbui) reaches the menu through these, by the
    // legacy handlers' rules; the menu stays the controller of every widget and value.

    /** The per-frame layout (scroll easing, labels, positions) while the document paints instead. */
    public SkijaSettingsRenderer.Frame layout(int windowWidth, int windowHeight) {
        return renderer.layout(windowWidth, windowHeight);
    }

    /** Per frame: auto-reverts the UI scale when its confirmation times out. */
    public void tick() {
        actionHandler.tickUiScaleConfirmation();
    }

    /**
     * A left press on part {@code target} ({@code category<i>}, {@code row<i>}, {@code item<k>} of
     * the open dropdown, {@code apply}, {@code back}, {@code keep}, {@code revert}, or {@code none})
     * with {@code fraction} = where along a slider's track it landed. The legacy precedence: the
     * confirmation is modal, an open dropdown takes the press (an item selects, its header closes
     * it, anything else just closes it), then categories, rows, Apply and Back.
     *
     * @return true when the press did something
     */
    public boolean press(String target, float fraction) {
        if (stateManager.isUiScaleConfirmActive()) {
            if (target.equals("keep")) { stateManager.getKeepUiScaleButton().click(); return true; }
            if (target.equals("revert")) { stateManager.getRevertUiScaleButton().click(); return true; }
            return false;
        }
        CategoryState.SettingType[] settings = stateManager.getSelectedCategory().getSettings();
        MDropdown open = stateManager.openDropdown();
        if (open != null) {
            if (target.startsWith("item")) {
                open.selectItem(Integer.parseInt(target.substring(4)));
            } else if (target.startsWith("row") && stateManager.widget(settings[row(target)]) == open) {
                open.toggle();
                open.click();
            } else {
                open.close();
            }
            return true;
        }
        if (target.startsWith("category")) {
            stateManager.getCategoryButtons().get(Integer.parseInt(target.substring(8))).click();
            return true;
        }
        if (target.startsWith("row")) {
            int i = row(target);
            if (i < 0 || i >= settings.length) return false;
            MWidget w = stateManager.widget(settings[i]);
            if (w instanceof MSlider slider) {
                slider.beginDragAt(fraction);
            } else if (w instanceof MDropdown d) {
                d.toggle();
                d.click();
            } else if (w instanceof MButton b) {
                b.click();
            } else {
                return false;
            }
            stateManager.setSelectedSettingInCategory(i);
            return true;
        }
        if (target.equals("apply")) {
            stateManager.getApplyButton().click();
            stateManager.setSelectedSettingInCategory(settings.length);
            return true;
        }
        if (target.equals("back")) {
            stateManager.getBackButton().click();
            stateManager.setSelectedSettingInCategory(settings.length + 1);
            return true;
        }
        return false;
    }

    private static int row(String target) {
        return Integer.parseInt(target.substring(3));
    }

    /** The pointer moved with the button held: a dragged slider follows ({@code fraction} of its track). */
    public void drag(float fraction) {
        for (MSlider slider : stateManager.sliders()) {
            slider.dragTo(fraction);
        }
    }

    /** True while a slider is being dragged. */
    public boolean dragging() {
        for (MSlider slider : stateManager.sliders()) {
            if (slider.isDragging()) return true;
        }
        return false;
    }

    /** A press on the scrollbar at device px ({@code x}, {@code y}); true when it grabbed it. */
    public boolean scrollbarPress(float x, float y) {
        if (stateManager.isUiScaleConfirmActive() || stateManager.openDropdown() != null) return false;
        return scrollContainer.handleMousePress(x, y);
    }

    /** The pointer moved with the scrollbar grabbed (device px). */
    public void scrollbarDrag(float y) {
        scrollContainer.handleMouseDrag(y);
    }

    /** The button went up: drags end. */
    public void release() {
        scrollContainer.handleMouseRelease();
        for (MSlider slider : stateManager.sliders()) {
            slider.stopDragging();
        }
    }

    /** A wheel tick over the viewport at device px; true when it scrolled. */
    public boolean wheel(float x, float y, float delta) {
        return mouseHandler.handleMouseWheel(x, y, delta);
    }

    /** One key press ({@link InputHandler#key}). */
    public void key(int key, boolean shift) {
        inputHandler.key(key, shift);
    }

    public StateManager getStateManager() { return stateManager; }
    public ScrollableSettingsContainer getScrollContainer() { return scrollContainer; }
    public SkijaSettingsRenderer renderer() { return renderer; }

    public void dispose() {
        renderer.dispose();
        ui.dispose();
    }
}
