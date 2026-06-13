package com.openmason.main.systems.menus.mainHub;

import com.openmason.main.systems.LogoManager;
import com.openmason.main.systems.menus.mainHub.components.HubLandingPanel;
import com.openmason.main.systems.menus.mainHub.components.HubSidebarNav;
import com.openmason.main.systems.menus.mainHub.components.PreviewPanel;
import com.openmason.main.systems.menus.mainHub.components.RecentProjectsPanel;
import com.openmason.main.systems.menus.mainHub.components.TemplatesPanel;
import com.openmason.main.systems.menus.mainHub.model.NavigationItem;
import com.openmason.main.systems.menus.mainHub.model.RecentProject;
import com.openmason.main.systems.menus.mainHub.services.HubActionService;
import com.openmason.main.systems.menus.mainHub.services.RecentProjectsService;
import com.openmason.main.systems.menus.mainHub.services.TemplateService;
import com.openmason.main.systems.menus.mainHub.state.HubState;
import com.openmason.main.systems.mortar.anim.Smoother;
import com.openmason.main.systems.themes.core.ThemeDefinition;
import com.openmason.main.systems.themes.core.ThemeManager;
import imgui.ImColor;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Coordinator for the project hub. Recent-first information architecture:
 * a fixed identity/navigation sidebar, a Home landing (search + New Project,
 * Recent Projects grid, Templates grid), and a contextual {@link PreviewPanel}
 * that slides in from the right when an item is selected. Built on MortarUI —
 * the sidebar, grids, header action and preview hero are Skija regions sharing
 * the live ImGui theme.
 */
public class ProjectHubScreen {

    private static final Logger logger = LoggerFactory.getLogger(ProjectHubScreen.class);

    private static final String WINDOW_TITLE = "Open Mason - Project Hub";
    private static final float SIDEBAR_WIDTH = 230.0f;
    private static final float PREVIEW_WIDTH = 340.0f;
    private static final float PANEL_GAP = 10.0f;

    private final ThemeManager themeManager;
    private final HubState hubState;
    private final HubActionService actionService;
    private final RecentProjectsService recentProjectsService;

    private final HubSidebarNav sidebarNav;
    private final HubLandingPanel landingPanel;
    private final RecentProjectsPanel recentProjectsPanel;
    private final PreviewPanel previewPanel;

    /** Animated width of the contextual preview panel (0 = hidden). */
    private final Smoother previewWidth = new Smoother(16.0f, 0.0f);

    public ProjectHubScreen(ThemeManager themeManager) {
        if (themeManager == null) {
            throw new IllegalArgumentException("ThemeManager cannot be null");
        }
        this.themeManager = themeManager;
        LogoManager logoManager = LogoManager.getInstance();

        this.hubState = new HubState();

        TemplateService templateService = new TemplateService();
        this.recentProjectsService = new RecentProjectsService();
        this.actionService = new HubActionService();

        this.sidebarNav = new HubSidebarNav(themeManager, hubState, logoManager);
        this.recentProjectsPanel = new RecentProjectsPanel(themeManager, hubState, recentProjectsService, actionService);
        TemplatesPanel templatesPanel = new TemplatesPanel(themeManager, hubState, templateService);
        this.landingPanel = new HubLandingPanel(hubState, recentProjectsPanel, templatesPanel);
        this.previewPanel = new PreviewPanel(themeManager, hubState, logoManager, actionService, recentProjectsService);

        // Share dialog instances so popups render once at window scope.
        this.previewPanel.setDialogs(recentProjectsPanel.getRenameDialog(), recentProjectsPanel.getDeleteDialog());

        logger.info("Project Hub Screen initialized (MortarUI)");
    }

    public void render() {
        ImVec2 viewportSize = ImGui.getMainViewport().getSize();
        ImVec2 viewportPos = ImGui.getMainViewport().getPos();

        ImGui.setNextWindowPos(viewportPos.x, viewportPos.y);
        ImGui.setNextWindowSize(viewportSize.x, viewportSize.y);

        int windowFlags = ImGuiWindowFlags.NoResize |
                ImGuiWindowFlags.NoCollapse |
                ImGuiWindowFlags.NoMove |
                ImGuiWindowFlags.NoTitleBar |
                ImGuiWindowFlags.NoBringToFrontOnFocus |
                ImGuiWindowFlags.NoScrollbar |
                ImGuiWindowFlags.NoScrollWithMouse;

        applyWindowStyling(themeManager.getCurrentTheme());

        if (ImGui.begin(WINDOW_TITLE, windowFlags)) {
            renderLayout();

            // Modal dialogs at top-level window scope.
            recentProjectsPanel.getRenameDialog().render();
            recentProjectsPanel.getDeleteDialog().render();
        }
        ImGui.end();

        ImGui.popStyleVar(2);
        ImGui.popStyleColor(1);
    }

