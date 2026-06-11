package com.openmason.main.systems.menus.textureCreator.panels.color;

import imgui.ImGui;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Color panel coordinator — single combined view: picker on top, then the
 * recent-colors row, then the Saved Palettes section (selector + editable
 * swatch grid). Preserves the legacy {@code ColorPanel} public surface
 * (render, get/setColor, addColorToHistory, color-history persistence) while
 * delegating to focused components: {@link ColorSelectionState},
 * {@link SkijaColorPickerRenderer}, {@link ColorPickerInteraction},
 * {@link RgbSliderGroup}, {@link HexAlphaControls}, {@link ColorHistoryStrip},
 * {@link PaletteSectionView}.
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
    private final PaletteSectionView paletteSection;

    private ColorMode colorMode = ColorMode.HSV;

    public ColorPanelView(com.openmason.main.systems.menus.textureCreator.palette.PaletteLibrary paletteLibrary) {
        this.paletteSection = paletteLibrary != null ? new PaletteSectionView(paletteLibrary) : null;
    }

    public void render() {
        // NoScrollWithMouse: the wheel adjusts hue/alpha/RGB sliders here, so
        // it must not simultaneously scroll the panel (the binding lacks
        // setItemKeyOwner to claim the wheel per-item). The scrollbar still
        // appears and works by dragging when content overflows.
        ImGui.beginChild("##color_panel", 0, 0, false,
                imgui.flag.ImGuiWindowFlags.NoScrollWithMouse);

        renderPickerTab();

        ImGui.spacing();
        ImGui.separatorText("Recent");
        history.render(state::setColor);

        if (paletteSection != null) {
            ImGui.spacing();
            ImGui.separatorText("Palette");
            paletteSection.render(state::getCurrentColor, state::setColor);
        }

        ImGui.spacing();
        ImGui.endChild();
    }

    private static final float HUE_GAP = 8f;
    private static final float MIN_PICKER_SIZE = 120f;
    private static final float MAX_PICKER_SIZE = 280f;
    private static final float ALPHA_BAR_HEIGHT = 14f;

    private void renderPickerTab() {
        ImGui.spacing();
        renderHeaderRow();

        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();

        renderModeSelector();
        ImGui.spacing();

        boolean skija = pickerRenderer.isAvailable();
        float pickerWidth;

        if (colorMode == ColorMode.HSV && skija) {
            // Responsive: SV square fills the panel width next to the hue bar
            float avail = ImGui.getContentRegionAvailX();
            float svSize = Math.max(MIN_PICKER_SIZE,
                    Math.min(avail - HUE_GAP - SkijaColorPickerRenderer.HUE_WIDTH, MAX_PICKER_SIZE));
            pickerWidth = svSize + HUE_GAP + SkijaColorPickerRenderer.HUE_WIDTH;

            pickerRenderer.drawSVSquare(state, svSize, svSize);
            pickerInteraction.handleSVInput(state, svSize, svSize);

            ImGui.sameLine(0, HUE_GAP);

            pickerRenderer.drawHueBar(state, svSize);
            pickerInteraction.handleHueInput(state, svSize);
        } else {
            rgbSliders.render(state);
            pickerWidth = Math.min(ImGui.getContentRegionAvailX(),
                    MAX_PICKER_SIZE + HUE_GAP + SkijaColorPickerRenderer.HUE_WIDTH);
        }

        ImGui.spacing();

        if (skija) {
            pickerRenderer.drawAlphaBar(state, pickerWidth, ALPHA_BAR_HEIGHT);
            pickerInteraction.handleAlphaInput(state, pickerWidth);
            if (ImGui.isItemHovered()) {
                int percent = (int) ((state.getAlpha() / 255.0f) * 100);
                ImGui.setTooltip("Alpha = " + state.getAlpha() + " (" + percent + "%)");
            }
        } else {
            hexAlpha.renderAlphaSlider(state);
        }
    }

    /**
     * Compact header: current + previous swatches with the hex input and
     * alpha readout beside them.
     */
    private void renderHeaderRow() {
        float swatchSize = 38f;

        SwatchRenderer.render("##current_swatch", getCurrentColor(), swatchSize, null);

        ImGui.sameLine(0, 4);
        SwatchRenderer.render("##previous_swatch", state.getPreviousColor(), swatchSize,
                state::swapWithPrevious);
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Previous color — click to swap");
        }

        ImGui.sameLine(0, 14);
        ImGui.beginGroup();
        hexAlpha.renderHexInput(state);
        ImGui.textDisabled(String.format("A %3d (%3d%%)",
                state.getAlpha(), (int) ((state.getAlpha() / 255.0f) * 100)));
        ImGui.endGroup();
    }

    /** Compact two-segment HSV/RGB switch. */
    private void renderModeSelector() {
        renderModeButton("HSV", ColorMode.HSV);
        ImGui.sameLine(0, 2);
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
        if (ImGui.button(label, 60f, 0)) {
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
