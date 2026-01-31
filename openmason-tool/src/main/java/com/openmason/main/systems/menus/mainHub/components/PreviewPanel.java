package com.openmason.main.systems.menus.mainHub.components;

import com.openmason.main.systems.LogoManager;
import com.openmason.main.systems.menus.mainHub.model.NavigationItem;
import com.openmason.main.systems.menus.mainHub.model.ProjectTemplate;
import com.openmason.main.systems.menus.mainHub.model.RecentProject;
import com.openmason.main.systems.menus.mainHub.services.HubActionService;
import com.openmason.main.systems.menus.mainHub.state.HubState;
import com.openmason.main.systems.themes.core.ThemeDefinition;
import com.openmason.main.systems.themes.core.ThemeManager;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;

/**
 * Right panel showing selected template/project details.
 * Single Responsibility: Display preview and metadata for selection.
 */
public class PreviewPanel {

    private static final float PREVIEW_IMAGE_HEIGHT = 200.0f;
    private static final float BUTTON_HEIGHT = 35.0f;

    private final ThemeManager themeManager;
    private final HubState hubState;
    private final LogoManager logoManager;
    private final HubActionService actionService;

    public PreviewPanel(ThemeManager themeManager, HubState hubState, LogoManager logoManager, HubActionService actionService) {
        this.themeManager = themeManager;
        this.hubState = hubState;
        this.logoManager = logoManager;
        this.actionService = actionService;
    }

    /**
     * Render the preview panel.
     */
    public void render() {
        NavigationItem.ViewType currentView = hubState.getCurrentView();

        if (currentView == NavigationItem.ViewType.TEMPLATES) {
            renderTemplatePreview();
        } else if (currentView == NavigationItem.ViewType.RECENT_PROJECTS) {
            renderProjectPreview();
        } else {
            renderEmptyState();
        }
    }

    /**
     * Render template preview.
     */
    private void renderTemplatePreview() {
        ProjectTemplate template = hubState.getSelectedTemplate();

        if (template == null) {
            renderNoSelectionState("Select a template to view details");
            return;
        }

        ThemeDefinition theme = themeManager.getCurrentTheme();

        // Preview image placeholder
        renderPreviewImagePlaceholder();

        ImGui.spacing();
        ImGui.spacing();

        // Template name
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 0, 8.0f);
        ImGui.text(template.getName());
        ImGui.popStyleVar();

