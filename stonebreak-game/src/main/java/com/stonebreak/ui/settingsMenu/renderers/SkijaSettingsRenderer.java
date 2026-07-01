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
import io.github.humbleui.skija.Font;
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
            float s = com.stonebreak.config.Settings.getInstance().getUiScale();
            float centerX = windowWidth / 2f;
            float centerY = windowHeight / 2f;

            float panelWidth = Math.min(700f * s, windowWidth * 0.92f);
            float panelHeight = Math.min(550f * s, windowHeight * 0.92f);
            float panelX = centerX - panelWidth / 2f;
            float panelY = centerY - panelHeight / 2f;

            float backdropExtra = 40f * s;

            Canvas canvas = ui.canvas();
            drawBackground(canvas, windowWidth, windowHeight);
            drawBackdropPanel(canvas, panelX, panelY, panelWidth, panelHeight + backdropExtra);

            float titleY = panelY + Math.max(40f * s, panelHeight * 0.08f);
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

            // Confirmation popup sits on top of everything, including overlays.
            if (state.isUiScaleConfirmActive()) {
                drawUiScaleConfirmation(canvas, windowWidth, windowHeight, s);
            }
        } finally {
            ui.endFrame();
        }
    }

    /**
     * Modal "keep this UI scale?" popup with a live countdown. The new scale is
     * already applied to the live UI here; if the user does nothing the menu's
     * per-frame tick auto-reverts, so an unreadable scale still self-heals.
     */
    private void drawUiScaleConfirmation(Canvas canvas, int w, int h, float s) {
        MPainter.fillRect(canvas, 0, 0, w, h, 0xC8000000);

        float dialogW = Math.min(460f * s, w * 0.9f);
        float dialogH = Math.min(210f * s, h * 0.9f);
        float dx = w / 2f - dialogW / 2f;
        float dy = h / 2f - dialogH / 2f;
        MPainter.panel(canvas, dx, dy, dialogW, dialogH);

        float cx = w / 2f;
        Font titleFont = ui.fonts().getScaled(MStyle.FONT_BUTTON);
        Font bodyFont  = ui.fonts().getScaled(MStyle.FONT_META);

        MPainter.drawCenteredStringWithShadow(canvas, "Keep UI Scale?",
                cx, dy + 42f * s, titleFont, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);

        String pendingStr  = String.format("New scale: %.1fx", state.getUiScaleSlider().value());
        String countdown   = "Reverting to " + String.format("%.1f", state.getUiScalePreviousScale())
                + "x in " + state.getUiScaleConfirmSecondsLeft() + "s";
        MPainter.drawCenteredStringWithShadow(canvas, pendingStr,
                cx, dy + 82f * s, bodyFont, MStyle.TEXT_PRIMARY, MStyle.TEXT_SHADOW);
        MPainter.drawCenteredStringWithShadow(canvas, countdown,
                cx, dy + 104f * s, bodyFont, MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);

        float bh  = SettingsConfig.getScaledButtonHeight();
        float gap = 16f * s;
        // Keep both buttons inside the dialog even when the base button width is wide.
        float bw  = Math.min(SettingsConfig.getScaledButtonWidth(), (dialogW - gap - 24f * s) / 2f);
        float btnY = dy + dialogH - bh - 22f * s;

        state.getKeepUiScaleButton().size(bw, bh).position(cx - gap / 2f - bw, btnY).render(ui);
        state.getRevertUiScaleButton().size(bw, bh).position(cx + gap / 2f, btnY).render(ui);
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
                    ui.fonts().getScaled(MStyle.FONT_TITLE), color);
        }
    }

    // ─────────────────────────────────────────────── Panels

    private void positionCategoryButtons(float centerX, float centerY) {
        float categoryX = centerX + SettingsConfig.getScaledCategoryPanelXOffset();
        float categoryY = centerY + SettingsConfig.getScaledCategoryButtonsStartY()
                - 20f * com.stonebreak.config.Settings.getInstance().getUiScale();
        var buttons = state.getCategoryButtons();
        for (int i = 0; i < buttons.size(); i++) {
            buttons.get(i).position(categoryX, categoryY + i * SettingsConfig.getScaledCategoryButtonSpacing());
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

        float s = com.stonebreak.config.Settings.getInstance().getUiScale();
        float offset = scrollContainer.getScrollOffset();
        float topPadding = Math.max(SettingsConfig.getScaledScrollContentPadding(), 40f * s);
        float startY = scrollContainer.getContainerY() - offset + topPadding;
        float centerX = scrollContainer.getContainerCenterX();
        float viewportY = scrollContainer.getContainerY();
        float viewportBottom = viewportY + scrollContainer.getContainerHeight();
        float cullBuffer = 50f * s;

        for (int i = 0; i < settings.length; i++) {
            float rowY = startY + i * scrollContainer.getItemSpacing();
            if (rowY + SettingsConfig.getScaledButtonHeight() < viewportY - cullBuffer) continue;
            if (rowY > viewportBottom + cullBuffer) continue;
            positionAndRenderSetting(settings[i], centerX, rowY);
        }
    }

    private void positionAndRenderSetting(CategoryState.SettingType type, float centerX, float y) {
        MWidget widget = resolveWidget(type);
        if (widget == null) return;
        if (isSlider(type)) {
            widget.position(centerX, y + SettingsConfig.getScaledButtonHeight() / 2f);
        } else {
            widget.position(centerX - SettingsConfig.getScaledButtonWidth() / 2f, y);
        }
        widget.render(ui);
    }

    private static boolean isSlider(CategoryState.SettingType type) {
        return type == CategoryState.SettingType.VOLUME
                || type == CategoryState.SettingType.CROSSHAIR_SIZE
                || type == CategoryState.SettingType.RENDER_DISTANCE
                || type == CategoryState.SettingType.LOD_DISTANCE
                || type == CategoryState.SettingType.MAX_FPS
                || type == CategoryState.SettingType.UI_SCALE;
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
            case CLOUDS_ENABLED   -> state.getCloudsButton();
            case GOD_RAYS         -> state.getGodRaysButton();
            case RENDER_DISTANCE  -> state.getRenderDistanceSlider();
            case LOD_DISTANCE     -> state.getLodDistanceSlider();
            case LOD_ENABLED      -> state.getLodEnabledButton();
            case VSYNC            -> state.getVsyncButton();
            case MAX_FPS          -> state.getMaxFpsSlider();
            case UI_SCALE         -> state.getUiScaleSlider();
            default -> null;
        };
    }

    // ─────────────────────────────────────────────── Action buttons

    private void drawActionButtons() {
        float s = com.stonebreak.config.Settings.getInstance().getUiScale();
        float bw = SettingsConfig.getScaledButtonWidth();
        float bh = SettingsConfig.getScaledButtonHeight();
        float centerX = scrollContainer.getContainerCenterX();
        float applyY = scrollContainer.getContainerBottom() + 20f * s;
        float backY  = applyY + bh + 15f * s;

        state.getApplyButton().position(centerX - bw / 2f, applyY).render(ui);
        state.getBackButton() .position(centerX - bw / 2f, backY) .render(ui);
    }
}
