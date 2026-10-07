package com.stonebreak.ui.settingsMenu.renderers;

import com.openmason.engine.ui.masonry.MCategoryButton;
import com.openmason.engine.ui.masonry.MDropdown;
import com.openmason.engine.ui.masonry.MPainter;
import com.openmason.engine.ui.masonry.MStyle;
import com.openmason.engine.ui.masonry.MWidget;
import com.openmason.engine.ui.masonry.MasonryUI;
import com.stonebreak.ui.runtime.providers.DirtBackdropProvider;
import com.stonebreak.ui.settingsMenu.components.ScrollableSettingsContainer;
import com.stonebreak.ui.settingsMenu.config.CategoryState;
import com.stonebreak.ui.settingsMenu.config.SettingsConfig;
import com.stonebreak.ui.settingsMenu.managers.StateManager;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;

import java.util.function.BiConsumer;

/**
 * Skija-backed settings screen. Composes the two-panel layout (categories
 * left, scrollable settings right) using MasonryUI widgets and primitives —
 * no NanoVG path on this screen.
 *
 * <p>{@link #layout} is the per-frame half (scroll easing, labels, every widget's position: the
 * hit tests' source of truth) and runs on its own while the screen's UI document shows (#299);
 * {@link #render} lays out and then paints background → categories → scroll viewport → action
 * buttons → dropdown overlays → the UI-scale confirmation.
 */
public final class SkijaSettingsRenderer {

    /** Where the frame's panel and title sit (device px), from {@link #layout}. */
    public record Frame(float centerX, float centerY, float panelX, float panelY, float panelWidth,
                        float panelHeight, float titleY) {
    }

    private final MasonryUI ui;
    private final StateManager state;
    private final ScrollableSettingsContainer scrollContainer;
    /** Where each part sits, by the document's names (fidelity gates, #299); null when nobody asks. */
    private BiConsumer<String, float[]> layoutSink;

    public SkijaSettingsRenderer(MasonryUI ui, StateManager state, ScrollableSettingsContainer scrollContainer) {
        this.ui = ui;
        this.state = state;
        this.scrollContainer = scrollContainer;
    }

    /**
     * Reports each part's rect ({@code x, y, w, h}) at layout: {@code category0..5}, {@code row0..}
     * (a slider's is its 3x-tall hit box), {@code apply}, {@code back}, {@code item0..} (the open
     * dropdown's list), {@code viewport}, {@code scrollbar} (its press area) and, while the UI-scale
     * confirmation is up, {@code dialog}, {@code keep}, {@code revert}.
     */
    public void setLayoutSink(BiConsumer<String, float[]> sink) {
        this.layoutSink = sink;
    }

    private void report(String part, float x, float y, float w, float h) {
        if (layoutSink != null) {
            layoutSink.accept(part, new float[]{x, y, w, h});
        }
    }

    public void render(int windowWidth, int windowHeight) {
        if (!ui.isAvailable()) return;
        if (!ui.beginFrame(windowWidth, windowHeight, 1.0f)) return;
        try {
            Frame f = layout(windowWidth, windowHeight);
            float s = com.stonebreak.config.Settings.getInstance().getUiScale();
            float backdropExtra = 40f * s;

            Canvas canvas = ui.canvas();
            drawBackground(canvas, windowWidth, windowHeight);
            drawBackdropPanel(canvas, f.panelX(), f.panelY(), f.panelWidth(), f.panelHeight() + backdropExtra);
            drawTitle(canvas, f.centerX(), f.titleY());

            drawCategoryPanel();
            scrollContainer.render(ui, this::paintScrollContent);
            state.getApplyButton().render(ui);
            state.getBackButton().render(ui);

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

        MPainter.drawCenteredStringWithShadow(canvas, state.uiScalePendingText(),
                cx, dy + 82f * s, bodyFont, MStyle.TEXT_PRIMARY, MStyle.TEXT_SHADOW);
        MPainter.drawCenteredStringWithShadow(canvas, state.uiScaleCountdownText(),
                cx, dy + 104f * s, bodyFont, MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);

        state.getKeepUiScaleButton().render(ui);
        state.getRevertUiScaleButton().render(ui);
    }

    private void positionConfirmation(int w, int h, float s) {
        float dialogW = Math.min(460f * s, w * 0.9f);
        float dialogH = Math.min(210f * s, h * 0.9f);
        float dx = w / 2f - dialogW / 2f;
        float dy = h / 2f - dialogH / 2f;
        float cx = w / 2f;
        float bh  = SettingsConfig.getScaledButtonHeight();
        float gap = 16f * s;
        // Keep both buttons inside the dialog even when the base button width is wide.
        float bw  = Math.min(SettingsConfig.getScaledButtonWidth(), (dialogW - gap - 24f * s) / 2f);
        float btnY = dy + dialogH - bh - 22f * s;

        state.getKeepUiScaleButton().size(bw, bh).position(cx - gap / 2f - bw, btnY);
        state.getRevertUiScaleButton().size(bw, bh).position(cx + gap / 2f, btnY);
        report("dialog", dx, dy, dialogW, dialogH);
        report("keep", cx - gap / 2f - bw, btnY, bw, bh);
        report("revert", cx + gap / 2f, btnY, bw, bh);
    }

    public void dispose() {
    }

    // ─────────────────────────────────────────────── Background

    private void drawBackground(Canvas canvas, int w, int h) {
        // The shared menu backdrop (dark base under the dirt tiles), as the document draws it
        DirtBackdropProvider.paint(canvas, 0, 0, w, h, DirtBackdropProvider.TILE_SCALE);
        // Darker full-screen tint so the centered panel stands out.
        MPainter.fillRect(canvas, 0, 0, w, h, 0xB4000000);
    }

    private void drawBackdropPanel(Canvas canvas, float x, float y, float w, float h) {
        MPainter.panel(canvas, x, y, w, h);
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
            MCategoryButton<CategoryState> b = buttons.get(i);
            b.position(categoryX, categoryY + i * SettingsConfig.getScaledCategoryButtonSpacing());
            report("category" + i, b.x(), b.y(), b.width(), b.height());
        }
    }

