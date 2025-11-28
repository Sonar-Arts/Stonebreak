package com.openmason.main.systems.menus.mainHub.components;

import com.openmason.main.systems.menus.mainHub.model.ProjectTemplate;
import com.openmason.main.systems.menus.mainHub.services.TemplateService;
import com.openmason.main.systems.menus.mainHub.state.HubState;
import com.openmason.main.systems.themes.core.ThemeDefinition;
import com.openmason.main.systems.themes.core.ThemeManager;
import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiMouseCursor;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;

import java.util.List;

/**
 * Center panel showing project templates in grid layout.
 * Single Responsibility: Display and manage template grid.
 */
public class TemplatesPanel {

    private static final float CARD_WIDTH = 220.0f;
    private static final float CARD_HEIGHT = 180.0f;
    private static final float CARD_PADDING = 15.0f;
    private static final float CARD_SPACING = 20.0f;
    private static final float CARD_ROUNDING = 8.0f;

    private final ThemeManager themeManager;
    private final HubState hubState;
    private final TemplateService templateService;

    // Track which card is being actively clicked (mouse down but not released)
    private int clickedCardIndex = -1;

    public TemplatesPanel(ThemeManager themeManager, HubState hubState, TemplateService templateService) {
        this.themeManager = themeManager;
        this.hubState = hubState;
        this.templateService = templateService;
    }

    /**
     * Render the templates panel.
     */
    public void render() {
        // Get filtered templates based on search query
        String searchQuery = hubState.getSearchQuery();
        List<ProjectTemplate> templates = searchQuery.isEmpty()
                ? templateService.getAllTemplates()
                : templateService.search(searchQuery);

        if (templates.isEmpty()) {
            renderEmptyState();
            return;
        }

        // Calculate grid layout
        float panelWidth = ImGui.getWindowWidth() - 20.0f; // Account for scrollbar
        int cardsPerRow = Math.max(1, (int)((panelWidth + CARD_SPACING) / (CARD_WIDTH + CARD_SPACING)));

        // Render template cards in grid
        for (int i = 0; i < templates.size(); i++) {
            ProjectTemplate template = templates.get(i);

            renderTemplateCard(template, i);

            // Move to next row or add horizontal spacing
            if ((i + 1) % cardsPerRow == 0) {
                // Next row
            } else if (i < templates.size() - 1) {
                ImGui.sameLine(0, CARD_SPACING);
            }
        }
    }

    /**
     * Render empty state when no templates match search.
     */
    private void renderEmptyState() {
        ImGui.spacing();
        ImGui.spacing();

        String message = hubState.getSearchQuery().isEmpty()
                ? "No templates available"
                : "No templates match your search";

        ImVec2 textSize = ImGui.calcTextSize(message);
        float windowWidth = ImGui.getWindowWidth();
        float textX = (windowWidth - textSize.x) * 0.5f;
        ImGui.setCursorPosX(textX);

        ThemeDefinition theme = themeManager.getCurrentTheme();
        ImVec4 textDisabled = theme.getColor(ImGuiCol.TextDisabled);
        if (textDisabled != null) {
            ImGui.pushStyleColor(ImGuiCol.Text, textDisabled.x, textDisabled.y, textDisabled.z, textDisabled.w);
        }
        ImGui.text(message);
        if (textDisabled != null) {
            ImGui.popStyleColor();
        }
    }

