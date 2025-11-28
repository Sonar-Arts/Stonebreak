package com.openmason.ui.textureCreator.panels;

import com.openmason.ui.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.textureCreator.color.ColorUtils;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiCol;
import imgui.type.ImFloat;
import imgui.type.ImString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Professional color panel with HSV/RGB picker, history, and harmony tools.
 *
 * Features:
 * - HSV color wheel with saturation/value picking
 * - RGB sliders with direct component control
 * - Mode switching between HSV and RGB
 * - Alpha slider with checkerboard preview
 * - Color history (last 10 colors)
 * - Hex input/output
 * - Tabbed interface for organization
 *
 * Follows SOLID principles:
 * - Single Responsibility: Color selection and management UI
 * - Maintains backward compatibility with existing API
 *
 * @author Open Mason Team
 */
public class ColorPanel {

    private static final Logger logger = LoggerFactory.getLogger(ColorPanel.class);

    /**
     * Color picker mode.
     */
    public enum ColorMode {
        HSV,
        RGB
    }

    // Current color mode
    private ColorMode colorMode = ColorMode.HSV;

    // HSV color state
    private float hue = 0f;          // 0-360 degrees
    private float saturation = 1f;   // 0-1
    private float value = 1f;        // 0-1
    private int alpha = 255;         // 0-255

    // RGB color state (0-255)
    private final ImFloat red = new ImFloat(0);
    private final ImFloat green = new ImFloat(0);
    private final ImFloat blue = new ImFloat(0);
    private final ImFloat alphaFloat = new ImFloat(255);

    // Hex input
    private final ImString hexInput = new ImString(16);

    // Previous color (for quick swap) - shows last color painted on canvas
    private int previousColor = 0xFF000000; // Black

    // Last painted color - tracks what was painted before current
    private int lastPaintedColor = 0xFF000000; // Black

    // Color history (last 10 colors used)
    private final List<Integer> colorHistory = new ArrayList<>();
    private static final int MAX_HISTORY_SIZE = 10;


    /**
     * Create color panel.
     */
    public ColorPanel() {
        setColor(0xFF000000); // Black, full alpha
        logger.debug("Color panel created with HSV picker");
    }

    /**
     * Render the color panel with tabbed interface.
     */
    public void render() {
        ImGui.beginChild("##color_panel", 0, 0, false);

        // Render tab bar
        if (ImGui.beginTabBar("##ColorPanelTabs")) {

            // Tab 1: Color Picker
            if (ImGui.beginTabItem("Picker")) {
                renderPickerTab();
                ImGui.endTabItem();
            }

            // Tab 2: Color History
            if (ImGui.beginTabItem("History")) {
                renderHistoryTab();
                ImGui.endTabItem();
            }

            ImGui.endTabBar();
        }

        ImGui.endChild();
    }

    // ================================
    // Tab Rendering
    // ================================

    /**
     * Render the Picker tab with mode switching and color controls.
     */
    private void renderPickerTab() {
        // Current and previous color swatches
        renderColorSwatches();

        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();

        // Mode switching buttons
        renderModeSelector();

        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();

        // Render color picker based on mode
        if (colorMode == ColorMode.HSV) {
            renderHSVColorWheel();
        } else {
            renderRGBSliders();
        }

        ImGui.spacing();
        ImGui.spacing();
        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();
        ImGui.spacing();

        // Alpha slider (common to both modes)
        renderAlphaSlider();

        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();

        // Hex input (common to both modes)
        renderHexInput();
    }

    /**
     * Render the History tab with recent colors.
     */
    private void renderHistoryTab() {
        ImGui.text("Recent Colors");
        ImGui.separator();
        ImGui.spacing();

        if (colorHistory.isEmpty()) {
            ImGui.textDisabled("No colors in history yet");
            ImGui.text("Colors will appear here as you use them");
        } else {
            renderColorHistoryGrid();
        }
    }

    // ================================
    // Mode Selector
    // ================================

    /**
     * Render professional mode selector with button group.
     */
    private void renderModeSelector() {
        ImGui.text("Color Mode");
        ImGui.spacing();

        float buttonWidth = 90f;

        // HSV button
        boolean isHSV = (colorMode == ColorMode.HSV);
        if (isHSV) {
            ImGui.pushStyleColor(ImGuiCol.Button, 0.26f, 0.59f, 0.98f, 1.0f);
            ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0.26f, 0.59f, 0.98f, 1.0f);
            ImGui.pushStyleColor(ImGuiCol.ButtonActive, 0.06f, 0.53f, 0.98f, 1.0f);
        }

        if (ImGui.button("HSV", buttonWidth, 0)) {
            colorMode = ColorMode.HSV;
            logger.debug("Switched to HSV mode");
        }

        if (isHSV) {
            ImGui.popStyleColor(3);
        }

        ImGui.sameLine();

