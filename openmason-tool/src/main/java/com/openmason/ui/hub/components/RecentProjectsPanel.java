package com.openmason.ui.hub.components;

import com.openmason.ui.hub.model.RecentProject;
import com.openmason.ui.hub.services.RecentProjectsService;
import com.openmason.ui.hub.state.HubState;
import com.openmason.ui.themes.core.ThemeDefinition;
import com.openmason.ui.themes.core.ThemeManager;
import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiMouseCursor;
import imgui.flag.ImGuiStyleVar;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Center panel showing recent projects.
 * Single Responsibility: Display and manage recent projects list/grid.
 */
public class RecentProjectsPanel {

    private static final float ITEM_HEIGHT = 80.0f;
    private static final float ITEM_PADDING = 12.0f;
    private static final float ITEM_SPACING = 10.0f;
    private static final float ITEM_ROUNDING = 6.0f;

    private final ThemeManager themeManager;
    private final HubState hubState;
    private final RecentProjectsService recentProjectsService;

    public RecentProjectsPanel(ThemeManager themeManager, HubState hubState, RecentProjectsService recentProjectsService) {
        this.themeManager = themeManager;
        this.hubState = hubState;
        this.recentProjectsService = recentProjectsService;
    }

    /**
     * Render the recent projects panel.
     */
    public void render() {
        // Get filtered projects based on search query
        String searchQuery = hubState.getSearchQuery();
        List<RecentProject> projects = searchQuery.isEmpty()
                ? recentProjectsService.getRecentProjects()
                : recentProjectsService.search(searchQuery);

        if (projects.isEmpty()) {
            renderEmptyState();
            return;
        }

        // Render projects in list format
        for (int i = 0; i < projects.size(); i++) {
            RecentProject project = projects.get(i);
            renderProjectItem(project, i);
            ImGui.spacing();
        }
    }

    /**
     * Render empty state when no projects exist.
     */
    private void renderEmptyState() {
        ImGui.spacing();
        ImGui.spacing();

        String message = hubState.getSearchQuery().isEmpty()
                ? "No recent projects"
                : "No projects match your search";

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

        ImGui.spacing();
        ImVec2 hintSize = ImGui.calcTextSize("Saved projects will appear here");
        float hintX = (windowWidth - hintSize.x) * 0.5f;
        ImGui.setCursorPosX(hintX);
        if (textDisabled != null) {
            ImGui.pushStyleColor(ImGuiCol.Text, textDisabled.x, textDisabled.y, textDisabled.z, 0.6f);
        }
        ImGui.text("Saved projects will appear here");
        if (textDisabled != null) {
            ImGui.popStyleColor();
        }
    }

    /**
     * Render a single project item.
     */
    private void renderProjectItem(RecentProject project, int index) {
        ImVec2 cursorPos = ImGui.getCursorScreenPos();
        ImDrawList drawList = ImGui.getWindowDrawList();

        float itemWidth = ImGui.getWindowWidth() - 20.0f; // Account for padding
        float x1 = cursorPos.x;
        float y1 = cursorPos.y;
        float x2 = x1 + itemWidth;
        float y2 = y1 + ITEM_HEIGHT;

        boolean isSelected = hubState.getSelectedRecentProject() == project;
        boolean isHovered = ImGui.isMouseHoveringRect(x1, y1, x2, y2);

        if (isHovered) {
            ImGui.setMouseCursor(ImGuiMouseCursor.Hand);
        }

        // Get theme colors
        ThemeDefinition theme = themeManager.getCurrentTheme();
        // Three-state background: selected > hovered > normal
        ImVec4 bgColor = isSelected
                ? theme.getColor(ImGuiCol.Header)
                : (isHovered ? theme.getColor(ImGuiCol.FrameBgHovered) : theme.getColor(ImGuiCol.ChildBg));
        if (bgColor == null) bgColor = new ImVec4(0.15f, 0.15f, 0.15f, 1.0f);

        ImVec4 borderColor = isHovered || isSelected
                ? theme.getColor(ImGuiCol.ButtonHovered)
                : theme.getColor(ImGuiCol.Border);
        if (borderColor == null) borderColor = new ImVec4(0.4f, 0.4f, 0.4f, 1.0f);

        // Draw background
        int bg = ImColor.rgba(bgColor.x, bgColor.y, bgColor.z, bgColor.w);
        drawList.addRectFilled(x1, y1, x2, y2, bg, ITEM_ROUNDING);

        // Draw border
        int border = ImColor.rgba(borderColor.x, borderColor.y, borderColor.z, 1.0f);
        float borderThickness = (isHovered || isSelected) ? 2.5f : 1.5f;
        drawList.addRect(x1, y1, x2, y2, border, ITEM_ROUNDING, 0, borderThickness);

        // Render project icon (left side)
        float iconX = x1 + ITEM_PADDING;
        float iconY = y1 + ITEM_PADDING;
        ImGui.setCursorScreenPos(iconX, iconY);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 0, 0);
        String icon = getProjectIcon(project);
        ImGui.text(icon);
        ImGui.popStyleVar();

