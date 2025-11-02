package com.openmason.ui.components.textureCreator.panels;

import com.openmason.ui.components.textureCreator.icons.TextureToolIconManager;
import com.openmason.ui.components.textureCreator.selection.SelectionManager;
import com.openmason.ui.components.textureCreator.tools.*;
import com.openmason.ui.components.textureCreator.tools.grabber.GrabberTool;
import com.openmason.ui.components.textureCreator.tools.move.MoveToolController;
import com.openmason.ui.components.textureCreator.tools.paste.PasteTool;
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
    private PasteTool pasteToolInstance; // Reference to paste tool for programmatic activation
    private ShapeTool shapeToolInstance; // Reference to shape tool for configuration

    /**
     * Create toolbar panel with all available tools.
     */
    public ToolbarPanel() {
        this.tools = new ArrayList<>();
        this.iconManager = TextureToolIconManager.getInstance();

        // Initialize all tools - Grabber is first (default tool), Move tool is second
        tools.add(new GrabberTool());

        // Store move tool reference for later configuration
        moveToolInstance = new MoveToolController();
        tools.add(moveToolInstance);

        tools.add(new PencilTool());
        tools.add(new EraserTool());
        tools.add(new FillTool());
        tools.add(new ColorPickerTool());
        tools.add(new LineTool());
        shapeToolInstance = new ShapeTool();
        tools.add(shapeToolInstance);
        tools.add(new RectangleSelectionTool());
        selectionBrushTool = new SelectionBrushTool();
        tools.add(selectionBrushTool);

        // Create paste tool instance for programmatic activation (Ctrl+V)
        // Note: NOT added to tools list - it's activated automatically, not manually selected
        pasteToolInstance = new PasteTool();

        // Set default tool
        currentTool = tools.get(0); // Grabber

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
     * Set the GLFW window handle for mouse capture functionality.
     * This enables infinite dragging in the move tool.
     * @param windowHandle The GLFW window handle
     */
    public void setWindowHandle(long windowHandle) {
        if (moveToolInstance != null) {
            moveToolInstance.setWindowHandle(windowHandle);
            logger.debug("Move tool configured with window handle for mouse capture");
        }
    }

    /**
     * Configures tools with preferences.
     * This allows tools to access user settings.
     * @param preferences The TextureCreatorPreferences to use
     */
    public void setPreferences(com.openmason.ui.components.textureCreator.TextureCreatorPreferences preferences) {
        if (moveToolInstance != null) {
            moveToolInstance.setPreferences(preferences);
            logger.debug("Move tool configured with preferences");
        }
        if (shapeToolInstance != null) {
            shapeToolInstance.setPreferences(preferences);
            logger.debug("Shape tool configured with preferences");
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

            // Get icon texture ID - for ShapeTool, use current shape variant
            int textureId;
            if (tool instanceof ShapeTool) {
                ShapeTool shapeTool = (ShapeTool) tool;
                String iconKey = "Shapes:" + shapeTool.getCurrentShape().getDisplayName();
                textureId = iconManager.getIconTexture(iconKey);
            } else {
                textureId = iconManager.getIconTexture(tool.getName());
            }

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

            // Pop selected style BEFORE handling right-click popup to prevent style bleeding
            if (isSelected) {
                ImGui.popStyleColor();
            }

            // Handle right-click for ShapeTool - show variant menu
            if (tool instanceof ShapeTool && ImGui.isItemClicked(1)) {
                ImGui.openPopup("##ShapeVariantPopup");
            }

            if (tool instanceof ShapeTool && ImGui.beginPopup("##ShapeVariantPopup")) {
                ShapeTool shapeTool = (ShapeTool) tool;
                ShapeTool.ShapeType currentShapeType = shapeTool.getCurrentShape();

                // Rectangle option
                int rectIconId = iconManager.getIconTexture("Shapes:Rectangle");
                if (rectIconId != -1) {
                    boolean isRectSelected = (currentShapeType == ShapeTool.ShapeType.RECTANGLE);
                    if (isRectSelected) {
                        ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button, 0.3f, 0.5f, 0.7f, 1.0f);
                    }

                    if (ImGui.imageButton(rectIconId, iconDisplaySize, iconDisplaySize)) {
                        shapeTool.setCurrentShape(ShapeTool.ShapeType.RECTANGLE);
                        ImGui.closeCurrentPopup();
                    }

                    if (isRectSelected) {
                        ImGui.popStyleColor();
                    }

                    if (ImGui.isItemHovered()) {
                        ImGui.setTooltip("Rectangle");
                    }
                }

                // Ellipse option
                int ellipseIconId = iconManager.getIconTexture("Shapes:Ellipse");
                if (ellipseIconId != -1) {
                    boolean isEllipseSelected = (currentShapeType == ShapeTool.ShapeType.ELLIPSE);
                    if (isEllipseSelected) {
                        ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button, 0.3f, 0.5f, 0.7f, 1.0f);
                    }

                    if (ImGui.imageButton(ellipseIconId, iconDisplaySize, iconDisplaySize)) {
                        shapeTool.setCurrentShape(ShapeTool.ShapeType.ELLIPSE);
                        ImGui.closeCurrentPopup();
                    }

                    if (isEllipseSelected) {
                        ImGui.popStyleColor();
                    }

                    if (ImGui.isItemHovered()) {
                        ImGui.setTooltip("Ellipse");
                    }
                }

                // Triangle option
                int triangleIconId = iconManager.getIconTexture("Shapes:Triangle");
                if (triangleIconId != -1) {
                    boolean isTriangleSelected = (currentShapeType == ShapeTool.ShapeType.TRIANGLE);
                    if (isTriangleSelected) {
                        ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button, 0.3f, 0.5f, 0.7f, 1.0f);
                    }

                    if (ImGui.imageButton(triangleIconId, iconDisplaySize, iconDisplaySize)) {
                        shapeTool.setCurrentShape(ShapeTool.ShapeType.TRIANGLE);
                        ImGui.closeCurrentPopup();
                    }

                    if (isTriangleSelected) {
                        ImGui.popStyleColor();
                    }

                    if (ImGui.isItemHovered()) {
                        ImGui.setTooltip("Triangle");
                    }
                }

                ImGui.endPopup();
            }

            // Tooltip showing tool name
            if (ImGui.isItemHovered()) {
                if (tool instanceof ShapeTool) {
                    ShapeTool shapeTool = (ShapeTool) tool;
                    ImGui.setTooltip(shapeTool.getCurrentShape().getDisplayName() + " (right-click for more shapes)");
                } else {
                    ImGui.setTooltip(tool.getName());
                }
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

    /**
     * Get the paste tool instance.
     * Used for programmatic activation (Ctrl+V).
     * @return paste tool instance
     */
    public PasteTool getPasteTool() {
        return pasteToolInstance;
    }

    /**
     * Get the move tool instance.
     * Used for paste operations (reuses move tool for DRY principle).
     * @return move tool instance
     */
    public MoveToolController getMoveToolInstance() {
        return moveToolInstance;
    }
}