    private void drawCategoryPanel() {
        for (MCategoryButton<CategoryState> button : state.getCategoryButtons()) {
            button.render(ui);
        }
    }

    // ─────────────────────────────────────────────── Scroll viewport

    /** First row's top relative to the scrolled content (the legacy padding rule). */
    private float rowTop(int i) {
        float s = com.stonebreak.config.Settings.getInstance().getUiScale();
        float topPadding = Math.max(SettingsConfig.getScaledScrollContentPadding(), 40f * s);
        float startY = scrollContainer.getContainerY() - scrollContainer.getScrollOffset() + topPadding;
        return startY + i * scrollContainer.getItemSpacing();
    }

    /**
     * Places every row of the selected category (culled ones too: a stale position could be
     * pressed outside the clip). Reports the viewport, the scrollbar and the open dropdown's list.
     */
    private void positionSettings() {
        CategoryState.SettingType[] settings = state.getSelectedCategory().getSettings();
        float centerX = scrollContainer.getContainerCenterX();
        float bw = SettingsConfig.getScaledButtonWidth();
        float bh = SettingsConfig.getScaledButtonHeight();
        report("viewport", scrollContainer.getContainerX(), scrollContainer.getContainerY(),
                scrollContainer.getContainerWidth(), scrollContainer.getContainerHeight());
        for (int i = 0; i < settings.length; i++) {
            MWidget widget = state.widget(settings[i]);
            if (widget == null) continue;
            float rowY = rowTop(i);
            if (StateManager.isSlider(settings[i])) {
                widget.position(centerX, rowY + bh / 2f);
                report("row" + i, widget.x() - widget.width() / 2f,
                        widget.y() - widget.height() * 3f / 2f, widget.width(), widget.height() * 3f);
            } else {
                widget.position(centerX - bw / 2f, rowY);
                report("row" + i, widget.x(), widget.y(), widget.width(), widget.height());
            }
            if (widget instanceof MDropdown d && d.isOpen()) {
                for (int k = 0; k < d.items().length; k++) {
                    report("item" + k, d.x(), d.y() + d.height() + k * d.itemHeightPx(),
                            d.width(), d.itemHeightPx());
                }
            }
        }
        float[] bar = scrollContainer.scrollbarHitBounds();
        if (bar != null) {
            report("scrollbar", bar[0], bar[1], bar[2], bar[3]);
        }
    }

    private void paintScrollContent() {
        CategoryState.SettingType[] settings = state.getSelectedCategory().getSettings();
        float s = com.stonebreak.config.Settings.getInstance().getUiScale();
        float viewportY = scrollContainer.getContainerY();
        float viewportBottom = viewportY + scrollContainer.getContainerHeight();
        float cullBuffer = 50f * s;

        for (int i = 0; i < settings.length; i++) {
            float rowY = rowTop(i);
            if (rowY + SettingsConfig.getScaledButtonHeight() < viewportY - cullBuffer) continue;
            if (rowY > viewportBottom + cullBuffer) continue;
            MWidget widget = state.widget(settings[i]);
            if (widget != null) widget.render(ui);
        }
    }

    // ─────────────────────────────────────────────── Action buttons

    private void positionActionButtons() {
        float s = com.stonebreak.config.Settings.getInstance().getUiScale();
        float bw = SettingsConfig.getScaledButtonWidth();
        float bh = SettingsConfig.getScaledButtonHeight();
        float centerX = scrollContainer.getContainerCenterX();
        float applyY = scrollContainer.getContainerBottom() + 20f * s;
        float backY  = applyY + bh + 15f * s;

        state.getApplyButton().position(centerX - bw / 2f, applyY);
        state.getBackButton() .position(centerX - bw / 2f, backY);
        report("apply", centerX - bw / 2f, applyY, bw, bh);
        report("back", centerX - bw / 2f, backY, bw, bh);
    }
}
