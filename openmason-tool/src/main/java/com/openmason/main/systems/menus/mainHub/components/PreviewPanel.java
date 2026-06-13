package com.openmason.main.systems.menus.mainHub.components;

import com.openmason.main.systems.LogoManager;
import com.openmason.main.systems.menus.mainHub.dialogs.DeleteProjectDialog;
import com.openmason.main.systems.menus.mainHub.dialogs.RenameProjectDialog;
import com.openmason.main.systems.menus.mainHub.model.ProjectTemplate;
import com.openmason.main.systems.menus.mainHub.model.RecentProject;
import com.openmason.main.systems.menus.mainHub.services.HubActionService;
import com.openmason.main.systems.menus.mainHub.services.RecentProjectsService;
import com.openmason.main.systems.menus.mainHub.state.HubState;
import com.openmason.main.systems.mortar.core.MortarRegion;
import com.openmason.main.systems.mortar.paint.MortarPainter;
import com.openmason.main.systems.mortar.parts.MortarBadge;
import com.openmason.main.systems.mortar.theme.Argb;
import com.openmason.main.systems.skija.SkijaFontStore.Weight;
import com.openmason.main.systems.themes.core.ThemeManager;
import imgui.ImGui;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.type.ImString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Contextual detail panel for the current selection (a template or a recent
 * project). The header — a Skija {@link MortarRegion} hero with a monogram,
 * name and category badge — is painted crisply; the metadata, description and
 * action-button rows stay ImGui (text wrapping, dialogs). Shown by
 * {@code ProjectHubScreen} only while something is selected; it slides in/out.
 */
public class PreviewPanel {

    private static final Logger logger = LoggerFactory.getLogger(PreviewPanel.class);

    private static final float THUMB_H = 120f;
    private static final float HERO_H = THUMB_H + 94f;
    private static final float BUTTON_HEIGHT = 32f;

    private final HubState hubState;
    private final HubActionService actionService;
    private final RecentProjectsService recentProjectsService;
    private final MortarRegion heroRegion = new MortarRegion();

    private RenameProjectDialog renameDialog;
    private DeleteProjectDialog deleteDialog;

    // Create-form inputs (shared by the New Project and Blank Template previews).
    private final ImString projectName = new ImString(128);
    private final ImString projectDir = new ImString(512);

    public PreviewPanel(ThemeManager themeManager, HubState hubState, LogoManager logoManager,
                        HubActionService actionService, RecentProjectsService recentProjectsService) {
        this.hubState = hubState;
        this.actionService = actionService;
        this.recentProjectsService = recentProjectsService;
    }

    public void setDialogs(RenameProjectDialog renameDialog, DeleteProjectDialog deleteDialog) {
        this.renameDialog = renameDialog;
        this.deleteDialog = deleteDialog;
    }

    public void render() {
        ProjectTemplate template = hubState.getSelectedTemplate();
        RecentProject project = hubState.getSelectedRecentProject();

        if (template != null) {
            renderTemplatePreview(template);
        } else if (project != null) {
            renderProjectPreview(project);
        } else if (hubState.isNewProjectSelected()) {
            renderNewProjectPreview();
        }
    }

    // ---- new project ------------------------------------------------------

    private void renderNewProjectPreview() {
        renderHero("New Project", "Blank");
        ImGui.dummy(0, 8f);

        renderCreateForm();

        ImGui.dummy(0, 6f);
        ImGui.separator();
        ImGui.dummy(0, 6f);

        dimWrapped("Choose a name and folder, then create. The project file is saved "
                + "to that folder right away so your work has a home from the start.", 0.85f);
    }