        // Render project name
        float nameX = iconX + 50;
        float nameY = y1 + ITEM_PADDING;
        ImGui.setCursorScreenPos(nameX, nameY);
        ImGui.text(project.getName());

        // Render template badge
        ImGui.sameLine();
        if (project.getSourceTemplate() != null) {
            renderTemplateBadge(project.getSourceTemplate().getName(), theme);
        }

        // Render description
        float descY = nameY + 22;
        ImGui.setCursorScreenPos(nameX, descY);
        ImVec4 textDisabled = theme.getColor(ImGuiCol.TextDisabled);
        if (textDisabled != null) {
            // Increase opacity on hover for better legibility: 0.8 (normal) -> 0.95 (hovered)
            float descOpacity = isHovered ? 0.95f : 0.8f;
            ImGui.pushStyleColor(ImGuiCol.Text, textDisabled.x, textDisabled.y, textDisabled.z, descOpacity);
        }
        ImGui.text(project.getDescription());
        if (textDisabled != null) {
            ImGui.popStyleColor();
        }

        // Render last opened time (right aligned)
        String timeText = formatLastOpened(project.getLastOpened());
        ImVec2 timeSize = ImGui.calcTextSize(timeText);
        float timeX = x2 - timeSize.x - ITEM_PADDING;
        float timeY = y1 + ITEM_PADDING;
        ImGui.setCursorScreenPos(timeX, timeY);
        if (textDisabled != null) {
            // Increase opacity on hover for better legibility: 0.7 (normal) -> 0.85 (hovered)
            float timeOpacity = isHovered ? 0.85f : 0.7f;
            ImGui.pushStyleColor(ImGuiCol.Text, textDisabled.x, textDisabled.y, textDisabled.z, timeOpacity);
        }
        ImGui.text(timeText);
        if (textDisabled != null) {
            ImGui.popStyleColor();
        }

        // Invisible button for click detection
        ImGui.setCursorScreenPos(x1, y1);
        ImGui.pushStyleColor(ImGuiCol.Button, 0);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, 0);
        if (ImGui.button("##project_" + index, itemWidth, ITEM_HEIGHT)) {
            hubState.setSelectedRecentProject(project);
        }
        ImGui.popStyleColor(3);

        // Restore cursor for next item
        ImGui.setCursorScreenPos(x1, y2 + ITEM_SPACING);
    }

    /**
     * Render template badge.
     */
    private void renderTemplateBadge(String templateName, ThemeDefinition theme) {
        ImVec4 badgeColor = theme.getColor(ImGuiCol.Button);
        if (badgeColor != null) {
            ImGui.pushStyleColor(ImGuiCol.Button, badgeColor.x, badgeColor.y, badgeColor.z, 0.5f);
        }
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 3.0f, 1.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, 3.0f);
        ImGui.button(templateName);
        ImGui.popStyleVar(2);
        if (badgeColor != null) {
            ImGui.popStyleColor();
        }
    }

    /**
     * Get icon for project based on source template.
     */
    private String getProjectIcon(RecentProject project) {
        if (project.getSourceTemplate() == null) {
            return "[P]";
        }

        return switch (project.getSourceTemplate().getType()) {
            case BASIC_3D_MODEL -> "[M]";
            case ADVANCED_3D_MODEL -> "[3D]";
            case TEXTURE_PACK -> "[T]";
            case BLOCK_SET -> "[B]";
            case FULL_GAME_TEMPLATE -> "[G]";
            case CUSTOM -> "[P]";
        };
    }

    /**
     * Format last opened time in human-readable format.
     */
    private String formatLastOpened(LocalDateTime lastOpened) {
        LocalDateTime now = LocalDateTime.now();

        long minutesAgo = ChronoUnit.MINUTES.between(lastOpened, now);
        if (minutesAgo < 60) {
            return minutesAgo + " min ago";
        }

        long hoursAgo = ChronoUnit.HOURS.between(lastOpened, now);
        if (hoursAgo < 24) {
            return hoursAgo + " hour" + (hoursAgo > 1 ? "s" : "") + " ago";
        }

        long daysAgo = ChronoUnit.DAYS.between(lastOpened, now);
        if (daysAgo < 7) {
            return daysAgo + " day" + (daysAgo > 1 ? "s" : "") + " ago";
        }

        long weeksAgo = daysAgo / 7;
        if (weeksAgo < 4) {
            return weeksAgo + " week" + (weeksAgo > 1 ? "s" : "") + " ago";
        }

        // For older projects, show actual date
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMM d, yyyy");
        return lastOpened.format(formatter);
    }
}
