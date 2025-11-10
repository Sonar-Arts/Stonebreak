package com.openmason.ui.hub;

import com.openmason.ui.LogoManager;
import com.openmason.ui.hub.components.*;
import com.openmason.ui.hub.model.NavigationItem;
import com.openmason.ui.hub.services.HubActionService;
import com.openmason.ui.hub.services.RecentProjectsService;
import com.openmason.ui.hub.services.TemplateService;
import com.openmason.ui.hub.state.HubState;
import com.openmason.ui.hub.state.HubVisibilityState;
import com.openmason.ui.themes.core.ThemeDefinition;
import com.openmason.ui.themes.core.ThemeManager;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main coordinator for Unity Hub-style project hub interface.
 * Replaces HomeScreenOM.java.
 *
 * Follows Composition over Inheritance and Dependency Injection patterns.
 * Orchestrates all hub components following SOLID, KISS, DRY, and YAGNI principles.
 */
public class ProjectHubScreen {

    private static final Logger logger = LoggerFactory.getLogger(ProjectHubScreen.class);

    private static final String WINDOW_TITLE = "Open Mason - Project Hub";
    private static final float SIDEBAR_WIDTH = 250.0f;
    private static final float PREVIEW_WIDTH = 350.0f;

    // Dependencies
    private final ThemeManager themeManager;
    private final LogoManager logoManager;

    // State
    private final HubState hubState;
    private final HubVisibilityState visibilityState;

    // Services
    private final TemplateService templateService;
    private final RecentProjectsService recentProjectsService;
    private final HubActionService actionService;

    // UI Components
    private final HubTopToolbar topToolbar;
    private final HubSidebarNav sidebarNav;
    private final TemplatesPanel templatesPanel;
    private final RecentProjectsPanel recentProjectsPanel;
    private final PreviewPanel previewPanel;

    // Animation state
    private float animationTime = 0.0f;

    // Window dimensions
    private float windowWidth = 0.0f;
    private float windowHeight = 0.0f;

    /**
     * Create Project Hub Screen with dependency injection.
     */
    public ProjectHubScreen(ThemeManager themeManager) {
        if (themeManager == null) {
            throw new IllegalArgumentException("ThemeManager cannot be null");
        }

        this.themeManager = themeManager;
        this.logoManager = LogoManager.getInstance();

        // Initialize state
        this.hubState = new HubState();
        this.visibilityState = new HubVisibilityState();

        // Initialize services
        this.templateService = new TemplateService();
        this.recentProjectsService = new RecentProjectsService(templateService);
        this.actionService = new HubActionService();

        // Initialize UI components with dependency injection
        this.topToolbar = new HubTopToolbar(themeManager, hubState);
        this.sidebarNav = new HubSidebarNav(themeManager, hubState, logoManager);
        this.templatesPanel = new TemplatesPanel(themeManager, hubState, templateService);
        this.recentProjectsPanel = new RecentProjectsPanel(themeManager, hubState, recentProjectsService);
        this.previewPanel = new PreviewPanel(themeManager, hubState, logoManager, actionService);

        logger.info("Project Hub Screen initialized");
    }

    /**
     * Render the Project Hub screen.
     */
    public void render() {
        // Match window to viewport size for fullscreen effect
        ImVec2 viewportSize = ImGui.getMainViewport().getSize();
        ImVec2 viewportPos = ImGui.getMainViewport().getPos();

        windowWidth = viewportSize.x;
        windowHeight = viewportSize.y;

        ImGui.setNextWindowPos(viewportPos.x, viewportPos.y);
        ImGui.setNextWindowSize(windowWidth, windowHeight);

        // Configure as borderless fullscreen window
        int windowFlags = ImGuiWindowFlags.NoResize |
                         ImGuiWindowFlags.NoCollapse |
                         ImGuiWindowFlags.NoMove |
                         ImGuiWindowFlags.NoTitleBar |
                         ImGuiWindowFlags.NoBringToFrontOnFocus;

        ThemeDefinition theme = themeManager.getCurrentTheme();
        applyWindowStyling(theme);

        if (ImGui.begin(WINDOW_TITLE, windowFlags)) {
            renderLayout();
        }
        ImGui.end();

        ImGui.popStyleVar(2);
        ImGui.popStyleColor(1);
    }

