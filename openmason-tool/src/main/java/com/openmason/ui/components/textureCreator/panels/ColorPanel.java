package com.openmason.ui.components.textureCreator.panels;

import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;
import imgui.ImGui;
import imgui.type.ImFloat;
import imgui.type.ImString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Color panel - RGBA color picker and swatch display.
 *
 * Follows SOLID principles - Single Responsibility: color selection UI only.
 *
 * @author Open Mason Team
 */
public class ColorPanel {

    private static final Logger logger = LoggerFactory.getLogger(ColorPanel.class);

    // Color components (0-255)
    private final ImFloat red = new ImFloat(0);
    private final ImFloat green = new ImFloat(0);
    private final ImFloat blue = new ImFloat(0);
    private final ImFloat alpha = new ImFloat(255);

    // Hex input
    private final ImString hexInput = new ImString(8);

    // Previous color (for swatch)
    private int previousColor = 0xFF000000; // Black

    /**
     * Create color panel.
     */
    public ColorPanel() {
        setColor(0xFF000000); // Black, full alpha
        logger.debug("Color panel created");
    }

    /**
     * Render the color panel.
     */
    public void render() {
        ImGui.beginChild("##color_panel", 0, 0, false);

        ImGui.text("Color");
        ImGui.separator();

        // Current color swatch
        renderColorSwatch();

        ImGui.spacing();

        // RGBA sliders
        renderRGBASliders();

        ImGui.spacing();

        // Hex color input
        renderHexInput();

        ImGui.spacing();

        // Previous color swatch (for quick color switching)
        renderPreviousColorSwatch();

        ImGui.endChild();
    }

    /**
     * Render current color swatch.
     */
    private void renderColorSwatch() {
        int currentColor = getCurrentColor();
        int[] rgba = PixelCanvas.unpackRGBA(currentColor);

        // Display color as a colored square
        float[] colorFloat = new float[]{
            rgba[0] / 255.0f,
            rgba[1] / 255.0f,
            rgba[2] / 255.0f,
            rgba[3] / 255.0f
        };

        ImGui.text("Current:");
        ImGui.sameLine();
        ImGui.colorButton("##current_color", colorFloat, 0, 50, 50);
    }

    /**
     * Render RGBA sliders.
     */
    private void renderRGBASliders() {
        ImGui.pushItemWidth(150);

        // Red slider
        if (ImGui.sliderFloat("R##red", red.getData(), 0, 255, "%.0f")) {
            updateHexFromRGBA();
        }

        // Green slider
        if (ImGui.sliderFloat("G##green", green.getData(), 0, 255, "%.0f")) {
            updateHexFromRGBA();
        }

        // Blue slider
        if (ImGui.sliderFloat("B##blue", blue.getData(), 0, 255, "%.0f")) {
            updateHexFromRGBA();
        }

        // Alpha slider
        if (ImGui.sliderFloat("A##alpha", alpha.getData(), 0, 255, "%.0f")) {
            updateHexFromRGBA();
        }

        ImGui.popItemWidth();
    }

    /**
     * Render hex color input.
     */
    private void renderHexInput() {
        ImGui.text("Hex:");
        ImGui.sameLine();
        ImGui.pushItemWidth(100);

        if (ImGui.inputText("##hex", hexInput, imgui.flag.ImGuiInputTextFlags.CharsHexadecimal |
                                                imgui.flag.ImGuiInputTextFlags.CharsUppercase)) {
            updateRGBAFromHex();
        }

        ImGui.popItemWidth();

        // Show format hint
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Format: RRGGBBAA (hex)");
        }
    }

    /**
     * Render previous color swatch for quick switching.
     */
    private void renderPreviousColorSwatch() {
        int[] rgba = PixelCanvas.unpackRGBA(previousColor);

        float[] colorFloat = new float[]{
            rgba[0] / 255.0f,
            rgba[1] / 255.0f,
            rgba[2] / 255.0f,
            rgba[3] / 255.0f
        };

        ImGui.text("Previous:");
        ImGui.sameLine();

        if (ImGui.colorButton("##previous_color", colorFloat, 0, 50, 50)) {
            // Swap current and previous colors
            int temp = getCurrentColor();
            setColor(previousColor);
            previousColor = temp;
        }

        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Click to swap with current color");
        }
    }

    /**
     * Get current color as packed RGBA int.
     * @return packed RGBA color
     */
    public int getCurrentColor() {
        return PixelCanvas.packRGBA(
            (int) red.get(),
            (int) green.get(),
            (int) blue.get(),
            (int) alpha.get()
        );
    }

    /**
     * Set current color.
     * @param color packed RGBA color
     */
    public void setColor(int color) {
        // Store previous color before changing
        int current = getCurrentColor();
        if (current != color) {
            previousColor = current;
        }

        // Update color components
        int[] rgba = PixelCanvas.unpackRGBA(color);
        red.set(rgba[0]);
        green.set(rgba[1]);
        blue.set(rgba[2]);
        alpha.set(rgba[3]);

        updateHexFromRGBA();
    }

    /**
     * Update hex input from RGBA values.
     */
    private void updateHexFromRGBA() {
        int r = (int) red.get();
        int g = (int) green.get();
        int b = (int) blue.get();
        int a = (int) alpha.get();

        String hex = String.format("%02X%02X%02X%02X", r, g, b, a);
        hexInput.set(hex);
    }

    /**
     * Update RGBA values from hex input.
     */
    private void updateRGBAFromHex() {
        String hex = hexInput.get().trim();

        // Pad or truncate to 8 characters
        if (hex.length() < 8) {
            hex = hex + "FF".repeat((8 - hex.length()) / 2);
        }
        if (hex.length() > 8) {
            hex = hex.substring(0, 8);
        }

        try {
            int r = Integer.parseInt(hex.substring(0, 2), 16);
            int g = Integer.parseInt(hex.substring(2, 4), 16);
            int b = Integer.parseInt(hex.substring(4, 6), 16);
            int a = Integer.parseInt(hex.substring(6, 8), 16);

            red.set(r);
            green.set(g);
            blue.set(b);
            alpha.set(a);
        } catch (NumberFormatException e) {
            logger.warn("Invalid hex color: {}", hex);
        }
    }
}
