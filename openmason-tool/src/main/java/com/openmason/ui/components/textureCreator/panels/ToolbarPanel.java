package com.openmason.ui.components.textureCreator.panels;

import com.openmason.ui.components.textureCreator.tools.*;
import imgui.ImGui;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Toolbar panel renderer - displays tool selection buttons.
 *
 * Follows SOLID principles - Single Responsibility: renders toolbar UI only.
 *
 * @author Open Mason Team
 */
public class ToolbarPanel {

    private static final Logger logger = LoggerFactory.getLogger(ToolbarPanel.class);

    private final List<DrawingTool> tools;
    private DrawingTool currentTool;

    /**
     * Create toolbar panel with all available tools.
     */
    public ToolbarPanel() {
        this.tools = new ArrayList<>();

        // Initialize all tools
        tools.add(new PencilTool());
        tools.add(new EraserTool());
        tools.add(new FillTool());
        tools.add(new ColorPickerTool());
        tools.add(new LineTool());

        // Set default tool
        currentTool = tools.get(0); // Pencil

        logger.debug("Toolbar panel created with {} tools", tools.size());
    }

    /**
     * Render the toolbar panel.
     */
    public void render() {
        ImGui.beginChild("##toolbar_panel", 0, 0, false);

        ImGui.text("Tools");
        ImGui.separator();

        // Render tool buttons
        for (int i = 0; i < tools.size(); i++) {
            DrawingTool tool = tools.get(i);
            boolean isSelected = (tool == currentTool);

            // Highlight selected tool
            if (isSelected) {
                ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button, 0.3f, 0.5f, 0.7f, 1.0f);
            }

            // Tool button
            if (ImGui.button(tool.getName() + "##tool_" + i, 120, 30)) {
                setCurrentTool(tool);
            }

            if (isSelected) {
                ImGui.popStyleColor();
            }

            // Tooltip
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip(tool.getDescription());
            }
        }

        ImGui.endChild();
    }

    /**
     * Set the current tool.
     * @param tool tool to activate
     */
    public void setCurrentTool(DrawingTool tool) {
        if (currentTool != null) {
            currentTool.reset();
        }
        currentTool = tool;
        logger.debug("Tool changed to: {}", tool.getName());
    }

    /**
     * Get the current tool.
     * @return current tool
     */
    public DrawingTool getCurrentTool() {
        return currentTool;
    }

    /**
     * Get all available tools.
     * @return list of tools
     */
    public List<DrawingTool> getTools() {
        return tools;
    }

    /**
     * Get tool by index.
     * @param index tool index
     * @return tool at index
     */
    public DrawingTool getTool(int index) {
        if (index < 0 || index >= tools.size()) {
            return null;
        }
        return tools.get(index);
    }
}
