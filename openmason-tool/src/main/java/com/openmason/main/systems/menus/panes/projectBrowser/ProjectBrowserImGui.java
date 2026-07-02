package com.openmason.main.systems.menus.panes.projectBrowser;

import com.openmason.main.systems.menus.panes.projectBrowser.sorting.SortOrder;
import com.openmason.main.systems.menus.panes.projectBrowser.sorting.SortBy;
import com.openmason.main.systems.menus.panes.projectBrowser.thumbnails.ModelThumbnailRenderer;
import com.openmason.main.systems.menus.panes.projectBrowser.thumbnails.OMTThumbnailRenderer;
import com.openmason.main.systems.menus.panes.projectBrowser.thumbnails.ThumbnailCache;
import com.openmason.main.systems.menus.panes.projectBrowser.views.CompactListRenderer;
import com.openmason.main.systems.menus.panes.projectBrowser.views.GridViewRenderer;
import com.openmason.main.systems.menus.panes.projectBrowser.views.ListViewRenderer;
import com.openmason.main.systems.menus.panes.projectBrowser.views.ViewMode;
import com.openmason.main.systems.menus.panes.projectBrowser.views.ViewRenderer;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * The Project Browser window: lists the .OMO models and .OMT textures found
 * in the folder the open .OMP project lives in. Replaces the old Model
 * Browser (which scanned globally configured asset folders).
 */
public class ProjectBrowserImGui {

    private static final Logger logger = LoggerFactory.getLogger(ProjectBrowserImGui.class);

    public static final String WINDOW_TITLE = "Project Browser";

    private static final float SEARCH_BAR_WIDTH = 220.0f;

    private final ProjectBrowserController controller;
    private final ImBoolean visible;
    private final Runnable newModelCallback;

    // One shared thumbnail cache across all view modes.
    private final ThumbnailCache thumbnailCache = new ThumbnailCache();

    private final ViewRenderer gridViewRenderer;
    private final ViewRenderer listViewRenderer;
    private final ViewRenderer compactListRenderer;

    /**
     * @param controller       the controller managing scanning and selection
     * @param visible          the visibility state (shared with UIVisibilityState)
     * @param newModelCallback invoked by the "New Model" button
     */
    public ProjectBrowserImGui(ProjectBrowserController controller, ImBoolean visible, Runnable newModelCallback) {
        this.controller = controller;
        this.visible = visible;
        this.newModelCallback = newModelCallback;

        ModelThumbnailRenderer modelRenderer = new ModelThumbnailRenderer(thumbnailCache);
        OMTThumbnailRenderer omtRenderer = new OMTThumbnailRenderer(thumbnailCache);
        this.gridViewRenderer = new GridViewRenderer(controller, thumbnailCache, modelRenderer, omtRenderer);
        this.listViewRenderer = new ListViewRenderer(controller, modelRenderer, omtRenderer);
        this.compactListRenderer = new CompactListRenderer(controller, modelRenderer, omtRenderer);

        logger.debug("ProjectBrowserImGui initialized");
    }

    /** Renders the Project Browser window. Called every frame when visible. */
    public void render() {
        if (!visible.get()) {
            return;
        }

        ImGui.setNextWindowSize(900, 400, imgui.flag.ImGuiCond.FirstUseEver);
        ImGui.setNextWindowPos(100, 100, imgui.flag.ImGuiCond.FirstUseEver);

        if (ImGui.begin(WINDOW_TITLE, visible, ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse)) {
            if (!controller.hasProjectOpen()) {
                renderNoProjectState();
            } else {
                renderToolbar();

                ImGui.spacing();
                ImGui.separator();
                ImGui.spacing();

                ImVec2 region = ImGui.getContentRegionAvail();
                float footerHeight = ImGui.getTextLineHeightWithSpacing() + 12f;
                if (ImGui.beginChild("##ProjectContentArea", region.x, region.y - footerHeight,
                        true, ImGuiWindowFlags.NoScrollbar)) {
                    renderActiveView(controller.getState().getViewMode());
                }
                ImGui.endChild();

                renderStatusFooter();
            }
        }
        ImGui.end();
    }

    /** Centered empty state when no .OMP project is open. */
    private void renderNoProjectState() {
        float availY = ImGui.getContentRegionAvailY();
        ImGui.dummy(0, Math.max(0f, availY * 0.35f));
        centeredDisabled("No project open");
        ImGui.spacing();
        centeredDisabled("Open a project to browse its .OMO and .OMT files");
    }

    private void centeredDisabled(String text) {
        float w = ImGui.getContentRegionAvailX();
        ImGui.setCursorPosX(Math.max(0f, (w - ImGui.calcTextSize(text).x) * 0.5f));
        ImGui.textDisabled(text);
    }