        // Category badge
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 4.0f, 2.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, 3.0f);
        ImVec4 badgeColor = theme.getColor(ImGuiCol.Button);
        if (badgeColor != null) {
            ImGui.pushStyleColor(ImGuiCol.Button, badgeColor.x, badgeColor.y, badgeColor.z, badgeColor.w);
        }
        ImGui.button(template.getCategory());
        if (badgeColor != null) {
            ImGui.popStyleColor();
        }
        ImGui.popStyleVar(2);

        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();

        // Description
        ImVec4 textColor = theme.getColor(ImGuiCol.Text);
        if (textColor != null) {
            ImGui.pushStyleColor(ImGuiCol.Text, textColor.x, textColor.y, textColor.z, 0.9f);
        }
        ImGui.pushTextWrapPos(ImGui.getWindowWidth() - 20);
        ImGui.textWrapped(template.getDescription());
        ImGui.popTextWrapPos();
        if (textColor != null) {
            ImGui.popStyleColor();
        }

        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();

        // Metadata
        if (!template.getMetadata().isEmpty()) {
            ImVec4 textDisabled = theme.getColor(ImGuiCol.TextDisabled);
            if (textDisabled != null) {
                ImGui.pushStyleColor(ImGuiCol.Text, textDisabled.x, textDisabled.y, textDisabled.z, 0.8f);
            }

            for (String key : template.getMetadata().keySet()) {
                String value = template.getMetadataValue(key);
                ImGui.text(key + ": " + value);
            }

            if (textDisabled != null) {
                ImGui.popStyleColor();
            }

            ImGui.spacing();
        }

        // Push buttons to bottom
        float windowHeight = ImGui.getWindowHeight();
        float cursorY = ImGui.getCursorPosY();
        float remainingSpace = windowHeight - cursorY - BUTTON_HEIGHT - 20;
        if (remainingSpace > 0) {
            ImGui.setCursorPosY(cursorY + remainingSpace);
        }

        // Create Project button
        ImVec4 buttonColor = theme.getColor(ImGuiCol.Button);
        if (buttonColor != null) {
            ImGui.pushStyleColor(ImGuiCol.Button, buttonColor.x, buttonColor.y, buttonColor.z, buttonColor.w);
        }
        ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, 5.0f);
        if (ImGui.button("Create Project", -1, BUTTON_HEIGHT)) {
            actionService.createProjectFromTemplate(template);
        }
        ImGui.popStyleVar();
        if (buttonColor != null) {
            ImGui.popStyleColor();
        }
    }

    /**
     * Render project preview.
     */
    private void renderProjectPreview() {
        RecentProject project = hubState.getSelectedRecentProject();

        if (project == null) {
            renderNoSelectionState("Select a project to view details");
            return;
        }

        ThemeDefinition theme = themeManager.getCurrentTheme();

        // Preview image placeholder
        renderPreviewImagePlaceholder();

        ImGui.spacing();
        ImGui.spacing();

        // Project name
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 0, 8.0f);
        ImGui.text(project.getName());
        ImGui.popStyleVar();

        // Template badge
        if (project.getSourceTemplate() != null) {
            ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 4.0f, 2.0f);
            ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, 3.0f);
            ImVec4 badgeColor = theme.getColor(ImGuiCol.Button);
            if (badgeColor != null) {
                ImGui.pushStyleColor(ImGuiCol.Button, badgeColor.x, badgeColor.y, badgeColor.z, 0.5f);
            }
            ImGui.button(project.getSourceTemplate().getName());
            if (badgeColor != null) {
                ImGui.popStyleColor();
            }
            ImGui.popStyleVar(2);
        }

        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();

        // Description
        ImVec4 textColor = theme.getColor(ImGuiCol.Text);
        if (textColor != null) {
            ImGui.pushStyleColor(ImGuiCol.Text, textColor.x, textColor.y, textColor.z, 0.9f);
        }
        ImGui.pushTextWrapPos(ImGui.getWindowWidth() - 20);
        ImGui.textWrapped(project.getDescription());
        ImGui.popTextWrapPos();
        if (textColor != null) {
            ImGui.popStyleColor();
        }

        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();

        // Metadata
        ImVec4 textDisabled = theme.getColor(ImGuiCol.TextDisabled);
        if (textDisabled != null) {
            ImGui.pushStyleColor(ImGuiCol.Text, textDisabled.x, textDisabled.y, textDisabled.z, 0.8f);
        }
        ImGui.text("Path: " + project.getPath());
        ImGui.text("Last Opened: " + project.getLastOpened().toString());
        if (textDisabled != null) {
            ImGui.popStyleColor();
        }

        ImGui.spacing();

        // Push buttons to bottom
        float windowHeight = ImGui.getWindowHeight();
        float cursorY = ImGui.getCursorPosY();
        float remainingSpace = windowHeight - cursorY - BUTTON_HEIGHT - 20;
        if (remainingSpace > 0) {
            ImGui.setCursorPosY(cursorY + remainingSpace);
        }

        // Open Project button
        ImVec4 buttonColor = theme.getColor(ImGuiCol.Button);
        if (buttonColor != null) {
            ImGui.pushStyleColor(ImGuiCol.Button, buttonColor.x, buttonColor.y, buttonColor.z, buttonColor.w);
        }
        ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, 5.0f);
        if (ImGui.button("Open Project", -1, BUTTON_HEIGHT)) {
            actionService.openRecentProject(project);
        }
        ImGui.popStyleVar();
        if (buttonColor != null) {
            ImGui.popStyleColor();
        }
    }

    /**
     * Render empty state for views without selection.
     */
    private void renderEmptyState() {
        renderNoSelectionState("No preview available");
    }

    /**
     * Render no selection state.
     */
    private void renderNoSelectionState(String message) {
        ImGui.spacing();
        ImGui.spacing();

        // Centered message
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
     * Render preview image placeholder.
     */
    private void renderPreviewImagePlaceholder() {
        float windowWidth = ImGui.getWindowWidth();
        float logoSize = Math.min(windowWidth * 0.6f, PREVIEW_IMAGE_HEIGHT);

        // Center logo
        ImVec2 scaledSize = logoManager.getScaledLogoSize(logoSize, logoSize);
        float logoX = (windowWidth - scaledSize.x) * 0.5f;
        ImGui.setCursorPosX(logoX);

        logoManager.renderLogo(scaledSize.x, scaledSize.y);
    }
}