        // RGB button
        boolean isRGB = (colorMode == ColorMode.RGB);
        if (isRGB) {
            ImGui.pushStyleColor(ImGuiCol.Button, 0.26f, 0.59f, 0.98f, 1.0f);
            ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0.26f, 0.59f, 0.98f, 1.0f);
            ImGui.pushStyleColor(ImGuiCol.ButtonActive, 0.06f, 0.53f, 0.98f, 1.0f);
        }

        if (ImGui.button("RGB", buttonWidth, 0)) {
            colorMode = ColorMode.RGB;
            logger.debug("Switched to RGB mode");
        }

        if (isRGB) {
            ImGui.popStyleColor(3);
        }
    }

    // ================================
    // Color Swatch Rendering
    // ================================

    /**
     * Render current and previous color swatches with alpha information.
     */
    private void renderColorSwatches() {
        float swatchSize = 60;

        // === Current Color ===
        ImGui.beginGroup();
        ImGui.text("Current");
        renderColorSwatch(getCurrentColor(), swatchSize, null);

        // Display alpha value below swatch
        int currentAlpha = PixelCanvas.unpackRGBA(getCurrentColor())[3];
        int currentPercent = (int) ((currentAlpha / 255.0f) * 100);
        String currentAlphaText = String.format("A: %d%%", currentPercent);
        ImGui.textDisabled(currentAlphaText);
        ImGui.endGroup();

        ImGui.sameLine();
        ImGui.dummy(20, 0); // Spacing
        ImGui.sameLine();

        // === Previous Color ===
        ImGui.beginGroup();
        ImGui.text("Previous");

        // Store colors before swap for proper handling
        int currentBeforeSwap = getCurrentColor();
        int previousBeforeSwap = previousColor;

        renderColorSwatch(previousColor, swatchSize, () -> {
            // Swap colors: current becomes previous, previous becomes current
            previousColor = currentBeforeSwap;
            setColor(previousBeforeSwap);
            logger.debug("Swapped colors - Current: #{}, Previous: #{}",
                ColorUtils.toHexString(getCurrentColor()),
                ColorUtils.toHexString(previousColor));
        });
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Click to swap with current");
        }

        // Display alpha value below swatch
        int prevAlpha = PixelCanvas.unpackRGBA(previousColor)[3];
        int prevPercent = (int) ((prevAlpha / 255.0f) * 100);
        String prevAlphaText = String.format("A: %d%%", prevPercent);
        ImGui.textDisabled(prevAlphaText);
        ImGui.endGroup();
    }

    /**
     * Render a single color swatch with checkerboard background for alpha.
     * DRY: Reusable swatch rendering method with professional borders and proper alpha display.
     *
     * @param color packed RGBA color
     * @param size swatch size
     * @param onClick optional click callback (null if not clickable)
     */
    private void renderColorSwatch(int color, float size, Runnable onClick) {
        ImVec2 cursorPos = ImGui.getCursorScreenPos();
        ImDrawList drawList = ImGui.getWindowDrawList();

        // Draw checkerboard background for alpha preview
        drawCheckerboard(drawList, cursorPos.x, cursorPos.y, size, size, 8);

        // Draw color with proper alpha over checkerboard
        // Convert from PixelCanvas RGBA format to ImGui ABGR format
        int[] rgba = PixelCanvas.unpackRGBA(color);
        int colorABGR = (rgba[3] << 24) | (rgba[2] << 16) | (rgba[1] << 8) | rgba[0];

        // Draw the color rectangle with alpha support
        drawList.addRectFilled(
            cursorPos.x, cursorPos.y,
            cursorPos.x + size, cursorPos.y + size,
            colorABGR
        );

        // Draw professional border on top
        drawList.addRect(
            cursorPos.x, cursorPos.y,
            cursorPos.x + size, cursorPos.y + size,
            0xFF666666, 0f, 0, 2.0f
        );

        // Invisible button for interaction
        ImGui.setCursorScreenPos(cursorPos.x, cursorPos.y);
        ImGui.invisibleButton("##swatch_" + color, size, size);

        if (ImGui.isItemClicked(0)) {
            if (onClick != null) {
                onClick.run();
            }
        }

        // Show detailed tooltip on hover
        if (ImGui.isItemHovered()) {
            String hex = ColorUtils.toHexString(color);
            int alphaPercent = (int) ((rgba[3] / 255.0f) * 100);
            ImGui.setTooltip("#" + hex + "\nAlpha: " + rgba[3] + " (" + alphaPercent + "%)");
        }
    }

    // ================================
    // HSV Color Wheel Rendering
    // ================================

    /**
     * Render HSV color wheel with saturation/value picking.
     * Professional appearance with custom rainbow gradient and polished layout.
     */
    private void renderHSVColorWheel() {
        ImGui.text("Color");
        ImGui.spacing();
        ImGui.spacing(); // Extra spacing for breathing room

        float pickerSize = 180f;
        float sliderWidth = 24f; // Slightly wider for better visibility
        float spacing = 15f; // Increased spacing

        ImDrawList drawList = ImGui.getWindowDrawList();
        ImVec2 cursorStart = ImGui.getCursorScreenPos();

        // Draw subtle background panel for grouping
        float panelWidth = pickerSize + spacing + sliderWidth + 20f;
        float panelHeight = pickerSize + 10f;
        drawList.addRectFilled(
            cursorStart.x - 5, cursorStart.y - 5,
            cursorStart.x + panelWidth + 5, cursorStart.y + panelHeight + 5,
            0x18FFFFFF, 4.0f // Subtle white with rounded corners
        );

        // === SV Picker (LEFT SIDE) ===
        ImVec2 svPos = ImGui.getCursorScreenPos();

        // Draw subtle shadow behind SV square
        float shadowOffset = 3f;
        drawList.addRectFilled(
            svPos.x + shadowOffset, svPos.y + shadowOffset,
            svPos.x + pickerSize + shadowOffset, svPos.y + pickerSize + shadowOffset,
            0x40000000, 4.0f // Soft shadow with rounded corners
        );

        // Draw SV square gradient
        renderSVSquare(drawList, svPos.x, svPos.y, pickerSize, pickerSize);

        // Draw professional border around SV square with rounded corners
        drawList.addRect(
            svPos.x, svPos.y,
            svPos.x + pickerSize, svPos.y + pickerSize,
            0xFF666666, 4.0f, 0, 2.0f // 4px rounded corners
        );

        // Invisible button for interaction
        ImGui.invisibleButton("##sv_picker", pickerSize, pickerSize);

        if (ImGui.isItemActive() && (ImGui.isMouseDragging(0, 0) || ImGui.isMouseClicked(0))) {
            ImVec2 mousePos = ImGui.getMousePos();
            float x = ColorUtils.clamp(mousePos.x - svPos.x, 0, pickerSize);
            float y = ColorUtils.clamp(mousePos.y - svPos.y, 0, pickerSize);

            saturation = x / pickerSize;
            value = 1.0f - (y / pickerSize);

            updateFromHSV();
        }

        // Draw enhanced picker indicator with glow effect
        float indicatorX = svPos.x + saturation * pickerSize;
        float indicatorY = svPos.y + (1.0f - value) * pickerSize;

        // Soft glow behind indicator
        drawList.addCircleFilled(indicatorX, indicatorY, 8, 0x40FFFFFF, 16);

        // Multi-layer indicator for better visibility
        drawList.addCircleFilled(indicatorX, indicatorY, 7, 0xFF000000); // Black outer
        drawList.addCircleFilled(indicatorX, indicatorY, 6, 0xFFFFFFFF); // White middle
        drawList.addCircle(indicatorX, indicatorY, 5, 0xFF000000, 16, 2.0f); // Black ring
        drawList.addCircle(indicatorX, indicatorY, 3, 0xFFFFFFFF, 16, 1.0f); // White inner ring

        // === HUE SLIDER (RIGHT SIDE) ===
        ImGui.sameLine();
        ImGui.dummy(spacing, 0);
        ImGui.sameLine();

        ImVec2 huePos = ImGui.getCursorScreenPos();

        // Draw subtle shadow behind hue slider
        drawList.addRectFilled(
            huePos.x + shadowOffset, huePos.y + shadowOffset,
            huePos.x + sliderWidth + shadowOffset, huePos.y + pickerSize + shadowOffset,
            0x40000000, 2.0f // Soft shadow with rounded corners
        );

        // Render custom rainbow hue slider
        renderCustomHueSlider(drawList, huePos.x, huePos.y, sliderWidth, pickerSize);
    }

    /**
     * Render custom rainbow gradient hue slider.
     * Professional vertical gradient with smooth color transitions.
     *
     * @param drawList ImGui draw list
     * @param x slider X position
     * @param y slider Y position
     * @param width slider width
     * @param height slider height
     * @return true if hue value changed
     */
    private boolean renderCustomHueSlider(ImDrawList drawList, float x, float y, float width, float height) {
        // Define rainbow gradient colors in ABGR format (0xAABBGGRR)
        // 6 segments for smooth transition matching HSV hue wheel
        int[] rainbowColors = {
            0xFF0000FF, // Red (0°) - RGB(255,0,0)
            0xFF00FFFF, // Yellow (60°) - RGB(255,255,0)
            0xFF00FF00, // Green (120°) - RGB(0,255,0)
            0xFFFFFF00, // Cyan (180°) - RGB(0,255,255)
            0xFFFF0000, // Blue (240°) - RGB(0,0,255)
            0xFFFF00FF, // Magenta (300°) - RGB(255,0,255)
            0xFF0000FF  // Red (360°) - RGB(255,0,0)
        };

        // Draw gradient in segments for smooth transitions
        int segments = rainbowColors.length - 1;
        float segmentHeight = height / segments;

        for (int i = 0; i < segments; i++) {
            float y1 = y + i * segmentHeight;
            float y2 = y + (i + 1) * segmentHeight;

            drawList.addRectFilledMultiColor(
                x, y1, x + width, y2,
                rainbowColors[i], rainbowColors[i],
                rainbowColors[i + 1], rainbowColors[i + 1]
            );
        }

        // Draw professional border
        drawList.addRect(x, y, x + width, y + height, 0xFF666666, 0f, 0, 2.0f);

        // Draw hue position indicator (horizontal line/arrow)
        float indicatorY = y + (hue / 360.0f) * height;
        float arrowSize = 4f;

        // Draw triangle arrow pointing right
        drawList.addTriangleFilled(
            x - arrowSize, indicatorY - arrowSize,
            x - arrowSize, indicatorY + arrowSize,
            x, indicatorY,
            0xFFFFFFFF
        );
        drawList.addTriangle(
            x - arrowSize, indicatorY - arrowSize,
            x - arrowSize, indicatorY + arrowSize,
            x, indicatorY,
            0xFF000000, 1.5f
        );

        // Draw triangle arrow pointing left
        drawList.addTriangleFilled(
            x + width + arrowSize, indicatorY - arrowSize,
            x + width + arrowSize, indicatorY + arrowSize,
            x + width, indicatorY,
            0xFFFFFFFF
        );
        drawList.addTriangle(
            x + width + arrowSize, indicatorY - arrowSize,
            x + width + arrowSize, indicatorY + arrowSize,
            x + width, indicatorY,
            0xFF000000, 1.5f
        );

        // Invisible button for interaction
        ImGui.setCursorScreenPos(x, y);
        ImGui.invisibleButton("##hue_slider", width, height);

        boolean changed = false;
        if (ImGui.isItemActive() && (ImGui.isMouseDragging(0, 0) || ImGui.isMouseClicked(0))) {
            ImVec2 mousePos = ImGui.getMousePos();
            float newY = ColorUtils.clamp(mousePos.y - y, 0, height);
            float newHue = (newY / height) * 360.0f;

            if (Math.abs(newHue - hue) > 0.1f) {
                hue = newHue;
                updateFromHSV();
                changed = true;
            }
        }

        return changed;
    }

    /**
     * Render saturation-value square gradient.
     */
    private void renderSVSquare(ImDrawList drawList, float x, float y, float width, float height) {
        int[] rgb = ColorUtils.hsvToRgb(hue, 1.0f, 1.0f);
        int pureHueColor = 0xFF000000 | (rgb[2] << 16) | (rgb[1] << 8) | rgb[0];

        // Draw base hue rectangle
        drawList.addRectFilled(x, y, x + width, y + height, pureHueColor);

        // Draw white to transparent gradient (left to right) for saturation
        drawList.addRectFilledMultiColor(
            x, y, x + width, y + height,
            0xFFFFFFFF, 0x00FFFFFF,  // Top left -> top right
            0x00FFFFFF, 0xFFFFFFFF   // Bottom right -> bottom left
        );

        // Draw black gradient (bottom to top) for value
        drawList.addRectFilledMultiColor(
            x, y, x + width, y + height,
            0x00000000, 0x00000000,  // Top left -> top right
            0xFF000000, 0xFF000000   // Bottom left -> bottom right
        );
    }

    // ================================
    // RGB Sliders Rendering
    // ================================

    /**
     * Calculate smart context-aware gradient colors for a specific RGB component.
     * Returns gradient that shows actual color range when adjusting one channel.
     *
     * @param component 'R', 'G', or 'B'
     * @return int[2] array with [startColor, endColor] in ABGR format
     */
    private int[] calculateSmartGradient(char component) {
        int r = (int) red.get();
        int g = (int) green.get();
        int b = (int) blue.get();

        int startR, startG, startB;
        int endR, endG, endB;

        switch (component) {
            case 'R':
                // Red slider: keep G and B constant, vary R from 0 to 255
                startR = 0; startG = g; startB = b;
                endR = 255; endG = g; endB = b;
                break;
            case 'G':
                // Green slider: keep R and B constant, vary G from 0 to 255
                startR = r; startG = 0; startB = b;
                endR = r; endG = 255; endB = b;
                break;
            case 'B':
                // Blue slider: keep R and G constant, vary B from 0 to 255
                startR = r; startG = g; startB = 0;
                endR = r; endG = g; endB = 255;
                break;
            default:
                startR = startG = startB = 0;
                endR = endG = endB = 255;
        }

        // Convert to ABGR format (0xAABBGGRR)
        int startColor = 0xFF000000 | (startB << 16) | (startG << 8) | startR;
        int endColor = 0xFF000000 | (endB << 16) | (endG << 8) | endR;

        return new int[]{startColor, endColor};
    }

    /**
     * Render RGB sliders with professional appearance and smart gradients.
     */
    private void renderRGBSliders() {
        ImGui.text("Color");
        ImGui.spacing();
        ImGui.spacing(); // Extra breathing room

        float sliderWidth = 200f; // Wider for easier interaction
        float sliderHeight = 28f; // Taller for better grab area

        ImDrawList drawList = ImGui.getWindowDrawList();
        ImVec2 cursorStart = ImGui.getCursorScreenPos();

        // Calculate total panel dimensions
        float panelWidth = sliderWidth + 80f; // Extra space for labels and values
        float panelHeight = (sliderHeight + 15f) * 3 + 20f; // 3 sliders + spacing

        // Draw subtle background panel for grouping
        drawList.addRectFilled(
            cursorStart.x - 5, cursorStart.y - 5,
            cursorStart.x + panelWidth, cursorStart.y + panelHeight,
            0x18FFFFFF, 4.0f // Subtle white with rounded corners
        );

        // Red slider
        int[] redGradient = calculateSmartGradient('R');
        renderSmartRGBSlider("R", red, sliderWidth, sliderHeight, redGradient[0], redGradient[1]);

        ImGui.spacing();
        ImGui.spacing();

        // Green slider
        int[] greenGradient = calculateSmartGradient('G');
        renderSmartRGBSlider("G", green, sliderWidth, sliderHeight, greenGradient[0], greenGradient[1]);

        ImGui.spacing();
        ImGui.spacing();

        // Blue slider
        int[] blueGradient = calculateSmartGradient('B');
        renderSmartRGBSlider("B", blue, sliderWidth, sliderHeight, blueGradient[0], blueGradient[1]);
    }

    /**
     * Render a professional RGB component slider with smart gradient and visual enhancements.
     * DRY: Reusable component slider rendering with shadows, rounded corners, and custom indicators.
     *
     * @param label component label (R, G, B)
     * @param value ImFloat value to modify
     * @param width slider width
     * @param height slider height
     * @param gradientStart gradient start color (ABGR format)
     * @param gradientEnd gradient end color (ABGR format)
     */
    private void renderSmartRGBSlider(String label, ImFloat value,
                                      float width, float height,
                                      int gradientStart, int gradientEnd) {
        ImDrawList drawList = ImGui.getWindowDrawList();
        ImVec2 labelPos = ImGui.getCursorScreenPos();

        // Render label with value display
        ImGui.text(label + ":");
        ImGui.sameLine();
        ImGui.dummy(5, 0); // Small spacing
        ImGui.sameLine();

        // Display numeric value (0-255)
        String valueText = String.format("%3d", (int) value.get());
        ImGui.textDisabled(valueText);

        ImGui.sameLine();
        ImGui.dummy(10, 0); // Spacing before slider
        ImGui.sameLine();

        ImVec2 cursorPos = ImGui.getCursorScreenPos();
        float shadowOffset = 2f;

        // Draw subtle shadow behind slider
        drawList.addRectFilled(
            cursorPos.x + shadowOffset, cursorPos.y + shadowOffset,
            cursorPos.x + width + shadowOffset, cursorPos.y + height + shadowOffset,
            0x30000000, 3.0f // Soft shadow with rounded corners
        );

        // Draw gradient background
        drawList.addRectFilledMultiColor(
            cursorPos.x, cursorPos.y,
            cursorPos.x + width, cursorPos.y + height,
            gradientStart, gradientEnd,
            gradientEnd, gradientStart
        );

        // Draw professional border with rounded corners
        drawList.addRect(
            cursorPos.x, cursorPos.y,
            cursorPos.x + width, cursorPos.y + height,
            0xFF666666, 3.0f, 0, 2.0f // 3px rounded, 2px thick
        );

        // Calculate indicator position
        float normalizedValue = value.get() / 255.0f;
        float indicatorX = cursorPos.x + normalizedValue * width;
        float indicatorY = cursorPos.y + height / 2;

        // Draw custom position indicator (vertical line with triangles)
        float lineWidth = 2.5f;
        float triangleSize = 5f;

        // Vertical line
        drawList.addLine(
            indicatorX, cursorPos.y,
            indicatorX, cursorPos.y + height,
            0xFFFFFFFF, lineWidth
        );

        // Top triangle
        drawList.addTriangleFilled(
            indicatorX - triangleSize, cursorPos.y - triangleSize,
            indicatorX + triangleSize, cursorPos.y - triangleSize,
            indicatorX, cursorPos.y,
            0xFFFFFFFF
        );
        drawList.addTriangle(
            indicatorX - triangleSize, cursorPos.y - triangleSize,
            indicatorX + triangleSize, cursorPos.y - triangleSize,
            indicatorX, cursorPos.y,
            0xFF000000, 1.5f
        );

        // Bottom triangle
        drawList.addTriangleFilled(
            indicatorX - triangleSize, cursorPos.y + height + triangleSize,
            indicatorX + triangleSize, cursorPos.y + height + triangleSize,
            indicatorX, cursorPos.y + height,
            0xFFFFFFFF
        );
        drawList.addTriangle(
            indicatorX - triangleSize, cursorPos.y + height + triangleSize,
            indicatorX + triangleSize, cursorPos.y + height + triangleSize,
            indicatorX, cursorPos.y + height,
            0xFF000000, 1.5f
        );

        // Invisible button for interaction
        ImGui.setCursorScreenPos(cursorPos.x, cursorPos.y);
        ImGui.invisibleButton("##" + label + "_slider", width, height);

        if (ImGui.isItemActive() && (ImGui.isMouseDragging(0, 0) || ImGui.isMouseClicked(0))) {
            ImVec2 mousePos = ImGui.getMousePos();
            float newX = ColorUtils.clamp(mousePos.x - cursorPos.x, 0, width);
            float newValue = (newX / width) * 255.0f;

            if (Math.abs(newValue - value.get()) > 0.1f) {
                value.set(newValue);
                updateFromRGB();
            }
        }

        // Tooltip on hover
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip(label + " = " + (int) value.get());
        }
    }

    /**
     * Render professional alpha slider with checkerboard preview, custom indicators, and value display.
     * Matches the style and quality of RGB sliders for visual consistency.
     */
    private void renderAlphaSlider() {
        ImDrawList drawList = ImGui.getWindowDrawList();

        // Render label with value display
        ImGui.text("Alpha:");
        ImGui.sameLine();
        ImGui.dummy(5, 0);
        ImGui.sameLine();

        // Display numeric value (0-255) and percentage
        int alphaValue = alpha;
        int alphaPercent = (int) ((alphaValue / 255.0f) * 100);
        String valueText = String.format("%3d (%3d%%)", alphaValue, alphaPercent);
        ImGui.textDisabled(valueText);

        ImGui.sameLine();
        ImGui.dummy(10, 0);
        ImGui.sameLine();

        float sliderWidth = 200f; // Match RGB slider width
        float sliderHeight = 28f; // Match RGB slider height
        float shadowOffset = 2f;

        ImVec2 cursorPos = ImGui.getCursorScreenPos();

        // Draw subtle shadow behind slider
        drawList.addRectFilled(
            cursorPos.x + shadowOffset, cursorPos.y + shadowOffset,
            cursorPos.x + sliderWidth + shadowOffset, cursorPos.y + sliderHeight + shadowOffset,
            0x30000000, 3.0f // Soft shadow with rounded corners
        );

        // Draw checkerboard background for transparency preview
        drawCheckerboard(drawList, cursorPos.x, cursorPos.y, sliderWidth, sliderHeight, 8);

        // Draw alpha gradient (transparent to opaque) in ABGR format
        int currentColor = getCurrentColor();
        int[] rgb = PixelCanvas.unpackRGBA(currentColor);

        // ABGR format: 0xAABBGGRR
        int colorTransparent = 0x00000000 | (rgb[2] << 16) | (rgb[1] << 8) | rgb[0];
        int colorOpaque = 0xFF000000 | (rgb[2] << 16) | (rgb[1] << 8) | rgb[0];

        drawList.addRectFilledMultiColor(
            cursorPos.x, cursorPos.y,
            cursorPos.x + sliderWidth, cursorPos.y + sliderHeight,
            colorTransparent, colorOpaque,
            colorOpaque, colorTransparent
        );

        // Draw professional border with rounded corners
        drawList.addRect(
            cursorPos.x, cursorPos.y,
            cursorPos.x + sliderWidth, cursorPos.y + sliderHeight,
            0xFF666666, 3.0f, 0, 2.0f // 3px rounded, 2px thick
        );

        // Calculate indicator position
        float normalizedAlpha = alpha / 255.0f;
        float indicatorX = cursorPos.x + normalizedAlpha * sliderWidth;

        // Draw custom position indicator (vertical line with triangles)
        float lineWidth = 2.5f;
        float triangleSize = 5f;

        // Vertical line
        drawList.addLine(
            indicatorX, cursorPos.y,
            indicatorX, cursorPos.y + sliderHeight,
            0xFFFFFFFF, lineWidth
        );

        // Top triangle
        drawList.addTriangleFilled(
            indicatorX - triangleSize, cursorPos.y - triangleSize,
            indicatorX + triangleSize, cursorPos.y - triangleSize,
            indicatorX, cursorPos.y,
            0xFFFFFFFF
        );
        drawList.addTriangle(
            indicatorX - triangleSize, cursorPos.y - triangleSize,
            indicatorX + triangleSize, cursorPos.y - triangleSize,
            indicatorX, cursorPos.y,
            0xFF000000, 1.5f
        );

        // Bottom triangle
        drawList.addTriangleFilled(
            indicatorX - triangleSize, cursorPos.y + sliderHeight + triangleSize,
            indicatorX + triangleSize, cursorPos.y + sliderHeight + triangleSize,
            indicatorX, cursorPos.y + sliderHeight,
            0xFFFFFFFF
        );
        drawList.addTriangle(
            indicatorX - triangleSize, cursorPos.y + sliderHeight + triangleSize,
            indicatorX + triangleSize, cursorPos.y + sliderHeight + triangleSize,
            indicatorX, cursorPos.y + sliderHeight,
            0xFF000000, 1.5f
        );

        // Invisible button for interaction
        ImGui.setCursorScreenPos(cursorPos.x, cursorPos.y);
        ImGui.invisibleButton("##alpha_slider", sliderWidth, sliderHeight);

        if (ImGui.isItemActive() && (ImGui.isMouseDragging(0, 0) || ImGui.isMouseClicked(0))) {
            ImVec2 mousePos = ImGui.getMousePos();
            float newX = ColorUtils.clamp(mousePos.x - cursorPos.x, 0, sliderWidth);
            float newAlpha = (newX / sliderWidth) * 255.0f;

            if (Math.abs(newAlpha - alpha) > 0.1f) {
                alpha = (int) newAlpha;
                alphaFloat.set(alpha);
                updateFromHSV(); // Update all color representations
            }
        }

        // Tooltip on hover
        if (ImGui.isItemHovered()) {
            int percent = (int) ((alpha / 255.0f) * 100);
            ImGui.setTooltip("Alpha = " + alpha + " (" + percent + "%)");
        }
    }

    /**
     * Render hex color input.
     */
    private void renderHexInput() {
        ImGui.text("Hex");
        ImGui.sameLine();
        ImGui.pushItemWidth(120);

        if (ImGui.inputText("##hex", hexInput, imgui.flag.ImGuiInputTextFlags.CharsHexadecimal |
                                                imgui.flag.ImGuiInputTextFlags.CharsUppercase)) {
            updateFromHex();
        }

        ImGui.popItemWidth();

        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Format: RRGGBBAA (e.g., FF8800FF)");
        }
    }

    /**
     * Render color history grid.
     */
    private void renderColorHistoryGrid() {
        float swatchSize = 40f;
        float padding = 4f;
        int columns = 5;

        for (int i = 0; i < colorHistory.size(); i++) {
            int historyColor = colorHistory.get(i);

            renderColorSwatch(historyColor, swatchSize, () -> {
                // Set the color and move it to front of history
                setColor(historyColor);
                addToHistory(historyColor); // Move to front
            });

            // Layout in grid
            if ((i + 1) % columns != 0 && i < colorHistory.size() - 1) {
                ImGui.sameLine();
                ImGui.dummy(padding, 0);
                ImGui.sameLine();
            }
        }
    }

    // ================================
    // Helper Methods
    // ================================

    /**
     * Draw checkerboard pattern for alpha preview.
     */
    private void drawCheckerboard(ImDrawList drawList, float x, float y, float width, float height, float cellSize) {
        int lightColor = 0xFFCCCCCC;
        int darkColor = 0xFF999999;

        int cols = (int)Math.ceil(width / cellSize);
        int rows = (int)Math.ceil(height / cellSize);

        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                boolean isLight = (row + col) % 2 == 0;
                int color = isLight ? lightColor : darkColor;

                float x1 = x + col * cellSize;
                float y1 = y + row * cellSize;
                float x2 = Math.min(x1 + cellSize, x + width);
                float y2 = Math.min(y1 + cellSize, y + height);

                drawList.addRectFilled(x1, y1, x2, y2, color);
            }
        }
    }

    /**
     * Update all color representations from HSV values.
     */
    private void updateFromHSV() {
        int currentColor = ColorUtils.hsvToPackedColor(hue, saturation, value, alpha);

        // Update hex
        hexInput.set(ColorUtils.toHexString(currentColor));

        // Update legacy RGB sliders (for backward compatibility)
        int[] rgba = PixelCanvas.unpackRGBA(currentColor);
        red.set(rgba[0]);
        green.set(rgba[1]);
        blue.set(rgba[2]);
        alphaFloat.set(rgba[3]);
    }

    /**
     * Update all color representations from hex input.
     */
    private void updateFromHex() {
        int color = ColorUtils.fromHexString(hexInput.get());

        // Update HSV
        float[] hsva = ColorUtils.packedColorToHsv(color);
        hue = hsva[0];
        saturation = hsva[1];
        value = hsva[2];
        alpha = (int)hsva[3];

        // Update RGB sliders
        int[] rgba = PixelCanvas.unpackRGBA(color);
        red.set(rgba[0]);
        green.set(rgba[1]);
        blue.set(rgba[2]);
        alphaFloat.set(rgba[3]);
    }

    /**
     * Update all color representations from RGB sliders.
     */
    private void updateFromRGB() {
        int r = (int)red.get();
        int g = (int)green.get();
        int b = (int)blue.get();
        int a = (int)alphaFloat.get();

        // Clamp values
        r = ColorUtils.clamp(r, 0, 255);
        g = ColorUtils.clamp(g, 0, 255);
        b = ColorUtils.clamp(b, 0, 255);
        a = ColorUtils.clamp(a, 0, 255);

        // Update HSV from RGB
        float[] hsv = ColorUtils.rgbToHsv(r, g, b);
        hue = hsv[0];
        saturation = hsv[1];
        value = hsv[2];
        alpha = a;

        // Update hex
        int color = PixelCanvas.packRGBA(r, g, b, a);
        hexInput.set(ColorUtils.toHexString(color));

        // Sync alphaFloat
        alphaFloat.set(a);
    }

    /**
     * Add color to history (avoiding duplicates).
     */
    private void addToHistory(int color) {
        // Remove existing occurrence
        colorHistory.remove((Integer)color);

        // Add to front
        colorHistory.add(0, color);

        // Trim to max size
        while (colorHistory.size() > MAX_HISTORY_SIZE) {
            colorHistory.remove(colorHistory.size() - 1);
        }
    }

    // ================================
    // Public API (Backward Compatible)
    // ================================

    /**
     * Get current color as packed RGBA int.
     * @return packed RGBA color
     */
    public int getCurrentColor() {
        return ColorUtils.hsvToPackedColor(hue, saturation, value, alpha);
    }

    /**
     * Set current color.
     * Does NOT update previousColor - only addColorToHistory() does that when color is used on canvas.
     * Does NOT automatically add to history - use addColorToHistory() for that.
     *
     * @param color packed RGBA color
     */
    public void setColor(int color) {
        // Update HSV from packed color
        float[] hsva = ColorUtils.packedColorToHsv(color);
        hue = hsva[0];
        saturation = hsva[1];
        value = hsva[2];
        alpha = (int)hsva[3];

        // Update hex
        hexInput.set(ColorUtils.toHexString(color));

        // Update RGB sliders
        int[] rgba = PixelCanvas.unpackRGBA(color);
        red.set(rgba[0]);
        green.set(rgba[1]);
        blue.set(rgba[2]);
        alphaFloat.set(rgba[3]);
    }

    /**
     * Add a color to the history and update previous color tracking.
     * Should be called when a color is actually painted/applied on the canvas.
     *
     * Flow:
     * 1. User selects color (current changes, previous unchanged)
     * 2. User paints → this method called
     * 3. If color is DIFFERENT from last painted:
     *    - previousColor = lastPaintedColor (shows last different color)
     *    - lastPaintedColor = current (track what we just painted)
     * 4. If color is SAME as last painted:
     *    - Keep both unchanged (previous stays as last different color)
     *
     * Example:
     * - Paint Red: previous=Black, lastPainted=Red
     * - Paint Red again: previous=Black (unchanged!), lastPainted=Red
     * - Select Blue, Paint Blue: previous=Red, lastPainted=Blue
     * - Paint Blue again: previous=Red (unchanged!), lastPainted=Blue
     *
     * Public API for canvas tools to register color usage.
     *
     * @param color packed RGBA color to add
     */
    public void addColorToHistory(int color) {
        int currentColor = getCurrentColor();

        // Only update previous color if we're painting a DIFFERENT color
        if (currentColor != lastPaintedColor) {
            // Previous = the color we painted BEFORE this one
            previousColor = lastPaintedColor;

            // Update last painted to current color
            lastPaintedColor = currentColor;

            logger.debug("Color painted on canvas (NEW): #{} | Previous: #{} | Last painted: #{}",
                       ColorUtils.toHexString(color),
                       ColorUtils.toHexString(previousColor),
                       ColorUtils.toHexString(lastPaintedColor));
        } else {
            logger.debug("Color painted on canvas (SAME): #{} | Previous unchanged: #{}",
                       ColorUtils.toHexString(color),
                       ColorUtils.toHexString(previousColor));
        }

        addToHistory(color);
    }

    /**
     * Get color history for persistence.
     * @return list of color history
     */
    public List<Integer> getColorHistory() {
        return new ArrayList<>(colorHistory);
    }

    /**
     * Set color history from persistence.
     * @param history list of colors
     */
    public void setColorHistory(List<Integer> history) {
        colorHistory.clear();
        if (history != null) {
            colorHistory.addAll(history);
            // Ensure max size
            while (colorHistory.size() > MAX_HISTORY_SIZE) {
                colorHistory.remove(colorHistory.size() - 1);
            }
        }
    }
}
