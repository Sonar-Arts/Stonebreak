package com.stonebreak.ui.settingsMenu;

import com.openmason.engine.ui.masonry.MCategoryButton;
import com.openmason.engine.ui.masonry.MWidget;
import com.stonebreak.ui.settingsMenu.components.ScrollableSettingsContainer;
import com.stonebreak.ui.settingsMenu.config.CategoryState;
import com.stonebreak.ui.settingsMenu.config.SettingsConfig;
import com.stonebreak.ui.settingsMenu.managers.StateManager;

/**
 * The settings screen's per-frame layout: advances the active category's scroll easing, refreshes the
 * labels and places every widget (categories left, the scrollable settings right, Apply and Back, the
 * UI-scale confirmation). The widgets' positions are the source of truth the UI host publishes to the
 * shipped document and the document's presses are resolved against (#299).
 */
public final class SettingsLayout {

    /** Where the frame's panel and title sit (device px), from {@link #layout}. */
    public record Frame(float centerX, float centerY, float panelX, float panelY, float panelWidth,
                        float panelHeight, float titleY) {
    }

    private final StateManager state;
    private final ScrollableSettingsContainer scrollContainer;

    public SettingsLayout(StateManager state, ScrollableSettingsContainer scrollContainer) {
        this.state = state;
        this.scrollContainer = scrollContainer;
    }

    /**
     * The per-frame layout: advances the active category's scroll easing (one 1/60 s step a frame,
     * as before), refreshes the labels and places every widget for this window.
     */
    public Frame layout(int windowWidth, int windowHeight) {
        float s = com.stonebreak.config.Settings.getInstance().getUiScale();
        float centerX = windowWidth / 2f;
        float centerY = windowHeight / 2f;

        float panelWidth = Math.min(700f * s, windowWidth * 0.92f);
        float panelHeight = Math.min(550f * s, windowHeight * 0.92f);
        float panelX = centerX - panelWidth / 2f;
        float panelY = centerY - panelHeight / 2f;
        float titleY = panelY + Math.max(40f * s, panelHeight * 0.08f);

        if (state.getCurrentScrollMath() != null) {
            state.getCurrentScrollMath().update(1f / 60f);
        }
        state.refreshLabels();

        positionCategoryButtons(centerX, centerY);
        state.updateButtonSelectionStates();

        scrollContainer.updateBounds(centerX, centerY, panelHeight);
        positionSettings();
        positionActionButtons();
        if (state.isUiScaleConfirmActive()) {
            positionConfirmation(windowWidth, windowHeight, s);
        }
        return new Frame(centerX, centerY, panelX, panelY, panelWidth, panelHeight, titleY);
    }

    /** The modal "keep this UI scale?" popup's Keep and Revert buttons. */
    private void positionConfirmation(int w, int h, float s) {
        float dialogW = Math.min(460f * s, w * 0.9f);
        float dialogH = Math.min(210f * s, h * 0.9f);
        float dy = h / 2f - dialogH / 2f;
        float cx = w / 2f;
        float bh  = SettingsConfig.getScaledButtonHeight();
        float gap = 16f * s;
        // Keep both buttons inside the dialog even when the base button width is wide.
        float bw  = Math.min(SettingsConfig.getScaledButtonWidth(), (dialogW - gap - 24f * s) / 2f);
        float btnY = dy + dialogH - bh - 22f * s;

        state.getKeepUiScaleButton().size(bw, bh).position(cx - gap / 2f - bw, btnY);
        state.getRevertUiScaleButton().size(bw, bh).position(cx + gap / 2f, btnY);
    }

    private void positionCategoryButtons(float centerX, float centerY) {
        float categoryX = centerX + SettingsConfig.getScaledCategoryPanelXOffset();
        float categoryY = centerY + SettingsConfig.getScaledCategoryButtonsStartY()
                - 20f * com.stonebreak.config.Settings.getInstance().getUiScale();
        var buttons = state.getCategoryButtons();
        for (int i = 0; i < buttons.size(); i++) {
            MCategoryButton<CategoryState> b = buttons.get(i);
            b.position(categoryX, categoryY + i * SettingsConfig.getScaledCategoryButtonSpacing());
        }
    }

    /** First row's top relative to the scrolled content (the padding rule). */
    private float rowTop(int i) {
        float s = com.stonebreak.config.Settings.getInstance().getUiScale();
        float topPadding = Math.max(SettingsConfig.getScaledScrollContentPadding(), 40f * s);
        float startY = scrollContainer.getContainerY() - scrollContainer.getScrollOffset() + topPadding;
        return startY + i * scrollContainer.getItemSpacing();
    }

    /**
     * Places every row of the selected category (culled ones too: a stale position could be
     * pressed outside the clip).
     */
    private void positionSettings() {
        CategoryState.SettingType[] settings = state.getSelectedCategory().getSettings();
        float centerX = scrollContainer.getContainerCenterX();
        float bw = SettingsConfig.getScaledButtonWidth();
        float bh = SettingsConfig.getScaledButtonHeight();
        for (int i = 0; i < settings.length; i++) {
            MWidget widget = state.widget(settings[i]);
            if (widget == null) continue;
            float rowY = rowTop(i);
            if (StateManager.isSlider(settings[i])) {
                widget.position(centerX, rowY + bh / 2f);
            } else {
                widget.position(centerX - bw / 2f, rowY);
            }
        }
    }

    private void positionActionButtons() {
        float s = com.stonebreak.config.Settings.getInstance().getUiScale();
        float bw = SettingsConfig.getScaledButtonWidth();
        float bh = SettingsConfig.getScaledButtonHeight();
        float centerX = scrollContainer.getContainerCenterX();
        float applyY = scrollContainer.getContainerBottom() + 20f * s;
        float backY  = applyY + bh + 15f * s;

        state.getApplyButton().position(centerX - bw / 2f, applyY);
        state.getBackButton() .position(centerX - bw / 2f, backY);
    }
}
