package com.openmason.main.systems.menus.mainHub.components;

import com.openmason.main.systems.menus.mainHub.dialogs.DeleteProjectDialog;
import com.openmason.main.systems.menus.mainHub.dialogs.RenameProjectDialog;
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
import imgui.flag.ImGuiMouseCursor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Center panel showing recent projects in a card grid.
 * Single Responsibility: Display and manage recent projects.
 */
public class RecentProjectsPanel {

    private static final Logger logger = LoggerFactory.getLogger(RecentProjectsPanel.class);

    // Grid layout
    private static final int COLUMNS = 2;
    private static final float GAP = 8.0f;
    private static final float CARD_ROUNDING = 6.0f;
    private static final float CARD_PAD = 12.0f;
    private static final float ACCENT_W = 3.0f;

    // Internal card zones
    private static final float HEADER_ZONE = 32.0f;   // Project name + time
    private static final float DETAIL_ZONE = 42.0f;   // Folder + filename in sub-container
    private static final float SEP_GAP = 1.0f;        // Separator line
    private static final float CARD_HEIGHT = CARD_PAD + HEADER_ZONE + SEP_GAP + DETAIL_ZONE + CARD_PAD;

    private final ThemeManager themeManager;
    private final HubState hubState;
    private final RecentProjectsService recentProjectsService;
    private final HubActionService actionService;

    private final RenameProjectDialog renameDialog = new RenameProjectDialog();
    private final DeleteProjectDialog deleteDialog = new DeleteProjectDialog();

    public RecentProjectsPanel(ThemeManager themeManager, HubState hubState,
                               RecentProjectsService recentProjectsService,
                               HubActionService actionService) {
        this.themeManager = themeManager;
        this.hubState = hubState;
        this.recentProjectsService = recentProjectsService;
        this.actionService = actionService;
    }

    public void render() {
        String query = hubState.getSearchQuery();
        List<RecentProject> projects = query.isEmpty()
                ? recentProjectsService.getRecentProjects()
                : recentProjectsService.search(query);

        if (projects.isEmpty()) {
            renderEmptyState();
            return;
        }

        ThemeDefinition theme = themeManager.getCurrentTheme();

        // Section header — dimmed using theme colors
        ImVec4 headerText = theme.getColor(ImGuiCol.Text);
        ImVec4 headerBg = theme.getColor(ImGuiCol.WindowBg);
        if (headerText == null) headerText = new ImVec4(1, 1, 1, 1);
        if (headerBg == null) headerBg = new ImVec4(0, 0, 0, 1);
        pushLerpedTextColor(headerText, headerBg, 0.5f);
        ImGui.text("RECENT PROJECTS  (" + projects.size() + ")");
        ImGui.popStyleColor();
        ImGui.spacing();

        // Grid layout
        float availW = ImGui.getContentRegionAvailX();
        float cardW = (availW - GAP * (COLUMNS - 1)) / COLUMNS;
        ImVec2 gridOrigin = ImGui.getCursorScreenPos();

        for (int i = 0; i < projects.size(); i++) {
            int col = i % COLUMNS;
            int row = i / COLUMNS;

            float x = gridOrigin.x + col * (cardW + GAP);
            float y = gridOrigin.y + row * (CARD_HEIGHT + GAP);

            renderCard(projects.get(i), i, x, y, cardW, theme);
        }

        // Advance cursor past all rows
        int totalRows = (projects.size() + COLUMNS - 1) / COLUMNS;
        float totalHeight = totalRows * (CARD_HEIGHT + GAP);
        ImGui.setCursorScreenPos(gridOrigin.x, gridOrigin.y + totalHeight);
        ImGui.dummy(availW, 0);
    }

    // ========== Card Rendering ==========

