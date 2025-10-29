package com.openmason.ui.components.textureCreator.panels;

import com.openmason.ui.components.textureCreator.icons.TextureToolIconManager;
import com.openmason.ui.components.textureCreator.selection.SelectionManager;
import com.openmason.ui.components.textureCreator.tools.*;
import com.openmason.ui.components.textureCreator.tools.move.MoveToolController;
import imgui.ImGui;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Toolbar panel renderer - displays tool selection buttons with SVG icons.
 *
 * Follows SOLID principles - Single Responsibility: renders toolbar UI only.
 * Now supports SelectionManager injection for move tool integration.
 *
 * @author Open Mason Team
 */
public class ToolbarPanel {

    private static final Logger logger = LoggerFactory.getLogger(ToolbarPanel.class);

    private final List<DrawingTool> tools;
    private DrawingTool currentTool;
    private final TextureToolIconManager iconManager;
    private MoveToolController moveToolInstance; // Reference to move tool for configuration
    private SelectionBrushTool selectionBrushTool; // Reference to brush tool for configuration

    /**
     * Create toolbar panel with all available tools.
     */
    public ToolbarPanel() {
        this.tools = new ArrayList<>();
        this.iconManager = TextureToolIconManager.getInstance();

        // Initialize all tools
        tools.add(new PencilTool());
        tools.add(new EraserTool());
        tools.add(new FillTool());
        tools.add(new ColorPickerTool());
        tools.add(new LineTool());
        tools.add(new RectangleSelectionTool());
        selectionBrushTool = new SelectionBrushTool();
        tools.add(selectionBrushTool);

        // Store move tool reference for later configuration
        moveToolInstance = new MoveToolController();
        tools.add(moveToolInstance);

        // Set default tool
        currentTool = tools.get(0); // Pencil

        logger.debug("Toolbar panel created with {} tools", tools.size());
    }

    /**
     * Configures the move tool with the SelectionManager.
     * Should be called after TextureCreatorState is initialized.
     * @param selectionManager The SelectionManager to use
     */
    public void setSelectionManager(SelectionManager selectionManager) {
        if (moveToolInstance != null) {
            moveToolInstance.setSelectionManager(selectionManager);
            logger.debug("Move tool configured with SelectionManager");
        }
        if (selectionBrushTool != null) {
            selectionBrushTool.setSelectionManager(selectionManager);
            logger.debug("Selection brush configured with SelectionManager");
        }
    }

    /**
     * Render the toolbar panel.
     */
    public void render() {
        ImGui.beginChild("##toolbar_panel", 0, 0, false);

        ImGui.text("Tools");
        ImGui.separator();

        // Icon rendering configuration
        float iconDisplaySize = 20.0f; // Render icons at 20x20 (smaller than 32x32)
        float buttonPadding = 4.0f;    // Padding around icon

        // Add spacing between buttons
        ImGui.spacing();

        for (int i = 0; i < tools.size(); i++) {
            DrawingTool tool = tools.get(i);
            boolean isSelected = (tool == currentTool);
            int textureId = iconManager.getIconTexture(tool.getName());

            // Highlight selected tool
            if (isSelected) {
                ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button, 0.3f, 0.5f, 0.7f, 1.0f);
            }

            // Add padding to button for centered icon
            ImGui.pushStyleVar(imgui.flag.ImGuiStyleVar.FramePadding, buttonPadding, buttonPadding);

            // Tool icon button
            boolean clicked;
            if (textureId != -1) {
                // Use icon button with smaller display size
                clicked = ImGui.imageButton(textureId, iconDisplaySize, iconDisplaySize);
            } else {
                // Fallback to text button if icon not loaded
                ImGui.popStyleVar(); // Remove padding for text button
                clicked = ImGui.button(tool.getName() + "##tool_" + i, iconDisplaySize + buttonPadding * 2, iconDisplaySize + buttonPadding * 2);
                ImGui.pushStyleVar(imgui.flag.ImGuiStyleVar.FramePadding, buttonPadding, buttonPadding); // Re-apply for consistency
            }

            ImGui.popStyleVar(); // Pop frame padding

            if (clicked) {
                setCurrentTool(tool);
            }

            if (isSelected) {
                ImGui.popStyleColor();
            }

            // Tooltip showing tool name
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip(tool.getName());
            }

            // Add small spacing between buttons
            ImGui.spacing();
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