    /**
     * Name + directory inputs and the primary Create button, shared by the New
     * Project and Blank Template previews. Create stays disabled until both a
     * name and a directory are provided, then pre-saves the project there.
     */
    private void renderCreateForm() {
        fieldLabel("Project Name");
        ImGui.setNextItemWidth(-1);
        ImGui.inputTextWithHint("##np_name", "My Project", projectName, ImGuiInputTextFlags.None);

        ImGui.dummy(0, 6f);
        fieldLabel("Directory");
        float browseW = 80f;
        ImGui.setNextItemWidth(ImGui.getContentRegionAvailX() - browseW - 8f);
        ImGui.inputTextWithHint("##np_dir", "Choose a folder...", projectDir, ImGuiInputTextFlags.None);
        ImGui.sameLine(0, 8f);
        if (ImGui.button("Browse", browseW, 0)) {
            actionService.pickFolder(projectDir::set);
        }

        ImGui.dummy(0, 8f);
        boolean valid = !projectName.get().isBlank() && !projectDir.get().isBlank();
        ImGui.beginDisabled(!valid);
        boolean create = accentButton("Create Project");
        ImGui.endDisabled();
        if (create && valid) {
            actionService.createProject(projectName.get(), projectDir.get());
        }
    }

    private void fieldLabel(String text) {
        ImVec4 c = ImGui.getStyle().getColor(ImGuiCol.Text);
        ImGui.pushStyleColor(ImGuiCol.Text, c.x, c.y, c.z, 0.6f);
        ImGui.text(text);
        ImGui.popStyleColor();
    }

    // ---- hero -------------------------------------------------------------

    private void renderHero(String name, String badge) {
        float width = ImGui.getContentRegionAvailX();
        if (width < 1f) {
            return;
        }
        heroRegion.begin(width, HERO_H);
        heroRegion.add("hero", 0f, 0f, width, HERO_H, (g, x, y, w, h, st) -> {
            // Thumbnail placeholder with a large faint monogram.
            g.fillRoundRect(x, y, w, THUMB_H, 8f, g.theme().surfaceHover);
            g.strokeRoundRect(x, y, w, THUMB_H, 8f, 1f, g.theme().border);
            String monogram = name == null || name.isEmpty() ? "?"
                    : name.substring(0, 1).toUpperCase();
            g.text(monogram, x + w / 2f, y + THUMB_H / 2f, MortarPainter.Align.CENTER,
                    Weight.BOLD, 48f, Argb.withAlpha(g.theme().text, 0.18f));
            // Name (wrapped, never truncated) + badge below the thumbnail.
            float nameTop = y + THUMB_H + 12f;
            float nameH = g.textWrapped(name == null ? "" : name, x, nameTop, w,
                    Weight.MEDIUM, 16f, g.theme().text, 2);
            if (badge != null && !badge.isEmpty()) {
                MortarBadge.paint(g, x, nameTop + nameH + 12f, badge);
            }
        });
        heroRegion.render();
    }

    // ---- template ---------------------------------------------------------

    private void renderTemplatePreview(ProjectTemplate template) {
        renderHero(template.getName(), template.getCategory());
        ImGui.dummy(0, 8f);

        // Name + directory + Create, directly under the hero.
        renderCreateForm();

        ImGui.dummy(0, 6f);
        ImGui.separator();
        ImGui.dummy(0, 6f);

        if (!template.getMetadata().isEmpty()) {
            for (String key : template.getMetadata().keySet()) {
                labelValue(key, template.getMetadataValue(key));
            }
        }

        if (!template.getDescription().isEmpty()) {
            ImGui.dummy(0, 6f);
            ImGui.separator();
            ImGui.dummy(0, 6f);
            dimWrapped(template.getDescription(), 0.85f);
        }
    }

    // ---- project ----------------------------------------------------------

