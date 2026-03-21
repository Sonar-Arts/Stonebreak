package com.openmason.main.systems.menus.mainHub.components;

import com.openmason.main.systems.LogoManager;
import com.openmason.main.systems.menus.mainHub.dialogs.DeleteProjectDialog;
import com.openmason.main.systems.menus.mainHub.dialogs.RenameProjectDialog;
import com.openmason.main.systems.menus.mainHub.model.NavigationItem;
import com.openmason.main.systems.menus.mainHub.model.ProjectTemplate;
import com.openmason.main.systems.menus.mainHub.model.RecentProject;
import com.openmason.main.systems.menus.mainHub.services.HubActionService;
import com.openmason.main.systems.menus.mainHub.services.RecentProjectsService;
import com.openmason.main.systems.menus.mainHub.state.HubState;
import com.openmason.main.systems.themes.core.ThemeDefinition;
import com.openmason.main.systems.themes.core.ThemeManager;
import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Right panel showing selected template/project details.
 * Single Responsibility: Display preview and metadata for selection.
 */
public class PreviewPanel {

    private static final Logger logger = LoggerFactory.getLogger(PreviewPanel.class);

    private static final float PREVIEW_IMAGE_HEIGHT = 160.0f;
    private static final float BUTTON_HEIGHT = 32.0f;

    private final ThemeManager themeManager;
    private final HubState hubState;
    private final LogoManager logoManager;
    private final HubActionService actionService;
    private final RecentProjectsService recentProjectsService;

    private RenameProjectDialog renameDialog;
    private DeleteProjectDialog deleteDialog;

    public PreviewPanel(ThemeManager themeManager, HubState hubState, LogoManager logoManager,
                        HubActionService actionService, RecentProjectsService recentProjectsService) {
        this.themeManager = themeManager;
        this.hubState = hubState;
        this.logoManager = logoManager;
        this.actionService = actionService;
        this.recentProjectsService = recentProjectsService;
    }

    public void setDialogs(RenameProjectDialog renameDialog, DeleteProjectDialog deleteDialog) {
        this.renameDialog = renameDialog;
        this.deleteDialog = deleteDialog;
    }

    public void render() {
        NavigationItem.ViewType currentView = hubState.getCurrentView();

        if (currentView == NavigationItem.ViewType.TEMPLATES) {
            renderTemplatePreview();
        } else if (currentView == NavigationItem.ViewType.RECENT_PROJECTS) {
            renderProjectPreview();
        } else {
            renderNoSelectionState("No preview available");
        }
    }

    // ========== Template Preview ==========

    private void renderTemplatePreview() {
        ProjectTemplate template = hubState.getSelectedTemplate();
        if (template == null) {
            renderNoSelectionState("Select a template to view details");
            return;
        }

        ThemeDefinition theme = themeManager.getCurrentTheme();

        renderPreviewImagePlaceholder();
        ImGui.spacing();
        ImGui.spacing();

        ImGui.text(template.getName());
        ImGui.sameLine();
        renderBadge(template.getCategory(), theme);

        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();

        renderDimText(template.getDescription(), theme, 0.85f, true);

        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();

        if (!template.getMetadata().isEmpty()) {
            for (String key : template.getMetadata().keySet()) {
                renderDimText(key + ": " + template.getMetadataValue(key), theme, 0.6f, false);
            }
            ImGui.spacing();
        }

        pushToBottom(BUTTON_HEIGHT + 20);
        renderAccentButton("Create Project", theme);
        if (ImGui.isItemClicked()) {
            actionService.createProjectFromTemplate(template);
        }
    }

    // ========== Project Preview ==========

    private void renderProjectPreview() {
        RecentProject project = hubState.getSelectedRecentProject();
        if (project == null) {
            renderNoSelectionState("Select a project to view details");
            return;
        }

        ThemeDefinition theme = themeManager.getCurrentTheme();

        // --- Header: Logo + Name ---
        renderPreviewImagePlaceholder();
        ImGui.spacing();

        // Project name
        ImGui.text(project.getName());
        if (project.getSourceTemplate() != null) {
            ImGui.sameLine();
            renderBadge(project.getSourceTemplate().getName(), theme);
        }

        ImGui.spacing();

        // Open Project — primary action right below the name
        renderAccentButton("Open Project", theme);
        if (ImGui.isItemClicked()) {
            actionService.openRecentProject(project);
        }

        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();

        // --- Info rows ---
        renderLabelValue("Last Opened", formatLastOpened(project.getLastOpened()), theme);
        ImGui.spacing();
        renderLocationRow(project.getPath(), theme);

        // Description
        if (!project.getDescription().isEmpty()) {
            ImGui.spacing();
            ImGui.separator();
            ImGui.spacing();
            renderDimText(project.getDescription(), theme, 0.7f, true);
        }

        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();

        // --- Actions ---
        ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, 4.0f);