    /**
     * Render a single template card.
     */
    private void renderTemplateCard(ProjectTemplate template, int index) {
        ImVec2 cursorPos = ImGui.getCursorScreenPos();
        ImDrawList drawList = ImGui.getWindowDrawList();

        float x1 = cursorPos.x;
        float y1 = cursorPos.y;
        float x2 = x1 + CARD_WIDTH;
        float y2 = y1 + CARD_HEIGHT;

        boolean isSelected = hubState.getSelectedTemplate() == template;
        boolean isHovered = ImGui.isMouseHoveringRect(x1, y1, x2, y2);

        if (isHovered) {
            ImGui.setMouseCursor(ImGuiMouseCursor.Hand);
        }

        // Get theme colors
        ThemeDefinition theme = themeManager.getCurrentTheme();
        // Consistent background - never changes for text readability
        ImVec4 bgColor = theme.getColor(ImGuiCol.ChildBg);
        if (bgColor == null) bgColor = new ImVec4(0.15f, 0.15f, 0.15f, 1.0f);

        // Determine click state
        boolean isClicked = (clickedCardIndex == index);

        // Border-based visual feedback with 4 states
        ImVec4 borderColor;
        float borderThickness;
        float shadowOffset;
        float shadowAlpha;

        if (isClicked) {
            // Clicked state: Thick bright border with strong shadow
            borderColor = theme.getColor(ImGuiCol.ButtonHovered);
            if (borderColor == null) borderColor = new ImVec4(0.26f, 0.59f, 0.98f, 1.0f);
            borderThickness = 4.0f;
            shadowOffset = 8.0f;
            shadowAlpha = 0.4f;
        } else if (isSelected) {
            // Selected state: Thick colored border with moderate shadow
            borderColor = theme.getColor(ImGuiCol.Header);
            if (borderColor == null) borderColor = new ImVec4(0.26f, 0.59f, 0.98f, 1.0f);
            borderThickness = 3.5f;
            shadowOffset = 6.0f;
            shadowAlpha = 0.3f;
        } else if (isHovered) {
            // Hovered state: Medium accent border with moderate shadow
            borderColor = theme.getColor(ImGuiCol.ButtonHovered);
            if (borderColor == null) borderColor = new ImVec4(0.4f, 0.4f, 0.4f, 1.0f);
            borderThickness = 2.5f;
            shadowOffset = 6.0f;
            shadowAlpha = 0.3f;
        } else {
            // Normal state: Thin subtle border with light shadow
            borderColor = theme.getColor(ImGuiCol.Border);
            if (borderColor == null) borderColor = new ImVec4(0.3f, 0.3f, 0.3f, 1.0f);
            borderThickness = 1.5f;
            shadowOffset = 4.0f;
            shadowAlpha = 0.2f;
        }

        // Draw drop shadow
        int shadowColor = ImColor.rgba(0, 0, 0, shadowAlpha);
        drawList.addRectFilled(
                x1 + shadowOffset, y1 + shadowOffset,
                x2 + shadowOffset, y2 + shadowOffset,
                shadowColor, CARD_ROUNDING
        );

        // Draw card background
        int bg = ImColor.rgba(bgColor.x, bgColor.y, bgColor.z, bgColor.w);
        drawList.addRectFilled(x1, y1, x2, y2, bg, CARD_ROUNDING);

        // Draw border
        int border = ImColor.rgba(borderColor.x, borderColor.y, borderColor.z, 1.0f);
        drawList.addRect(x1, y1, x2, y2, border, CARD_ROUNDING, 0, borderThickness);

        // Render template name (no icon needed)
        float nameY = y1 + CARD_PADDING + 20;
        ImGui.setCursorScreenPos(x1 + CARD_PADDING, nameY);
        ImGui.pushTextWrapPos(x2 - CARD_PADDING);
        ImGui.textWrapped(template.getName());
        ImGui.popTextWrapPos();

        // Render category badge
        float badgeY = nameY + 35;
        ImGui.setCursorScreenPos(x1 + CARD_PADDING, badgeY);
        renderCategoryBadge(template.getCategory(), theme);

        // Render short description
        float descY = badgeY + 25;
        ImGui.setCursorScreenPos(x1 + CARD_PADDING, descY);
        ImGui.beginChild("##desc_" + index, CARD_WIDTH - 2 * CARD_PADDING, y2 - descY - CARD_PADDING, false, ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoBackground);
        ImVec4 textDisabled = theme.getColor(ImGuiCol.TextDisabled);
        if (textDisabled != null) {
            // Consistent text color - always readable
            ImGui.pushStyleColor(ImGuiCol.Text, textDisabled.x, textDisabled.y, textDisabled.z, textDisabled.w);
        }
        ImGui.pushTextWrapPos(CARD_WIDTH - 2 * CARD_PADDING);
        ImGui.textWrapped(template.getDescription());
        ImGui.popTextWrapPos();
        if (textDisabled != null) {
            ImGui.popStyleColor();
        }
        ImGui.endChild();

        // Direct mouse click detection - no invisible button needed
        if (isHovered && ImGui.isMouseClicked(0)) {
            // Mouse button pressed down on this card
            clickedCardIndex = index;
        }
        if (clickedCardIndex == index && !ImGui.isMouseDown(0)) {
            // Mouse button released - complete the click
            hubState.setSelectedTemplate(template);
            clickedCardIndex = -1;
        }

        // Restore cursor for next card
        ImGui.setCursorScreenPos(x1, y2 + CARD_SPACING);
    }

    /**
     * Render category badge.
     */
    private void renderCategoryBadge(String category, ThemeDefinition theme) {
        ImVec4 badgeColor = theme.getColor(ImGuiCol.Button);
        if (badgeColor != null) {
            ImGui.pushStyleColor(ImGuiCol.Button, badgeColor.x, badgeColor.y, badgeColor.z, badgeColor.w);
        }
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 4.0f, 2.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, 3.0f);
        ImGui.button(category);
        ImGui.popStyleVar(2);
        if (badgeColor != null) {
            ImGui.popStyleColor();
        }
    }

}
