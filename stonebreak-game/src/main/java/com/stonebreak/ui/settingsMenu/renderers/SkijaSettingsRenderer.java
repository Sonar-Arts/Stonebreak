package com.stonebreak.ui.settingsMenu.renderers;

import com.stonebreak.rendering.UI.masonryUI.MCategoryButton;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.masonryUI.MWidget;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.settingsMenu.components.ScrollableSettingsContainer;
import com.stonebreak.ui.settingsMenu.config.CategoryState;
import com.stonebreak.ui.settingsMenu.config.SettingsConfig;
import com.stonebreak.ui.settingsMenu.managers.StateManager;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.Rect;

/**
 * Skija-backed settings screen. Composes the two-panel layout (categories
 * left, scrollable settings right) using MasonryUI widgets and primitives —
 * no NanoVG path on this screen.
 *
 * Splits cleanly into background → categories → scroll viewport → action
 * buttons → dropdown overlays. Layout math mirrors the legacy
 * {@code SectionRenderer}; that logic was renderer-agnostic.
 */
public final class SkijaSettingsRenderer {

    private final MasonryUI ui;
    private final StateManager state;
    private final ScrollableSettingsContainer scrollContainer;

    private Shader dirtShader;

    public SkijaSettingsRenderer(MasonryUI ui, StateManager state, ScrollableSettingsContainer scrollContainer) {
        this.ui = ui;
        this.state = state;
        this.scrollContainer = scrollContainer;
    }

    public void render(int windowWidth, int windowHeight) {
        if (!ui.isAvailable()) return;
        if (!ui.beginFrame(windowWidth, windowHeight, 1.0f)) return;
        try {
            float centerX = windowWidth / 2f;
            float centerY = windowHeight / 2f;

            // Panel matches legacy NanoVG SettingsMenu sizing so content
            // alignment (category column, scroll viewport, title) is identical.
            float panelWidth = Math.min(700f, windowWidth * 0.85f);
            float panelHeight = Math.min(550f, windowHeight * 0.85f);
            float panelX = centerX - panelWidth / 2f;
            float panelY = centerY - panelHeight / 2f;

            // Draw the visible backdrop slightly taller than the content grid
            // so the Back button doesn't kiss the bottom edge.
            float backdropExtra = 40f;

            Canvas canvas = ui.canvas();
            drawBackground(canvas, windowWidth, windowHeight);
            drawBackdropPanel(canvas, panelX, panelY, panelWidth, panelHeight + backdropExtra);

            float titleY = panelY + Math.max(40f, panelHeight * 0.08f);
            drawTitle(canvas, centerX, titleY);

            if (state.getCurrentScrollMath() != null) {
                state.getCurrentScrollMath().update(1f / 60f);
            }
            state.refreshLabels();

            positionCategoryButtons(centerX, centerY);
            state.updateButtonSelectionStates();

            scrollContainer.updateBounds(centerX, centerY, panelHeight);

            drawCategoryPanel();
            drawScrollableSettings(centerX, centerY);
            drawActionButtons();

            // Dropdowns drew earlier but queued themselves as overlays.
            ui.renderOverlays();
        } finally {
            ui.endFrame();
        }
    }

    public void dispose() {
        if (dirtShader != null) { dirtShader.close(); dirtShader = null; }
    }

    // ─────────────────────────────────────────────── Background

    private void drawBackground(Canvas canvas, int w, int h) {
        MPainter.fillRect(canvas, 0, 0, w, h, 0xFF2C2C2C);
        ensureDirtShader();
        if (dirtShader != null) {
            try (Paint p = new Paint().setShader(dirtShader)) {
                canvas.save();
                canvas.scale(4f, 4f);
                canvas.drawRect(Rect.makeXYWH(0, 0, w / 4f, h / 4f), p);
                canvas.restore();
            }
        }
        // Darker full-screen tint so the centered panel stands out.
        MPainter.fillRect(canvas, 0, 0, w, h, 0xB4000000);
    }

    private void drawBackdropPanel(Canvas canvas, float x, float y, float w, float h) {
        MPainter.panel(canvas, x, y, w, h);
    }

    private void ensureDirtShader() {
        if (dirtShader != null) return;
        Image dirt = ui.backend() != null ? ui.backend().getDirtTexture() : null;
        if (dirt == null) return;
        dirtShader = dirt.makeShader(FilterTileMode.REPEAT, FilterTileMode.REPEAT, SamplingMode.DEFAULT, null);
    }