    /**
     * Toolbar: project identity (name + folder), New Model, Refresh, search,
     * view mode, and (in List view) sort controls.
     */
    private void renderToolbar() {
        ProjectBrowserState state = controller.getState();

        if (ImGui.button("New Model")) {
            if (newModelCallback != null) {
                newModelCallback.run();
            }
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Create a new blank cube model");
        }

        ImGui.sameLine();
        if (ImGui.button("Refresh")) {
            controller.refresh();
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Rescan the project folder");
        }

        ImGui.sameLine();
        ImGui.spacing();
        ImGui.sameLine();

        // Search input with inline clear.
        ImGui.alignTextToFramePadding();
        ImGui.text("Search:");
        ImGui.sameLine();
        ImGui.pushItemWidth(SEARCH_BAR_WIDTH);
        ImGui.inputTextWithHint("##projectSearch", "Filter assets...", state.getSearchText());
        ImGui.popItemWidth();
        if (state.isSearchActive()) {
            ImGui.sameLine();
            if (ImGui.smallButton("Clear")) {
                state.setSearchTextValue("");
            }
        }

        ImGui.sameLine();
        ImGui.spacing();
        ImGui.sameLine();
        renderViewModeToggle();

        if (state.getViewMode() == ViewMode.LIST) {
            ImGui.sameLine();
            ImGui.spacing();
            ImGui.sameLine();
            renderSortControls();
        }

        // Project identity pinned right: name + folder path.
        ImGui.sameLine();
        String projectName = controller.getProjectName();
        Path root = controller.getRootPath();
        String identity = projectName + "  ·  " + (root != null ? root.toString() : "");
        float availWidth = ImGui.getContentRegionAvailX();
        float textW = ImGui.calcTextSize(identity).x;
        if (textW < availWidth) {
            ImGui.setCursorPosX(ImGui.getCursorPosX() + availWidth - textW);
        }
        ImGui.textDisabled(identity);
        if (ImGui.isItemHovered() && root != null) {
            ImGui.setTooltip(root.toString());
        }
    }

    private void renderViewModeToggle() {
        ProjectBrowserState state = controller.getState();
        ViewMode currentMode = state.getViewMode();

        ImGui.alignTextToFramePadding();
        ImGui.text("View:");
        ImGui.sameLine();

        ImGui.pushItemWidth(100.0f);
        String[] viewOptions = {"Grid", "List", "Compact"};
        int currentViewIndex = switch (currentMode) {
            case GRID -> 0;
            case LIST -> 1;
            case COMPACT -> 2;
        };

        ImInt viewIndex = new ImInt(currentViewIndex);
        if (ImGui.combo("##projectViewMode", viewIndex, viewOptions)) {
            ViewMode newMode = switch (viewIndex.get()) {
                case 1 -> ViewMode.LIST;
                case 2 -> ViewMode.COMPACT;
                default -> ViewMode.GRID;
            };
            state.setViewMode(newMode);
        }
        ImGui.popItemWidth();
    }

    private void renderSortControls() {
        ProjectBrowserState state = controller.getState();

        ImGui.alignTextToFramePadding();
        ImGui.text("Sort:");
        ImGui.sameLine();

        ImGui.pushItemWidth(100.0f);
        String[] sortOptions = {"Name", "Type"};
        ImInt sortIndex = new ImInt(state.getSortBy() == SortBy.TYPE ? 1 : 0);
        if (ImGui.combo("##projectSortBy", sortIndex, sortOptions)) {
            state.setSortBy(sortIndex.get() == 1 ? SortBy.TYPE : SortBy.NAME);
        }
        ImGui.popItemWidth();

        ImGui.sameLine();
        String orderIcon = state.getSortOrder() == SortOrder.ASCENDING ? "↑" : "↓";
        if (ImGui.smallButton(orderIcon)) {
            state.setSortOrder(state.getSortOrder().toggle());
        }
        if (ImGui.isItemHovered()) {
            String orderText = state.getSortOrder() == SortOrder.ASCENDING ? "Ascending" : "Descending";
            ImGui.setTooltip("Sort order: " + orderText + "\nClick to toggle");
        }
    }

    private void renderActiveView(ViewMode viewMode) {
        switch (viewMode) {
            case GRID -> gridViewRenderer.render();
            case LIST -> listViewRenderer.render();
            case COMPACT -> compactListRenderer.render();
        }
    }

    private void renderStatusFooter() {
        ProjectBrowserState state = controller.getState();

        ImGui.spacing();
        ImGui.textDisabled(state.getSelectedAssetInfo());
        if (state.isSearchActive()) {
            ImGui.sameLine();
            ImGui.textDisabled(" | Searching: \"" + state.getSearchTextValue() + "\"");
        }
    }

    public ProjectBrowserController getController() {
        return controller;
    }

    public boolean isVisible() {
        return visible.get();
    }

    public void setVisible(boolean visible) {
        this.visible.set(visible);
    }

    /** Free the thumbnail GL textures. Call with a current GL context. */
    public void cleanup() {
        thumbnailCache.cleanup();
    }
}