    /**
     * Render the main layout.
     */
    private void renderLayout() {
        // Top toolbar (full width)
        if (visibilityState.isTopToolbarVisible()) {
            topToolbar.render();
            ImGui.separator();
            ImGui.spacing();
        }

        // Calculate available space for three-panel layout
        float availableHeight = ImGui.getContentRegionAvailY();
        float availableWidth = ImGui.getContentRegionAvailX();

        // Calculate panel widths
        float sidebarWidth = visibilityState.isSidebarVisible() ? SIDEBAR_WIDTH : 0;
        float previewWidth = visibilityState.isPreviewPanelVisible() ? PREVIEW_WIDTH : 0;
        float spacing = 10.0f;
        float centerWidth = availableWidth - sidebarWidth - previewWidth - (spacing * 2);

        // Left sidebar
        if (visibilityState.isSidebarVisible()) {
            ImGui.beginChild("Sidebar", sidebarWidth, availableHeight, true);
            sidebarNav.render();
            ImGui.endChild();
            ImGui.sameLine(0, spacing);
        }

        // Center panel (switchable content)
        ImGui.beginChild("CenterPanel", centerWidth, availableHeight, true);
        renderCenterPanel();
        ImGui.endChild();

        if (visibilityState.isPreviewPanelVisible()) {
            ImGui.sameLine(0, spacing);

            // Right preview panel
            ImGui.beginChild("PreviewPanel", previewWidth, availableHeight, true);
            previewPanel.render();
            ImGui.endChild();
        }
    }

    /**
     * Render the center panel based on current view.
     */
    private void renderCenterPanel() {
        switch (hubState.getCurrentView()) {
            case TEMPLATES:
                templatesPanel.render();
                break;
            case RECENT_PROJECTS:
                recentProjectsPanel.render();
                break;
            case LEARN:
                renderLearnPanel();
                break;
        }
    }

    /**
     * Render Learn panel (placeholder).
     */
    private void renderLearnPanel() {
        ImGui.spacing();
        ImGui.spacing();

        String title = "Learning Resources";
        ImVec2 titleSize = ImGui.calcTextSize(title);
        float windowWidth = ImGui.getWindowWidth();
        float titleX = (windowWidth - titleSize.x) * 0.5f;
        ImGui.setCursorPosX(titleX);
        ImGui.text(title);

        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();

        ThemeDefinition theme = themeManager.getCurrentTheme();
        ImVec4 textDisabled = theme.getColor(ImGuiCol.TextDisabled);
        if (textDisabled != null) {
            ImGui.pushStyleColor(ImGuiCol.Text, textDisabled.x, textDisabled.y, textDisabled.z, 0.8f);
        }

        String message = "Documentation and tutorials coming soon!";
        ImVec2 messageSize = ImGui.calcTextSize(message);
        float messageX = (windowWidth - messageSize.x) * 0.5f;
        ImGui.setCursorPosX(messageX);
        ImGui.text(message);

        if (textDisabled != null) {
            ImGui.popStyleColor();
        }
    }

    /**
     * Update method called every frame.
     */
    public void update(float deltaTime) {
        animationTime += deltaTime;
        // Animation updates can be added here
    }

    /**
     * Apply window styling using theme colors.
     */
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

    /**
     * Set transition callbacks for project creation and opening.
     */
    public void setTransitionCallbacks(Runnable onCreateProject, Runnable onOpenProject) {
        actionService.setCreateProjectCallback(onCreateProject);
        actionService.setOpenProjectCallback(onOpenProject);
    }

    /**
     * Set callback for preferences button.
     */
    public void setOnPreferencesClicked(Runnable callback) {
        topToolbar.setOnPreferencesClicked(callback);
    }

    /**
     * Check if there's a pending transition action.
     */
    public boolean shouldTransition() {
        return actionService.hasPendingAction();
    }

    /**
     * Get the action service for accessing pending actions.
     */
    public HubActionService getActionService() {
        return actionService;
    }

    /**
     * Get the hub state.
     */
    public HubState getHubState() {
        return hubState;
    }

    /**
     * Get the visibility state.
     */
    public HubVisibilityState getVisibilityState() {
        return visibilityState;
    }
}