    private void drawTitle(Canvas canvas, float centerX, float titleY) {
        String title = "SETTINGS";
        for (int i = 4; i >= 0; i--) {
            int color;
            switch (i) {
                case 0 -> color = MStyle.TEXT_PRIMARY;
                case 1 -> color = 0xFFC8C8BE;
                default -> {
                    int v = Math.max(30, 80 - i * 15);
                    color = (0xC8 << 24) | (v << 16) | (v << 8) | v;
                }
            }
            MPainter.drawCenteredString(canvas, title, centerX + i * 2f, titleY + i * 2f,
                    ui.fonts().get(MStyle.FONT_TITLE), color);
        }
    }

    // ─────────────────────────────────────────────── Panels

    private void positionCategoryButtons(float centerX, float centerY) {
        float categoryX = centerX + SettingsConfig.CATEGORY_PANEL_X_OFFSET;
        float categoryY = centerY + SettingsConfig.CATEGORY_BUTTONS_START_Y_OFFSET - 20f;
        var buttons = state.getCategoryButtons();
        for (int i = 0; i < buttons.size(); i++) {
            buttons.get(i).position(categoryX, categoryY + i * SettingsConfig.CATEGORY_BUTTON_SPACING);
        }
    }

    private void drawCategoryPanel() {
        for (MCategoryButton<CategoryState> button : state.getCategoryButtons()) {
            button.render(ui);
        }
    }

    // ─────────────────────────────────────────────── Scroll viewport

    private void drawScrollableSettings(float centerX, float centerY) {
        scrollContainer.render(ui, this::paintScrollContent);
    }

    private void paintScrollContent() {
        CategoryState category = state.getSelectedCategory();
        CategoryState.SettingType[] settings = category.getSettings();

        float offset = scrollContainer.getScrollOffset();
        float topPadding = Math.max(SettingsConfig.SCROLL_CONTENT_PADDING, 40f);
        float startY = scrollContainer.getContainerY() - offset + topPadding;
        float centerX = scrollContainer.getContainerCenterX();
        float viewportY = scrollContainer.getContainerY();
        float viewportBottom = viewportY + scrollContainer.getContainerHeight();
        float cullBuffer = 50f;

        for (int i = 0; i < settings.length; i++) {
            float rowY = startY + i * scrollContainer.getItemSpacing();
            if (rowY + SettingsConfig.BUTTON_HEIGHT < viewportY - cullBuffer) continue;
            if (rowY > viewportBottom + cullBuffer) continue;
            positionAndRenderSetting(settings[i], centerX, rowY);
        }
    }

    private void positionAndRenderSetting(CategoryState.SettingType type, float centerX, float y) {
        MWidget widget = resolveWidget(type);
        if (widget == null) return;
        if (type == CategoryState.SettingType.VOLUME || type == CategoryState.SettingType.CROSSHAIR_SIZE) {
            // Sliders use centre-positioning
            widget.position(centerX, y + SettingsConfig.BUTTON_HEIGHT / 2f);
        } else {
            widget.position(centerX - SettingsConfig.BUTTON_WIDTH / 2f, y);
        }
        widget.render(ui);
    }

    private MWidget resolveWidget(CategoryState.SettingType type) {
        return switch (type) {
            case RESOLUTION       -> state.getResolutionButton();
            case VOLUME           -> state.getVolumeSlider();
            case ARM_MODEL        -> state.getArmModelButton();
            case CROSSHAIR_STYLE  -> state.getCrosshairStyleButton();
            case CROSSHAIR_SIZE   -> state.getCrosshairSizeSlider();
            case LEAF_TRANSPARENCY -> state.getLeafTransparencyButton();
            case WATER_SHADER     -> state.getWaterShaderButton();
            default -> null;
        };
    }

    // ─────────────────────────────────────────────── Action buttons

    private void drawActionButtons() {
        float centerX = scrollContainer.getContainerCenterX();
        float applyY = scrollContainer.getContainerBottom() + 20f;
        float backY = applyY + SettingsConfig.BUTTON_HEIGHT + 15f;

        state.getApplyButton()
                .position(centerX - SettingsConfig.BUTTON_WIDTH / 2f, applyY)
                .render(ui);
        state.getBackButton()
                .position(centerX - SettingsConfig.BUTTON_WIDTH / 2f, backY)
                .render(ui);
    }
}
