package com.stonebreak.ui.settingsMenu;

import com.stonebreak.config.Settings;
import com.stonebreak.core.GameState;
import com.openmason.engine.ui.masonry.MButton;
import com.openmason.engine.ui.masonry.MDropdown;
import com.openmason.engine.ui.masonry.MSlider;
import com.openmason.engine.ui.masonry.MWidget;
import com.stonebreak.ui.settingsMenu.config.CategoryState;
import com.stonebreak.ui.settingsMenu.components.ScrollableSettingsContainer;
import com.stonebreak.ui.settingsMenu.handlers.ActionHandler;
import com.stonebreak.ui.settingsMenu.handlers.InputHandler;
import com.stonebreak.ui.settingsMenu.managers.SettingsManager;
import com.stonebreak.ui.settingsMenu.managers.StateManager;

/**
 * Facade coordinating the settings screen: the MasonryUI widgets' state, values and rules. The
 * shipped document {@code ui/documents/settings.sbui} presents it (#299).
 */
public final class SettingsMenu {

    private final StateManager stateManager;
    private final SettingsManager settingsManager;
    private final ActionHandler actionHandler;
    private final InputHandler inputHandler;
    private final ScrollableSettingsContainer scrollContainer;
    private final SettingsLayout layout;

    public SettingsMenu() {
        Settings settings = Settings.getInstance();

        this.stateManager = new StateManager(settings);
        this.settingsManager = new SettingsManager(settings);
        this.actionHandler = new ActionHandler(stateManager, settingsManager, settings);
        this.inputHandler = new InputHandler(stateManager, settings, actionHandler);

        this.scrollContainer = new ScrollableSettingsContainer(stateManager);
        this.layout = new SettingsLayout(stateManager, scrollContainer);

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
                actionHandler::togglePauseWorld,
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

    // ─────────────────────────────────────────────── Public API

    public void setPreviousState(GameState state) { stateManager.setPreviousState(state); }

    // ─────────────────────────────────────────────── Document API (#299)
    // The shipped document (ui/documents/settings.sbui) reaches the menu through these, by the
    // menu's own rules; the menu stays the controller of every widget and value.

    /** The per-frame layout (scroll easing, labels, positions) while the document shows. */
    public SettingsLayout.Frame layout(int windowWidth, int windowHeight) {
        return layout.layout(windowWidth, windowHeight);
    }

    /** Per frame: auto-reverts the UI scale when its confirmation times out. */
    public void tick() {
        actionHandler.tickUiScaleConfirmation();
    }

    /**
     * A left press on part {@code target} ({@code category<i>}, {@code row<i>}, {@code item<k>} of
     * the open dropdown, {@code apply}, {@code back}, {@code keep}, {@code revert}, or {@code none})
     * with {@code fraction} = where along a slider's track it landed. The precedence: the
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
        return scrollContainer.handleMouseWheel(x, y, delta);
    }

    /** One key press ({@link InputHandler#key}). */
    public void key(int key, boolean shift) {
        inputHandler.key(key, shift);
    }

    public StateManager getStateManager() { return stateManager; }
    public ScrollableSettingsContainer getScrollContainer() { return scrollContainer; }
}