    private void renderCard(RecentProject project, int index, float x, float y,
                            float cardW, ThemeDefinition theme) {
        ImDrawList dl = ImGui.getWindowDrawList();

        float x2 = x + cardW;
        float y2 = y + CARD_HEIGHT;

        boolean selected = project.equals(hubState.getSelectedRecentProject());
        boolean hovered = ImGui.isMouseHoveringRect(x, y, x2, y2);
        if (hovered) ImGui.setMouseCursor(ImGuiMouseCursor.Hand);

        // --- Card background — darker than the panel to stand out ---
        ImVec4 panelBg = theme.getColor(ImGuiCol.ChildBg);
        if (panelBg == null) panelBg = new ImVec4(0.15f, 0.15f, 0.15f, 1.0f);
        // Darken for dark themes, lighten for light themes
        float shift = (panelBg.x + panelBg.y + panelBg.z) / 3.0f < 0.5f ? -0.04f : 0.04f;
        ImVec4 bgColor = new ImVec4(
                Math.max(0, panelBg.x + shift),
                Math.max(0, panelBg.y + shift),
                Math.max(0, panelBg.z + shift),
                1.0f);

        // --- Border + shadow states (matching template card style) ---
        ImVec4 borderColor;
        float borderThickness;
        float shadowOffset;
        float shadowAlpha;

        if (selected) {
            borderColor = theme.getColor(ImGuiCol.Header);
            if (borderColor == null) borderColor = new ImVec4(0.26f, 0.59f, 0.98f, 1.0f);
            borderThickness = 3.0f;
            shadowOffset = 6.0f;
            shadowAlpha = 0.3f;
        } else if (hovered) {
            borderColor = theme.getColor(ImGuiCol.ButtonHovered);
            if (borderColor == null) borderColor = new ImVec4(0.4f, 0.4f, 0.4f, 1.0f);
            borderThickness = 2.0f;
            shadowOffset = 5.0f;
            shadowAlpha = 0.25f;
        } else {
            borderColor = theme.getColor(ImGuiCol.Border);
            if (borderColor == null) borderColor = new ImVec4(0.3f, 0.3f, 0.3f, 1.0f);
            borderThickness = 1.0f;
            shadowOffset = 3.0f;
            shadowAlpha = 0.15f;
        }

        // Drop shadow
        dl.addRectFilled(x + shadowOffset, y + shadowOffset,
                x2 + shadowOffset, y2 + shadowOffset,
                ImColor.rgba(0, 0, 0, shadowAlpha), CARD_ROUNDING);

        // Card fill
        dl.addRectFilled(x, y, x2, y2,
                ImColor.rgba(bgColor.x, bgColor.y, bgColor.z, bgColor.w), CARD_ROUNDING);

        // Border
        dl.addRect(x, y, x2, y2,
                ImColor.rgba(borderColor.x, borderColor.y, borderColor.z, 1.0f),
                CARD_ROUNDING, 0, borderThickness);

        // --- Left accent bar (selected only) ---
        if (selected) {
            ImVec4 accent = theme.getColor(ImGuiCol.ButtonHovered);
            if (accent == null) accent = new ImVec4(0.4f, 0.5f, 0.9f, 1.0f);
            dl.addRectFilled(x + 2, y + 8, x + 2 + ACCENT_W, y2 - 8,
                    ImColor.rgba(accent.x, accent.y, accent.z, 1.0f), ACCENT_W / 2);
        }

        // --- Layout zones ---
        ImVec4 tc = theme.getColor(ImGuiCol.Text);
        if (tc == null) tc = new ImVec4(1.0f, 1.0f, 1.0f, 1.0f);

        float contentL = x + CARD_PAD + (selected ? ACCENT_W + 2 : 0);
        float contentR = x2 - CARD_PAD;
        float headerY = y + CARD_PAD;
        float sepY = headerY + HEADER_ZONE;
        float detailY = sepY + SEP_GAP;

        // ===== HEADER ZONE: Project name + time =====
        // Project name — scaled up
        ImGui.setCursorScreenPos(contentL, headerY);
        ImGui.setWindowFontScale(1.15f);
        ImGui.pushStyleColor(ImGuiCol.Text, tc.x, tc.y, tc.z, 1.0f);
        ImGui.text(project.getName());
        ImGui.popStyleColor();
        ImGui.setWindowFontScale(1.0f);

        // Time — right-aligned in header
        String timeStr = formatTime(project.getLastOpened());
        ImVec2 timeSz = ImGui.calcTextSize(timeStr);
        ImGui.setCursorScreenPos(contentR - timeSz.x, headerY + 4);
        ImGui.setWindowFontScale(0.9f);
        ImGui.pushStyleColor(ImGuiCol.Text, tc.x, tc.y, tc.z, 1.0f);
        ImGui.text(timeStr);
        ImGui.popStyleColor();
        ImGui.setWindowFontScale(1.0f);

        // ===== SEPARATOR LINE =====
        ImVec4 sepColor = theme.getColor(ImGuiCol.Separator);
        if (sepColor == null) sepColor = new ImVec4(0.3f, 0.3f, 0.3f, 1.0f);
        dl.addLine(contentL, sepY, contentR, sepY,
                ImColor.rgba(sepColor.x, sepColor.y, sepColor.z, 0.4f), 1.0f);

        // ===== DETAIL ZONE: Folder + file in a tinted sub-container =====
        float detailPad = 8.0f;
        float detailInnerL = x + detailPad;
        float detailInnerR = x2 - detailPad;
        float detailBoxTop = detailY + 4;
        float detailBoxBot = y2 - CARD_PAD + 4;

        // Sub-container background — slightly darker/lighter than card bg
        ImVec4 detailBg = theme.getColor(ImGuiCol.FrameBg);
        if (detailBg == null) detailBg = new ImVec4(bgColor.x - 0.02f, bgColor.y - 0.02f, bgColor.z - 0.02f, 1.0f);
        dl.addRectFilled(detailInnerL, detailBoxTop, detailInnerR, detailBoxBot,
                ImColor.rgba(detailBg.x, detailBg.y, detailBg.z, 0.5f), 4.0f);

        // Folder name
        String folder = extractFolderName(project.getPath());
        ImGui.setCursorScreenPos(detailInnerL + 6, detailBoxTop + 4);
        ImGui.pushStyleColor(ImGuiCol.Text, tc.x, tc.y, tc.z, 1.0f);
        ImGui.text(folder);
        ImGui.popStyleColor();

        // File name — smaller, below folder
        String fileName = extractFileName(project.getPath());
        if (!fileName.isEmpty()) {
            ImGui.setCursorScreenPos(detailInnerL + 6, detailBoxTop + 20);
            ImGui.setWindowFontScale(0.88f);
            ImGui.pushStyleColor(ImGuiCol.Text, tc.x, tc.y, tc.z, 1.0f);
            ImGui.text(fileName);
            ImGui.popStyleColor();
            ImGui.setWindowFontScale(1.0f);
        }

        // --- Invisible button for click + context menu ---
        ImGui.setCursorScreenPos(x, y);
        ImGui.pushStyleColor(ImGuiCol.Button, 0);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, 0);
        if (ImGui.button("##proj_" + index, cardW, CARD_HEIGHT)) {
            hubState.setSelectedRecentProject(project);
        }
        if (ImGui.isItemHovered() && ImGui.isMouseDoubleClicked(0)) {
            hubState.setSelectedRecentProject(project);
            actionService.openRecentProject(project);
        }
        ImGui.popStyleColor(3);