    private void renderProjectPreview(RecentProject project) {
        String badge = project.getSourceTemplate() != null ? project.getSourceTemplate().getName() : null;
        renderHero(project.getName(), badge);
        ImGui.dummy(0, 8f);

        if (accentButton("Open Project")) {
            actionService.openRecentProject(project);
        }

        ImGui.dummy(0, 6f);
        ImGui.separator();
        ImGui.dummy(0, 6f);

        labelValue("Last Opened", formatLastOpened(project.getLastOpened()));
        ImGui.dummy(0, 4f);
        locationRow(project.getPath());

        if (!project.getDescription().isEmpty()) {
            ImGui.dummy(0, 6f);
            ImGui.separator();
            ImGui.dummy(0, 6f);
            dimWrapped(project.getDescription(), 0.7f);
        }

        ImGui.dummy(0, 8f);
        ImGui.separator();
        ImGui.dummy(0, 8f);

        ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, 5f);
        float availW = ImGui.getContentRegionAvailX();
        float halfW = (availW - 8f) / 2f;
        if (ImGui.button("Open Folder", halfW, BUTTON_HEIGHT)) {
            openContainingFolder(project.getPath());
        }
        ImGui.sameLine(0, 8f);
        if (ImGui.button("Rename", halfW, BUTTON_HEIGHT) && renameDialog != null) {
            renameDialog.show(project.getId(), project.getName(), this::handleRename);
        }
        ImGui.dummy(0, 6f);
        ImGui.pushStyleColor(ImGuiCol.Button, 0.45f, 0.12f, 0.12f, 0.5f);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0.6f, 0.15f, 0.15f, 0.8f);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, 0.7f, 0.2f, 0.2f, 1.0f);
        if (ImGui.button("Remove Project", -1, BUTTON_HEIGHT) && deleteDialog != null) {
            deleteDialog.show(project, this::handleDelete);
        }
        ImGui.popStyleColor(3);
        ImGui.popStyleVar();
    }

    // ---- ImGui helpers ----------------------------------------------------

    private boolean accentButton(String label) {
        ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, 5f);
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 0f, 8f);
        ImVec4 accent = ImGui.getStyle().getColor(ImGuiCol.HeaderActive);
        ImGui.pushStyleColor(ImGuiCol.Button, accent.x, accent.y, accent.z, 0.85f);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, accent.x, accent.y, accent.z, 1.0f);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, accent.x * 0.8f, accent.y * 0.8f, accent.z * 0.8f, 1.0f);
        boolean clicked = ImGui.button(label, -1, BUTTON_HEIGHT + 4);
        ImGui.popStyleColor(3);
        ImGui.popStyleVar(2);
        return clicked;
    }

    private void labelValue(String label, String value) {
        ImVec4 c = ImGui.getStyle().getColor(ImGuiCol.Text);
        ImGui.pushStyleColor(ImGuiCol.Text, c.x, c.y, c.z, 0.6f);
        ImGui.text(label + ":");
        ImGui.popStyleColor();
        ImGui.sameLine();
        ImGui.text(value != null ? value : "");
    }

    private void locationRow(String path) {
        ImVec4 c = ImGui.getStyle().getColor(ImGuiCol.Text);
        ImGui.pushStyleColor(ImGuiCol.Text, c.x, c.y, c.z, 0.6f);
        ImGui.text("Location:");
        ImGui.popStyleColor();
        ImGui.pushStyleColor(ImGuiCol.Text, c.x, c.y, c.z, 0.8f);
        ImGui.pushTextWrapPos(ImGui.getContentRegionAvailX() + ImGui.getCursorPosX());
        ImGui.textWrapped(path != null ? path : "");
        ImGui.popTextWrapPos();
        ImGui.popStyleColor();
    }

    private void dimWrapped(String text, float alpha) {
        ImVec4 c = ImGui.getStyle().getColor(ImGuiCol.Text);
        ImGui.pushStyleColor(ImGuiCol.Text, c.x, c.y, c.z, alpha);
        ImGui.pushTextWrapPos(ImGui.getContentRegionAvailX() + ImGui.getCursorPosX());
        ImGui.textWrapped(text);
        ImGui.popTextWrapPos();
        ImGui.popStyleColor();
    }

    // ---- utility ----------------------------------------------------------

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
        if (filePath == null || filePath.isBlank()) {
            return;
        }
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

    private void handleRename(String projectId, String newName) {
        recentProjectsService.renameProject(projectId, newName);
        RecentProject selected = hubState.getSelectedRecentProject();
        if (selected != null && selected.getId().equals(projectId)) {
            hubState.setSelectedRecentProject(null);
        }
    }

    private void handleDelete(RecentProject project, boolean deleteFile) {
        if (deleteFile) {
            recentProjectsService.deleteProjectFile(project);
        }
        recentProjectsService.removeProject(project.getId());
        if (project.equals(hubState.getSelectedRecentProject())) {
            hubState.setSelectedRecentProject(null);
        }
    }

    public void update(float deltaTime) {
        heroRegion.update(deltaTime);
    }

    public void dispose() {
        heroRegion.close();
    }
}
