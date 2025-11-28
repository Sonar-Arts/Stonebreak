package com.openmason.ui.textureCreator;

import java.util.HashMap;
import java.util.Map;

/**
 * State management for symmetry/mirror mode in the texture editor.
 *
 * Follows SOLID principles:
 * - Single Responsibility: Only manages symmetry state
 * - Immutable where possible for thread safety
 *
 * @author Open Mason Team
 */
public class SymmetryState {

    public enum SymmetryMode {
        NONE("None", "No symmetry"),
        HORIZONTAL("Horizontal", "Mirror across horizontal axis (top/bottom)"),
        VERTICAL("Vertical", "Mirror across vertical axis (left/right)"),
        QUADRANT("Quadrant", "Mirror in all four quadrants");

        private final String displayName;
        private final String description;

        SymmetryMode(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
    }

    // State fields
    private SymmetryMode mode;
    private int axisOffsetX; // Offset from center in pixels (can be negative)
    private int axisOffsetY; // Offset from center in pixels (can be negative)
    private boolean showAxisLines; // Show visual axis indicators on canvas
    private final Map<String, Boolean> toolEnabledMap; // Per-tool symmetry enable/disable

    /**
     * Create new symmetry state with defaults.
     */
    public SymmetryState() {
        this.mode = SymmetryMode.NONE;
        this.axisOffsetX = 0;
        this.axisOffsetY = 0;
        this.showAxisLines = true;
        this.toolEnabledMap = new HashMap<>();
        initializeDefaultToolSettings();
    }

    /**
     * Initialize default tool settings.
     * By default, enable symmetry for pixel-based drawing tools.
     */
    private void initializeDefaultToolSettings() {
        // Enable for pixel-based tools by default
        toolEnabledMap.put("PencilTool", true);
        toolEnabledMap.put("BrushTool", true);
        toolEnabledMap.put("EraserTool", true);

        // Disable for other tools by default
        toolEnabledMap.put("FillTool", false);
        toolEnabledMap.put("LineTool", false);
        toolEnabledMap.put("ShapeTool", false);
        toolEnabledMap.put("ColorPickerTool", false);
        toolEnabledMap.put("RectangleSelectionTool", false);
        toolEnabledMap.put("SelectionBrushTool", false);
    }

    // Getters and setters

    public SymmetryMode getMode() {
        return mode;
    }

    public void setMode(SymmetryMode mode) {
        this.mode = mode;
    }

    public int getAxisOffsetX() {
        return axisOffsetX;
    }

    public void setAxisOffsetX(int axisOffsetX) {
        this.axisOffsetX = axisOffsetX;
    }

    public int getAxisOffsetY() {
        return axisOffsetY;
    }

    public void setAxisOffsetY(int axisOffsetY) {
        this.axisOffsetY = axisOffsetY;
    }

    public boolean isShowAxisLines() {
        return showAxisLines;
    }

    public void setShowAxisLines(boolean showAxisLines) {
        this.showAxisLines = showAxisLines;
    }

    /**
     * Check if symmetry is enabled for a specific tool.
     * @param toolClassName Simple class name of the tool (e.g., "PencilTool")
     * @return true if symmetry is enabled for this tool
     */
    public boolean isEnabledForTool(String toolClassName) {
        return toolEnabledMap.getOrDefault(toolClassName, false);
    }

    /**
     * Set whether symmetry is enabled for a specific tool.
     * @param toolClassName Simple class name of the tool
     * @param enabled true to enable symmetry for this tool
     */
    public void setEnabledForTool(String toolClassName, boolean enabled) {
        toolEnabledMap.put(toolClassName, enabled);
    }

    /**
     * Check if symmetry is currently active (mode != NONE).
     * @return true if symmetry mode is active
     */
    public boolean isActive() {
        return mode != SymmetryMode.NONE;
    }

    /**
     * Reset to default settings.
     */
    public void resetToDefaults() {
        this.mode = SymmetryMode.NONE;
        this.axisOffsetX = 0;
        this.axisOffsetY = 0;
        this.showAxisLines = true;
        initializeDefaultToolSettings();
    }
}
