package com.openmason.main.systems.menus.mainHub.components;

import com.openmason.main.systems.menus.mainHub.dialogs.DeleteProjectDialog;
import com.openmason.main.systems.menus.mainHub.dialogs.RenameProjectDialog;
import com.openmason.main.systems.menus.mainHub.model.RecentProject;
import com.openmason.main.systems.menus.mainHub.services.HubActionService;
import com.openmason.main.systems.menus.mainHub.services.RecentProjectsService;
import com.openmason.main.systems.menus.mainHub.state.HubState;
import com.openmason.main.systems.mortar.core.MortarFrameResult;
import com.openmason.main.systems.mortar.core.MortarRegion;
import com.openmason.main.systems.mortar.parts.MortarCard;
import com.openmason.main.systems.mortar.parts.MortarSectionLabel;
import com.openmason.main.systems.themes.core.ThemeManager;
import imgui.ImGui;
import imgui.flag.ImGuiCol;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Recent-projects grid, painted as a single Skija {@link MortarRegion} of
 * {@link MortarCard}s with a responsive column count. Owns its data service and
 * the rename/remove dialogs; selection, double-click-to-open and a right-click
 * context menu are routed from the region's hit-test. The contextual preview
 * panel reacts to the selection this panel sets.
 */
public class RecentProjectsPanel {

    private static final float MIN_CARD_W = 210f;
    private static final float CARD_H = 104f;
    private static final float GAP = 12f;
    private static final float LABEL_H = 18f;
    private static final float LABEL_GAP = 10f;
    private static final String CTX_POPUP = "##recent_ctx";

    private final HubState hubState;
    private final RecentProjectsService recentProjectsService;
    private final HubActionService actionService;
    private final MortarRegion region = new MortarRegion();

    private final RenameProjectDialog renameDialog = new RenameProjectDialog();
    private final DeleteProjectDialog deleteDialog = new DeleteProjectDialog();

    /** Filtered list backing the current frame's card ids ("recent.<index>"). */
    private List<RecentProject> frameProjects = List.of();
    private RecentProject contextProject;

    public RecentProjectsPanel(ThemeManager themeManager, HubState hubState,
                               RecentProjectsService recentProjectsService,
                               HubActionService actionService) {
        this.hubState = hubState;
        this.recentProjectsService = recentProjectsService;
        this.actionService = actionService;
    }

    public void render() {
        String query = hubState.getSearchQuery();
        List<RecentProject> projects = query.isEmpty()
                ? recentProjectsService.getRecentProjects()
                : recentProjectsService.search(query);
        this.frameProjects = projects;

        if (projects.isEmpty()) {
            renderEmptyState(query);
            return;
        }

        float availW = ImGui.getContentRegionAvailX();
        int cols = Math.max(1, (int) ((availW + GAP) / (MIN_CARD_W + GAP)));
        float cardW = (availW - GAP * (cols - 1)) / cols;
        int rows = (projects.size() + cols - 1) / cols;

        float gridTop = LABEL_H + LABEL_GAP;
        float height = gridTop + rows * (CARD_H + GAP) - GAP;

        region.begin(availW, height);
        region.add("label", 0f, 0f, availW, LABEL_H,
                new MortarSectionLabel("Recent Projects  (" + projects.size() + ")"));

        for (int i = 0; i < projects.size(); i++) {
            RecentProject p = projects.get(i);
            int col = i % cols;
            int row = i / cols;
            float x = col * (cardW + GAP);
            float y = gridTop + row * (CARD_H + GAP);
            boolean selected = p.equals(hubState.getSelectedRecentProject());
            // Title (name) wraps; the meta line pairs relative time with the
            // folder; the file name is the faint footer.
            String meta = relativeTime(p.getLastOpened()) + "   ·   " + folderName(p.getPath());
            MortarCard card = new MortarCard(p.getName(), meta, fileName(p.getPath()));
            region.add("recent." + i, x, y, cardW, CARD_H, selected, card);
        }

        MortarFrameResult input = region.render();
        handleInput(input);
        renderContextMenu();
    }