        float availW = ImGui.getContentRegionAvailX();
        float halfW = (availW - 8.0f) / 2.0f;

        if (ImGui.button("Open Folder", halfW, BUTTON_HEIGHT)) {
            openContainingFolder(project.getPath());
        }
        ImGui.sameLine(0, 8.0f);
        if (ImGui.button("Rename", halfW, BUTTON_HEIGHT)) {
            if (renameDialog != null) {
                renameDialog.show(project.getId(), project.getName(), this::handleRename);
            }
        }

        ImGui.spacing();

        ImGui.pushStyleColor(ImGuiCol.Button, 0.45f, 0.12f, 0.12f, 0.5f);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0.6f, 0.15f, 0.15f, 0.8f);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, 0.7f, 0.2f, 0.2f, 1.0f);
        if (ImGui.button("Remove Project", -1, BUTTON_HEIGHT)) {
            if (deleteDialog != null) {
                deleteDialog.show(project, this::handleDelete);
            }
        }
        ImGui.popStyleColor(3);

        ImGui.popStyleVar();
    }

    // ========== Info Row Helpers ==========

    /**
     * Render a label: value pair on one line.
     * Label is dimmed, value is normal brightness.
     */
    private void renderLabelValue(String label, String value, ThemeDefinition theme) {
        ImVec4 textCol = theme.getColor(ImGuiCol.Text);
        if (textCol != null) {
            ImGui.pushStyleColor(ImGuiCol.Text, textCol.x, textCol.y, textCol.z, 0.6f);
        }
        ImGui.text(label + ":");
        if (textCol != null) {
            ImGui.popStyleColor();
        }
        ImGui.sameLine();
        ImGui.text(value);
    }

    /**
     * Render the location path on two lines: label then full path below it.
     * Path is wrapped so it never overlaps.
     */
    private void renderLocationRow(String path, ThemeDefinition theme) {
        ImVec4 textCol = theme.getColor(ImGuiCol.Text);

        // Label
        if (textCol != null) {
            ImGui.pushStyleColor(ImGuiCol.Text, textCol.x, textCol.y, textCol.z, 0.6f);
        }
        ImGui.text("Location:");
        if (textCol != null) {
            ImGui.popStyleColor();
        }

        // Full path on next line, wrapped
        if (textCol != null) {
            ImGui.pushStyleColor(ImGuiCol.Text, textCol.x, textCol.y, textCol.z, 0.8f);
        }
        ImGui.pushTextWrapPos(ImGui.getContentRegionAvailX() + ImGui.getCursorPosX());
        ImGui.textWrapped(path != null ? path : "");
        ImGui.popTextWrapPos();
        if (textCol != null) {
            ImGui.popStyleColor();
        }
    }

    // ========== Shared Rendering ==========

    private void renderAccentButton(String label, ThemeDefinition theme) {
        ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, 5.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 0, 8.0f);

        ImVec4 accent = theme.getColor(ImGuiCol.ButtonHovered);
        if (accent != null) {
            ImGui.pushStyleColor(ImGuiCol.Button, accent.x, accent.y, accent.z, 0.8f);
            ImGui.pushStyleColor(ImGuiCol.ButtonHovered, accent.x, accent.y, accent.z, 1.0f);
            ImGui.pushStyleColor(ImGuiCol.ButtonActive, accent.x * 0.8f, accent.y * 0.8f, accent.z * 0.8f, 1.0f);
        }

        ImGui.button(label, -1, BUTTON_HEIGHT + 4);

        if (accent != null) {
            ImGui.popStyleColor(3);
        }
        ImGui.popStyleVar(2);
    }

    private void renderBadge(String text, ThemeDefinition theme) {
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 6.0f, 1.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, 4.0f);
        ImVec4 c = theme.getColor(ImGuiCol.Button);
        if (c != null) {
            ImGui.pushStyleColor(ImGuiCol.Button, c.x, c.y, c.z, 0.35f);
        }
        ImGui.button(text);
        if (c != null) {
            ImGui.popStyleColor();
        }
        ImGui.popStyleVar(2);
    }

    private void renderDimText(String text, ThemeDefinition theme, float alpha, boolean wrap) {
        ImVec4 c = theme.getColor(ImGuiCol.Text);
        if (c != null) {
            ImGui.pushStyleColor(ImGuiCol.Text, c.x, c.y, c.z, alpha);
        }
        if (wrap) {
            ImGui.pushTextWrapPos(ImGui.getWindowWidth() - 20);
            ImGui.textWrapped(text);
            ImGui.popTextWrapPos();
        } else {
            ImGui.text(text);
        }
        if (c != null) {
            ImGui.popStyleColor();
        }
    }

    private void pushToBottom(float reservedHeight) {
        float remaining = ImGui.getWindowHeight() - ImGui.getCursorPosY() - reservedHeight;
        if (remaining > 0) {
            ImGui.setCursorPosY(ImGui.getCursorPosY() + remaining);
        }
    }

    // ========== Utility ==========

    private String formatLastOpened(LocalDateTime lastOpened) {
        LocalDateTime now = LocalDateTime.now();
        long min = ChronoUnit.MINUTES.between(lastOpened, now);
        if (min < 1) return "Just now";
        if (min < 60) return min + " min ago";
        long hrs = ChronoUnit.HOURS.between(lastOpened, now);
        if (hrs < 24) return hrs + "h ago";
        long days = ChronoUnit.DAYS.between(lastOpened, now);
        if (days < 7) return days + "d ago";
        if (days < 28) return (days / 7) + "w ago";
        return lastOpened.format(DateTimeFormatter.ofPattern("MMM d, yyyy"));
    }

    private void openContainingFolder(String filePath) {
        if (filePath == null || filePath.isBlank()) return;
        try {
            File file = new File(filePath);
            File folder = file.isDirectory() ? file : file.getParentFile();
            if (folder != null && folder.exists()) {
                java.awt.Desktop.getDesktop().open(folder);
            }
        } catch (Exception e) {
            logger.warn("Failed to open folder for: {}", filePath, e);
        }
    }

    // ========== Callbacks ==========

    private void handleRename(String projectId, String newName) {
        recentProjectsService.renameProject(projectId, newName);
        RecentProject selected = hubState.getSelectedRecentProject();
        if (selected != null && selected.getId().equals(projectId)) {
            hubState.setSelectedRecentProject(null);
        }
    }

    private void handleDelete(RecentProject project, boolean deleteFile) {
        if (deleteFile) recentProjectsService.deleteProjectFile(project);
        recentProjectsService.removeProject(project.getId());
        if (project.equals(hubState.getSelectedRecentProject())) {
            hubState.setSelectedRecentProject(null);
        }
    }

    // ========== Empty States ==========

    private void renderNoSelectionState(String message) {
        ImGui.spacing();
        ImGui.spacing();
        ImVec2 sz = ImGui.calcTextSize(message);
        ImGui.setCursorPosX((ImGui.getWindowWidth() - sz.x) * 0.5f);

        ThemeDefinition theme = themeManager.getCurrentTheme();
        ImVec4 textCol = theme.getColor(ImGuiCol.Text);
        if (textCol != null) {
            ImGui.pushStyleColor(ImGuiCol.Text, textCol.x, textCol.y, textCol.z, 0.6f);
        }
        ImGui.text(message);
        if (textCol != null) {
            ImGui.popStyleColor();
        }
    }

    private void renderPreviewImagePlaceholder() {
        float ww = ImGui.getWindowWidth();
        float logoSize = Math.min(ww * 0.5f, PREVIEW_IMAGE_HEIGHT);
        ImVec2 scaled = logoManager.getScaledLogoSize(logoSize, logoSize);
        ImGui.setCursorPosX((ww - scaled.x) * 0.5f);
        logoManager.renderLogo(scaled.x, scaled.y);
    }
}
