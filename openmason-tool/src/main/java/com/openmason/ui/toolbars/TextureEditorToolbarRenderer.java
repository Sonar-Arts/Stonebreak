package com.openmason.ui.toolbars;

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
 * Texture editor toolbar renderer - displays tool selection buttons with SVG icons.
 * Extends BaseToolbarRenderer for consistent styling and DRY principles.
 * Follows SOLID principles - Single Responsibility: renders toolbar UI only.
 *
 * @author Open Mason Team
 */
public class TextureEditorToolbarRenderer extends BaseToolbarRenderer {

    private static final Logger logger = LoggerFactory.getLogger(TextureEditorToolbarRenderer.class);

    private final List<DrawingTool> tools;
    private DrawingTool currentTool;
    private final TextureToolIconManager iconManager;
    private MoveToolController moveToolInstance; // Reference to move tool for configuration
    private SelectionBrushTool selectionBrushTool; // Reference to brush tool for configuration
    private PasteTool pasteToolInstance; // Reference to paste tool for programmatic activation
    private ShapeTool shapeToolInstance; // Reference to shape tool for configuration

    /**
     * Create toolbar renderer with all available tools.
     */
    public TextureEditorToolbarRenderer() {
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

        logger.debug("Toolbar renderer created with {} tools", tools.size());
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

        // Apply button padding (inherited from BaseToolbarRenderer)
        pushButtonPadding(buttonPadding, buttonPadding);

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

            // Render icon button with highlighting (inherited from BaseToolbarRenderer)
            boolean clicked;
            if (textureId != -1) {
                // Use base class icon button renderer with highlighting support
                clicked = renderIconButton(textureId, iconDisplaySize, null, isSelected);
            } else {
                // Fallback to text button if icon not loaded
                popButtonPadding(); // Remove padding for text button
                clicked = renderButton(tool.getName() + "##tool_" + i, null, isSelected);
                pushButtonPadding(buttonPadding, buttonPadding); // Re-apply for consistency
            }

            if (clicked) {
                setCurrentTool(tool);
            }

            // Handle right-click for ShapeTool - show variant menu
            if (tool instanceof ShapeTool && ImGui.isItemClicked(1)) {
                ImGui.openPopup("##ShapeVariantPopup");
            }

            if (tool instanceof ShapeTool && ImGui.beginPopup("##ShapeVariantPopup")) {
                renderShapeVariantPopup((ShapeTool) tool, iconDisplaySize);
            }

            // Tooltip showing tool name (inherited from BaseToolbarRenderer)
            if (tool instanceof ShapeTool) {
                ShapeTool shapeTool = (ShapeTool) tool;
                renderTooltip(shapeTool.getCurrentShape().getDisplayName() + " (right-click for more shapes)");
            } else {
                renderTooltip(tool.getName());
            }

            // Add small spacing between buttons
            ImGui.spacing();
        }

        popButtonPadding(); // Pop button padding

        ImGui.endChild();
    }

    /**
     * Render shape variant selection popup.
     * Uses inherited styling methods from BaseToolbarRenderer.
     *
     * @param shapeTool the shape tool instance
     * @param iconDisplaySize the icon display size
     */
    private void renderShapeVariantPopup(ShapeTool shapeTool, float iconDisplaySize) {
        ShapeTool.ShapeType currentShapeType = shapeTool.getCurrentShape();

        // Rectangle option
        int rectIconId = iconManager.getIconTexture("Shapes:Rectangle");
        if (rectIconId != -1) {
            boolean isRectSelected = (currentShapeType == ShapeTool.ShapeType.RECTANGLE);

            // Use base class icon button renderer with highlighting support
            if (renderIconButton(rectIconId, iconDisplaySize, "Rectangle", isRectSelected)) {
                shapeTool.setCurrentShape(ShapeTool.ShapeType.RECTANGLE);
                ImGui.closeCurrentPopup();
            }
        }

        // Ellipse option
        int ellipseIconId = iconManager.getIconTexture("Shapes:Ellipse");
        if (ellipseIconId != -1) {
            boolean isEllipseSelected = (currentShapeType == ShapeTool.ShapeType.ELLIPSE);

            // Use base class icon button renderer with highlighting support
            if (renderIconButton(ellipseIconId, iconDisplaySize, "Ellipse", isEllipseSelected)) {
                shapeTool.setCurrentShape(ShapeTool.ShapeType.ELLIPSE);
                ImGui.closeCurrentPopup();
            }
        }

        // Triangle option
        int triangleIconId = iconManager.getIconTexture("Shapes:Triangle");
        if (triangleIconId != -1) {
            boolean isTriangleSelected = (currentShapeType == ShapeTool.ShapeType.TRIANGLE);

            // Use base class icon button renderer with highlighting support
            if (renderIconButton(triangleIconId, iconDisplaySize, "Triangle", isTriangleSelected)) {
                shapeTool.setCurrentShape(ShapeTool.ShapeType.TRIANGLE);
                ImGui.closeCurrentPopup();
            }
        }

        ImGui.endPopup();
    }

    /**
     * Set the current tool.
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
     */
    public DrawingTool getCurrentTool() {
        return currentTool;
    }

    /**
     * Get all available tools.
     */
    public List<DrawingTool> getTools() {
        return tools;
    }


    /**
     * Get the move tool instance.
     * Used for paste operations
     */
    public MoveToolController getMoveToolInstance() {
        return moveToolInstance;
    }
}