    private void handleInput(MortarFrameResult input) {
        RecentProject clicked = resolve(input.clicked());
        if (clicked != null) {
            if (clicked.equals(hubState.getSelectedRecentProject())) {
                hubState.setSelectedRecentProject(null); // toggle off
            } else {
                hubState.setSelectedTemplate(null);
                hubState.setSelectedRecentProject(clicked);
            }
        }
        RecentProject opened = resolve(input.doubleClicked());
        if (opened != null) {
            hubState.setSelectedRecentProject(opened);
            actionService.openRecentProject(opened);
        }
        RecentProject ctx = resolve(input.rightClicked());
        if (ctx != null) {
            contextProject = ctx;
            hubState.setSelectedRecentProject(ctx);
            ImGui.openPopup(CTX_POPUP);
        }
    }

    private void renderContextMenu() {
        if (contextProject == null) {
            return;
        }
        if (ImGui.beginPopup(CTX_POPUP)) {
            if (ImGui.menuItem("Open")) {
                actionService.openRecentProject(contextProject);
            }
            ImGui.separator();
            if (ImGui.menuItem("Rename...")) {
                renameDialog.show(contextProject.getId(), contextProject.getName(), this::handleRename);
            }
            if (ImGui.menuItem("Remove from Recent")) {
                deleteDialog.show(contextProject, this::handleDelete);
            }
            ImGui.endPopup();
        }
    }

    private RecentProject resolve(String partId) {
        if (partId == null || !partId.startsWith("recent.")) {
            return null;
        }
        try {
            int index = Integer.parseInt(partId.substring("recent.".length()));
            if (index >= 0 && index < frameProjects.size()) {
                return frameProjects.get(index);
            }
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    private void renderEmptyState(String query) {
        ImGui.dummy(0, 8f);
        String msg = query.isEmpty() ? "No recent projects yet"
                : "No projects match your search";
        centered(msg, 0.7f);
        ImGui.dummy(0, 4f);
        centered("Create a project to get started", 0.45f);
    }

    private void centered(String text, float alpha) {
        float w = ImGui.getContentRegionAvailX();
        float tw = ImGui.calcTextSize(text).x;
        ImGui.setCursorPosX(ImGui.getCursorPosX() + Math.max(0f, (w - tw) / 2f));
        var c = ImGui.getStyle().getColor(ImGuiCol.Text);
        ImGui.pushStyleColor(ImGuiCol.Text, c.x, c.y, c.z, alpha);
        ImGui.text(text);
        ImGui.popStyleColor();
    }

    public void update(float deltaTime) {
        region.update(deltaTime);
    }

    public void dispose() {
        region.close();
    }

    // ---- formatting -------------------------------------------------------

    private static String folderName(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return "";
        }
        try {
            Path parent = Path.of(filePath).getParent();
            if (parent != null) {
                Path name = parent.getFileName();
                return name != null ? name.toString() : parent.toString();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String fileName(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return "";
        }
        try {
            Path name = Path.of(filePath).getFileName();
            return name != null ? name.toString() : "";
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String relativeTime(LocalDateTime lastOpened) {
        LocalDateTime now = LocalDateTime.now();
        long min = ChronoUnit.MINUTES.between(lastOpened, now);
        if (min < 1) return "now";
        if (min < 60) return min + "m";
        long hrs = ChronoUnit.HOURS.between(lastOpened, now);
        if (hrs < 24) return hrs + "h";
        long days = ChronoUnit.DAYS.between(lastOpened, now);
        if (days < 7) return days + "d";
        if (days < 28) return (days / 7) + "w";
        return lastOpened.format(DateTimeFormatter.ofPattern("MMM d"));
    }

    // ---- dialog callbacks -------------------------------------------------

    private void handleRename(String projectId, String newName) {
        recentProjectsService.renameProject(projectId, newName);
        RecentProject sel = hubState.getSelectedRecentProject();
        if (sel != null && sel.getId().equals(projectId)) {
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

    public RenameProjectDialog getRenameDialog() {
        return renameDialog;
    }

    public DeleteProjectDialog getDeleteDialog() {
        return deleteDialog;
    }
}
