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
import com.openmason.main.systems.mortar.parts.MortarListRow;
import com.openmason.main.systems.themes.core.ThemeManager;
import imgui.ImGui;
import imgui.ImVec4;
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
    private static final float ROW_H = 56f;
    private static final float ROW_GAP = 8f;
    private static final float ROW_MAX_W = 480f;
    private static final float GAP = 12f;
    private static final float TOGGLE_BTN_W = 52f;
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

        renderHeader(projects.size());

        float availW = ImGui.getContentRegionAvailX();
        boolean list = hubState.getRecentViewMode() == HubState.RecentViewMode.LIST;

        float height = list
                ? projects.size() * (ROW_H + ROW_GAP) - ROW_GAP
                : gridRows(projects.size(), availW) * (CARD_H + GAP) - GAP;

        region.begin(availW, height);

        if (list) {
            layoutList(projects, availW, 0f);
        } else {
            layoutGrid(projects, availW, 0f);
        }

        MortarFrameResult input = region.render();
        handleInput(input);
        renderContextMenu();
    }

    /** Responsive grid of {@link MortarCard} tiles. */
    private void layoutGrid(List<RecentProject> projects, float availW, float top) {
        int cols = gridCols(availW);
        float cardW = (availW - GAP * (cols - 1)) / cols;
        for (int i = 0; i < projects.size(); i++) {
            RecentProject p = projects.get(i);
            float x = (i % cols) * (cardW + GAP);
            float y = top + (i / cols) * (CARD_H + GAP);
            boolean selected = p.equals(hubState.getSelectedRecentProject());
            // Title (name) wraps; the meta line pairs relative time with the
            // folder; the file name is the faint footer.
            String meta = relativeTime(p.getLastOpened()) + "   ·   " + folderName(p.getPath());
            MortarCard card = new MortarCard(p.getName(), meta, fileName(p.getPath()));
            region.add("recent." + i, x, y, cardW, CARD_H, selected, card);
        }
    }

    /** Single-column list of informative {@link MortarListRow}s. */
    private void layoutList(List<RecentProject> projects, float availW, float top) {
        float rowW = Math.min(availW, ROW_MAX_W);
        for (int i = 0; i < projects.size(); i++) {
            RecentProject p = projects.get(i);
            float y = top + i * (ROW_H + ROW_GAP);
            boolean selected = p.equals(hubState.getSelectedRecentProject());
            // Title (name) on the left; the folder/file location as a dimmed
            // subtitle; relative time pinned to the right.
            String subtitle = folderName(p.getPath()) + "   ·   " + fileName(p.getPath());
            MortarListRow row = new MortarListRow(p.getName(), subtitle, relativeTime(p.getLastOpened()));
            region.add("recent." + i, 0f, y, rowW, ROW_H, selected, row);
        }
    }

    private static int gridCols(float availW) {
        return Math.max(1, (int) ((availW + GAP) / (MIN_CARD_W + GAP)));
    }

    private static int gridRows(int count, float availW) {
        int cols = gridCols(availW);
        return (count + cols - 1) / cols;
    }

    /**
     * The section header band: an upper-cased "RECENT PROJECTS (N)" heading on
     * the left with the Grid/List toggle pinned to the right of the same row,
     * then a separator that distinctly divides the header from the list below.
     * {@link ImGui#alignTextToFramePadding()} centres the heading against the
     * buttons so the whole row shares one baseline.
     */
    private void renderHeader(int count) {
        HubState.RecentViewMode mode = hubState.getRecentViewMode();
        float startX = ImGui.getCursorPosX();
        float availW = ImGui.getContentRegionAvailX();
        float spacing = ImGui.getStyle().getItemSpacingX();
        float groupW = TOGGLE_BTN_W * 2f + spacing;

        ImGui.dummy(0f, 2f);

        // Heading, upper-cased and near-full strength so it clearly reads as a
        // section title rather than body text. Aligned to the buttons' baseline.
        ImGui.alignTextToFramePadding();
        ImVec4 t = ImGui.getStyle().getColor(ImGuiCol.Text);
        ImGui.pushStyleColor(ImGuiCol.Text, t.x, t.y, t.z, 0.85f);
        ImGui.text("RECENT PROJECTS  (" + count + ")");
        ImGui.popStyleColor();

        // Toggle right-aligned on the same row; the two buttons stay paired via
        // sameLine() with no manual cursor-Y nudging.
        ImGui.sameLine();
        ImGui.setCursorPosX(startX + Math.max(0f, availW - groupW));
        toggleButton("Grid", mode == HubState.RecentViewMode.GRID, HubState.RecentViewMode.GRID);
        ImGui.sameLine();
        toggleButton("List", mode == HubState.RecentViewMode.LIST, HubState.RecentViewMode.LIST);

        ImGui.dummy(0f, 6f);
        ImGui.separator();
        ImGui.dummy(0f, 12f);
    }

    private void toggleButton(String label, boolean active, HubState.RecentViewMode target) {
        if (active) {
            ImVec4 c = ImGui.getStyle().getColor(ImGuiCol.ButtonActive);
            ImGui.pushStyleColor(ImGuiCol.Button, c.x, c.y, c.z, c.w);
        }
        if (ImGui.button(label + "##recent_view", TOGGLE_BTN_W, 0f)) {
            hubState.setRecentViewMode(target);
        }
        if (active) {
            ImGui.popStyleColor();
        }
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