    private void renderLayout() {
        ImVec2 origin = ImGui.getCursorScreenPos();
        float availH = ImGui.getContentRegionAvailY();
        float availW = ImGui.getContentRegionAvailX();

        // Content keeps its full width regardless of the preview — the preview
        // slides in as an overlay on top, so the card grids never reflow/squeeze.
        float contentW = availW - SIDEBAR_WIDTH - PANEL_GAP;

        ImGui.beginChild("Sidebar", SIDEBAR_WIDTH, availH, true);
        sidebarNav.render();
        ImGui.endChild();

        ImGui.sameLine(0, PANEL_GAP);

        ImGui.beginChild("Content", contentW, availH, true);
        if (hubState.getCurrentView() == NavigationItem.ViewType.LEARN) {
            renderLearn();
        } else {
            landingPanel.render();
        }
        ImGui.endChild();

        float pw = previewWidth.getValue();
        if (pw > 2.0f) {
            renderPreviewOverlay(origin, availW, availH, pw);
        }
    }

    /**
     * Draw the contextual preview as an opaque overlay anchored to the right
     * edge of the layout, with a soft shadow on its left so it reads as
     * floating above the content rather than docked beside it.
     */
    private void renderPreviewOverlay(ImVec2 origin, float availW, float availH, float pw) {
        // The panel is always full PREVIEW_WIDTH wide; only its X position is
        // animated, so it translates in from the right edge (clipped by the
        // window) without its content ever re-laying-out as it opens.
        float py = origin.y;
        float px = origin.x + availW - pw;

        int transparent = ImColor.rgba(0f, 0f, 0f, 0f);
        int shadow = ImColor.rgba(0f, 0f, 0f, 0.22f);
        ImGui.getWindowDrawList().addRectFilledMultiColor(
                px - 12f, py, px, py + availH, transparent, shadow, shadow, transparent);

        ImVec4 bg = ImGui.getStyle().getColor(ImGuiCol.WindowBg);
        ImGui.pushStyleColor(ImGuiCol.ChildBg, bg.x, bg.y, bg.z, 1.0f);
        ImGui.setCursorScreenPos(px, py);
        ImGui.beginChild("Preview", PREVIEW_WIDTH, availH, true);
        previewPanel.render();
        ImGui.endChild();
        ImGui.popStyleColor();
    }

    private void renderLearn() {
        ImGui.dummy(0, 40f);
        centered("Learning Resources", 1.0f);
        ImGui.dummy(0, 8f);
        centered("Documentation and tutorials are coming soon.", 0.55f);
    }

    private void centered(String text, float alpha) {
        float windowWidth = ImGui.getWindowWidth();
        float tw = ImGui.calcTextSize(text).x;
        ImGui.setCursorPosX((windowWidth - tw) * 0.5f);
        ImVec4 c = ImGui.getStyle().getColor(ImGuiCol.Text);
        ImGui.pushStyleColor(ImGuiCol.Text, c.x, c.y, c.z, alpha);
        ImGui.text(text);
        ImGui.popStyleColor();
    }

    public void update(float deltaTime) {
        boolean hasSelection = hubState.getSelectedTemplate() != null
                || hubState.getSelectedRecentProject() != null
                || hubState.isNewProjectSelected();
        previewWidth.setTarget(hasSelection ? PREVIEW_WIDTH : 0.0f);
        previewWidth.update(deltaTime);

        sidebarNav.update(deltaTime);
        landingPanel.update(deltaTime);
        previewPanel.update(deltaTime);
    }

    private void applyWindowStyling(ThemeDefinition theme) {
        ImVec4 bgColor = theme.getColor(ImGuiCol.WindowBg);
        if (bgColor != null) {
            ImGui.pushStyleColor(ImGuiCol.WindowBg, bgColor.x, bgColor.y, bgColor.z, bgColor.w);
        } else {
            ImGui.pushStyleColor(ImGuiCol.WindowBg, 0.1f, 0.1f, 0.1f, 1.0f);
        }
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 15.0f, 15.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 0.0f);
    }

    public void setTransitionCallbacks(BiConsumer<String, String> onCreateProject,
                                       Consumer<RecentProject> onOpenProject) {
        actionService.setCreateProjectCallback(onCreateProject);
        actionService.setOpenProjectCallback(onOpenProject);
    }

    /** Wire the folder picker used by the New Project / template create form. */
    public void setFolderPicker(Consumer<Consumer<String>> picker) {
        actionService.setFolderPicker(picker);
    }

    public RecentProjectsService getRecentProjectsService() {
        return recentProjectsService;
    }

    public void setOnPreferencesClicked(Runnable callback) {
        sidebarNav.setOnPreferencesClicked(callback);
    }

    /** Release Skija regions. Must run before the SkijaContext is closed. */
    public void dispose() {
        sidebarNav.dispose();
        landingPanel.dispose();
        previewPanel.dispose();
    }
}