        // Context menu
        if (ImGui.beginPopupContextItem("ctx_" + index)) {
            if (ImGui.menuItem("Open")) {
                hubState.setSelectedRecentProject(project);
                actionService.openRecentProject(project);
            }
            ImGui.separator();
            if (ImGui.menuItem("Rename...")) {
                renameDialog.show(project.getId(), project.getName(), this::handleRename);
            }
            if (ImGui.menuItem("Remove from Recent")) {
                deleteDialog.show(project, this::handleDelete);
            }
            ImGui.endPopup();
        }
    }

    // ========== Empty State ==========

    private void renderEmptyState() {
        ThemeDefinition theme = themeManager.getCurrentTheme();
        ImVec4 tc = theme.getColor(ImGuiCol.Text);
        ImVec4 bg = theme.getColor(ImGuiCol.WindowBg);
        if (tc == null) tc = new ImVec4(1, 1, 1, 1);
        if (bg == null) bg = new ImVec4(0, 0, 0, 1);
        float ww = ImGui.getWindowWidth();

        ImGui.spacing();
        ImGui.spacing();
        ImGui.spacing();

        String msg = hubState.getSearchQuery().isEmpty()
                ? "No recent projects" : "No projects match your search";
        centerText(msg, tc, bg, 0.4f, ww);

        ImGui.spacing();
        centerText("Saved projects will appear here", tc, bg, 0.6f, ww);
    }

    private void centerText(String text, ImVec4 textColor, ImVec4 bgColor, float mix, float windowWidth) {
        ImVec2 sz = ImGui.calcTextSize(text);
        ImGui.setCursorPosX((windowWidth - sz.x) * 0.5f);
        pushLerpedTextColor(textColor, bgColor, mix);
        ImGui.text(text);
        ImGui.popStyleColor();
    }

    // ========== Color Helpers ==========

    /**
     * Push a text color that lerps between the text color and a target (usually background).
     * mix=0 → pure text color, mix=1 → pure target. Works for both light and dark themes.
     */
    private void pushLerpedTextColor(ImVec4 text, ImVec4 target, float mix) {
        float r = text.x + (target.x - text.x) * mix;
        float g = text.y + (target.y - text.y) * mix;
        float b = text.z + (target.z - text.z) * mix;
        ImGui.pushStyleColor(ImGuiCol.Text, r, g, b, 1.0f);
    }

    // ========== Utility ==========

    private String extractFolderName(String filePath) {
        if (filePath == null || filePath.isBlank()) return "";
        try {
            Path parent = Path.of(filePath).getParent();
            if (parent != null) {
                Path name = parent.getFileName();
                return name != null ? name.toString() : parent.toString();
            }
        } catch (Exception ignored) {}
        return "";
    }

    private String extractFileName(String filePath) {
        if (filePath == null || filePath.isBlank()) return "";
        try {
            Path name = Path.of(filePath).getFileName();
            return name != null ? name.toString() : "";
        } catch (Exception ignored) {}
        return "";
    }

    private String formatTime(LocalDateTime lastOpened) {
        LocalDateTime now = LocalDateTime.now();
        long min = ChronoUnit.MINUTES.between(lastOpened, now);
        if (min < 1) return "Just now";
        if (min < 60) return min + "m";
        long hrs = ChronoUnit.HOURS.between(lastOpened, now);
        if (hrs < 24) return hrs + "h";
        long days = ChronoUnit.DAYS.between(lastOpened, now);
        if (days < 7) return days + "d";
        if (days < 28) return (days / 7) + "w";
        return lastOpened.format(DateTimeFormatter.ofPattern("MMM d"));
    }

    // ========== Callbacks ==========

    private void handleRename(String projectId, String newName) {
        recentProjectsService.renameProject(projectId, newName);
        RecentProject sel = hubState.getSelectedRecentProject();
        if (sel != null && sel.getId().equals(projectId)) {
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

    public RenameProjectDialog getRenameDialog() { return renameDialog; }
    public DeleteProjectDialog getDeleteDialog() { return deleteDialog; }
}
