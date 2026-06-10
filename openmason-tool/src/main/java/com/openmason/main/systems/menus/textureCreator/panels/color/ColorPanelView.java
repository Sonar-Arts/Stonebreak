package com.openmason.main.systems.menus.textureCreator.panels.color;

import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import imgui.ImGui;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Color panel coordinator — drop-in replacement for the legacy monolithic
 * {@code ColorPanel}, preserving its public surface (render, get/setColor,
 * addColorToHistory, color-history persistence) while delegating to focused
 * components: {@link ColorSelectionState}, {@link SkijaColorPickerRenderer},
 * {@link ColorPickerInteraction}, {@link RgbSliderGroup},
 * {@link HexAlphaControls}, {@link ColorHistoryStrip}.
 */
public final class ColorPanelView implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ColorPanelView.class);

    private enum ColorMode {
        HSV,
        RGB
    }

    private final ColorSelectionState state = new ColorSelectionState();
    private final SkijaColorPickerRenderer pickerRenderer = new SkijaColorPickerRenderer();
    private final ColorPickerInteraction pickerInteraction = new ColorPickerInteraction();
    private final RgbSliderGroup rgbSliders = new RgbSliderGroup();
    private final HexAlphaControls hexAlpha = new HexAlphaControls();
    private final ColorHistoryStrip history = new ColorHistoryStrip();

    private ColorMode colorMode = ColorMode.HSV;

    public void render() {
        ImGui.beginChild("##color_panel", 0, 0, false);

        if (ImGui.beginTabBar("##ColorPanelTabs")) {
            if (ImGui.beginTabItem("Picker")) {
                renderPickerTab();
                ImGui.endTabItem();
            }
            if (ImGui.beginTabItem("History")) {
                history.render(state::setColor);
                ImGui.endTabItem();
            }
            ImGui.endTabBar();
        }

        ImGui.endChild();
    }

    private void renderPickerTab() {
        renderColorSwatches();

        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();

        renderModeSelector();

        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();

        if (colorMode == ColorMode.HSV && pickerRenderer.isAvailable()) {
            renderHSVPicker();
        } else {
            rgbSliders.render(state);
        }

        ImGui.spacing();
        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();

        hexAlpha.renderAlphaSlider(state);

        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();

        hexAlpha.renderHexInput(state);
    }

    /** Skija-painted SV square + hue bar with item-based interaction. */
    private void renderHSVPicker() {
        ImGui.text("Color");
        ImGui.spacing();

        pickerRenderer.drawSVSquare(state);
        pickerInteraction.handleSVInput(state,
                SkijaColorPickerRenderer.SV_SIZE, SkijaColorPickerRenderer.SV_SIZE);

        ImGui.sameLine();
        ImGui.dummy(10, 0);
        ImGui.sameLine();

        pickerRenderer.drawHueBar(state);
        pickerInteraction.handleHueInput(state, SkijaColorPickerRenderer.SV_SIZE);
    }

    private void renderColorSwatches() {
        float swatchSize = 60;

        ImGui.beginGroup();
        ImGui.text("Current");
        SwatchRenderer.render("##current_swatch", getCurrentColor(), swatchSize, null);
        ImGui.textDisabled(alphaLabel(getCurrentColor()));
        ImGui.endGroup();

        ImGui.sameLine();
        ImGui.dummy(20, 0);
        ImGui.sameLine();

        ImGui.beginGroup();
        ImGui.text("Previous");
        SwatchRenderer.render("##previous_swatch", state.getPreviousColor(), swatchSize,
                state::swapWithPrevious);
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Click to swap with current");
        }
        ImGui.textDisabled(alphaLabel(state.getPreviousColor()));
        ImGui.endGroup();
    }

    private static String alphaLabel(int color) {
        int alpha = PixelCanvas.unpackRGBA(color)[3];
        return String.format("A: %d%%", (int) ((alpha / 255.0f) * 100));
    }

    private void renderModeSelector() {
        ImGui.text("Color Mode");
        ImGui.spacing();
        renderModeButton("HSV", ColorMode.HSV);
        ImGui.sameLine();
        renderModeButton("RGB", ColorMode.RGB);
    }

    private void renderModeButton(String label, ColorMode mode) {
        boolean selected = (colorMode == mode);
        if (selected) {
            ImVec4 accent = ImGui.getStyle().getColor(ImGuiCol.HeaderActive);
            ImGui.pushStyleColor(ImGuiCol.Button, accent.x, accent.y, accent.z, 1.0f);
            ImGui.pushStyleColor(ImGuiCol.ButtonHovered, accent.x, accent.y, accent.z, 0.85f);
            ImGui.pushStyleColor(ImGuiCol.ButtonActive, accent.x, accent.y, accent.z, 0.70f);
        }
        if (ImGui.button(label, 90f, 0)) {
            colorMode = mode;
            logger.debug("Switched to {} mode", label);
        }
        if (selected) {
            ImGui.popStyleColor(3);
        }
    }

    // ================================
    // Public API (drop-in compatible with the legacy ColorPanel)
    // ================================

    /** Current color as packed RGBA int. */
    public int getCurrentColor() {
        return state.getCurrentColor();
    }

    /**
     * Set the current color. Does not touch the previous color or history —
     * those advance via {@link #addColorToHistory(int)} when painting.
     */
    public void setColor(int color) {
        state.setColor(color);
    }

    /** Record a color painted on the canvas: history + previous tracking. */
    public void addColorToHistory(int color) {
        state.markPainted();
        history.add(color);
    }

    public List<Integer> getColorHistory() {
        return history.getColors();
    }

    public void setColorHistory(List<Integer> colors) {
        history.setColors(colors);
    }

    @Override
    public void close() {
        pickerRenderer.close();
    }
}
