package com.openmason.main.systems.menus.preferences;

import com.openmason.main.systems.menus.textureCreator.TextureCreatorImGui;
import com.openmason.main.systems.menus.panes.propertyPane.PropertyPanelImGui;
import com.openmason.main.systems.menus.windows.WindowTitleBar;
import com.openmason.main.systems.themes.core.ThemeManager;
import com.openmason.main.systems.ViewportController;
import com.openmason.main.systems.mortar.core.MortarFrameResult;
import com.openmason.main.systems.mortar.core.MortarRegion;
import com.openmason.main.systems.mortar.parts.MortarNavItem;
import com.openmason.main.systems.themes.utils.ThemeColors.Tone;
import com.openmason.main.systems.themes.utils.ThemedWidgets;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiFocusedFlags;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiPopupFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Unified preferences window for Open Mason.
 * <p>
 * Uses a deferred-apply model with OK / Apply / Cancel buttons.
 * Settings are synced from persistence when the window opens,
 * and only saved/applied when the user clicks OK or Apply. Cancel (also the title-bar X and
 * Escape) discards staged edits and reverts the one immediate page, Keybinds.
 * </p>
 */
public class PreferencesWindow {

    private static final Logger logger = LoggerFactory.getLogger(PreferencesWindow.class);

    private static final String WINDOW_TITLE = "Preferences";
    private static final float SIDEBAR_WIDTH = 170.0f;
    private static final float MIN_WINDOW_WIDTH = 800.0f;
    private static final float MIN_WINDOW_HEIGHT = 600.0f;
    private static final float FOOTER_HEIGHT = 40.0f;

    // Sidebar nav layout (logical px, like HubSidebarNav)
    private static final float NAV_ITEM_HEIGHT = 36.0f;
    private static final float NAV_ITEM_GAP = 4.0f;
    private static final float NAV_PAD = 10.0f;

    // Footer buttons (screen px)
    private static final float BUTTON_WIDTH = 88.0f;
    private static final float BUTTON_HEIGHT = 26.0f;
    private static final float BUTTON_SPACING = 8.0f;
    private static final float FOOTER_MARGIN = 15.0f;

    // Window visibility state
    private final ImBoolean visible;

    // State management
    private final PreferencesState state;

    // Unified page renderer
    private final PreferencesPageRenderer pageRenderer;

    // Skija-painted sidebar navigation (ImGui fallback when Skija is unavailable)
    private final MortarRegion navRegion = new MortarRegion();

    // Window state
    private final WindowTitleBar titleBar;
    private boolean iniFileSet = false;
    private boolean wasVisible = false;
    /**
     * Whether a text field, popup or key-capture modal owned the keyboard at the end of
     * the previous frame. The widget that consumes an Escape clears its own state during
     * this frame's render, so current-frame checks alone would let one Escape both close
     * the nested widget and cancel the whole window.
     */
    private boolean escapeOwnedLastFrame = false;

    /**
     * Creates a new unified preferences window.
     */
    public PreferencesWindow(ImBoolean visible,
                             PreferencesManager preferencesManager,
                             ThemeManager themeManager,
                             TextureCreatorImGui textureCreatorImGui,
                             ViewportController viewport,
                             PropertyPanelImGui propertyPanel) {
        if (visible == null) {
            throw new IllegalArgumentException("Visibility state cannot be null");
        }

        this.visible = visible;
        this.state = new PreferencesState();

        this.pageRenderer = new PreferencesPageRenderer(
                preferencesManager,
                themeManager,
                textureCreatorImGui,
                viewport,
                propertyPanel
        );

        // No minimize: any hide is a Cancel (keybind edits revert), which a minimize button would do silently
        this.titleBar = new WindowTitleBar(WINDOW_TITLE, false, false);
        logger.debug("Unified preferences window created");
    }

    /**
     * Shows the preferences window.
     */
    public void show() {
        visible.set(true);
        logger.debug("Preferences window shown");
    }

    /**
     * Hides the preferences window.
     */
    public void hide() {
        visible.set(false);
        logger.debug("Preferences window hidden");
    }

    /** Releases the Skija sidebar surface; call while the GL context is current. */
    public void close() {
        navRegion.close();
    }

    /**
     * Checks if the preferences window is visible.
     */
    public boolean isVisible() {
        return visible.get();
    }

    /**
     * Renders the unified preferences window.
     */
    public void render() {
        if (!visible.get()) {
            if (wasVisible) {
                // Hidden by a path other than the footer buttons: same as Cancel.
                pageRenderer.cancelChanges();
            }
            wasVisible = false;
            return;
        }

        // Detect window open transition and sync state from persistence
        if (!wasVisible) {
            escapeOwnedLastFrame = false;
            pageRenderer.onWindowOpened();
            ImGui.setNextWindowFocus();
            wasVisible = true;
        }

        // Set initial size and position (first time only)
        if (!iniFileSet) {
            ImGui.setNextWindowSize(MIN_WINDOW_WIDTH, MIN_WINDOW_HEIGHT);
            ImVec2 center = ImGui.getMainViewport().getCenter();
            ImGui.setNextWindowPos(
                    center.x - MIN_WINDOW_WIDTH / 2f,
                    center.y - MIN_WINDOW_HEIGHT / 2f
            );
            iniFileSet = true;
        }

        // Set size constraints
        ImGui.setNextWindowSizeConstraints(
                MIN_WINDOW_WIDTH, MIN_WINDOW_HEIGHT,
                Float.MAX_VALUE, Float.MAX_VALUE
        );

        // Configure window flags for standalone floating window
        int windowFlags = ImGuiWindowFlags.NoDocking |
                ImGuiWindowFlags.NoTitleBar |
                ImGuiWindowFlags.NoCollapse |
                ImGuiWindowFlags.NoScrollbar;

        // Remove window padding for tight layout
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0.0f, 0.0f);

        // Begin window
        if (ImGui.begin(WINDOW_TITLE, visible, windowFlags)) {
            try {
                WindowTitleBar.Result result = titleBar.render();
                if (result.closeClicked()) {
                    cancel();
                } else {
                    renderContent();
                    handleEscape();
                    escapeOwnedLastFrame = escapeOwnedElsewhere();
                }
            } catch (Exception e) {
                logger.error("Error rendering preferences window", e);
                ThemedWidgets.statusText(Tone.ERROR, "Error rendering preferences");
                ImGui.textDisabled("Check logs for details");
            }
        }
        ImGui.end();

        ImGui.popStyleVar();
    }

    /** Applies all staged values and persists them. Keeps the window open. */
    private void apply() {
        pageRenderer.applyAllSettings();
        logger.info("Preferences applied");
    }

    /** Applies and closes. */
    private void ok() {
        pageRenderer.applyAllSettings();
        visible.set(false);
        logger.info("Preferences applied and window closed");
    }

    /**
     * Discards every change since the window opened / last Apply and closes.
     * Staged values are dropped (re-synced on next open); immediate keybind edits are reverted.
     */
    private void cancel() {
        pageRenderer.cancelChanges();
        visible.set(false);
        logger.info("Preferences cancelled");
    }

    /**
     * Escape = Cancel, unless a text field, popup or key-capture modal owned the key
     * this frame or at the end of the last one.
     */
    private void handleEscape() {
        if (ImGui.isWindowFocused(ImGuiFocusedFlags.RootAndChildWindows)
                && ImGui.isKeyPressed(ImGuiKey.Escape, false)
                && !escapeOwnedLastFrame
                && !escapeOwnedElsewhere()) {
            cancel();
        }
    }

    private boolean escapeOwnedElsewhere() {
        return ImGui.isAnyItemActive()
                || ImGui.getIO().getWantTextInput()
                || ImGui.isPopupOpen("", ImGuiPopupFlags.AnyPopupId | ImGuiPopupFlags.AnyPopupLevel)
                || pageRenderer.isModalOpen();
    }

    /**
     * Renders the sidebar navigation, content area, and footer buttons.
     */
    private void renderContent() {
        float windowWidth = ImGui.getContentRegionAvailX();
        float windowHeight = ImGui.getContentRegionAvailY();
        float contentHeight = windowHeight - FOOTER_HEIGHT;

        // Left sidebar (navigation)
        ImGui.beginChild("##Sidebar", SIDEBAR_WIDTH, contentHeight, false);
        renderSidebar();
        ImGui.endChild();

        // Vertical separator
        ImGui.sameLine();
        ImGui.getWindowDrawList().addLine(
                ImGui.getCursorScreenPosX(),
                ImGui.getCursorScreenPosY(),
                ImGui.getCursorScreenPosX(),
                ImGui.getCursorScreenPosY() + contentHeight,
                ImGui.getColorU32(ImGuiCol.Separator),
                1.0f
        );

        // Right content area (selected page)
        ImGui.sameLine();
        float contentWidth = windowWidth - SIDEBAR_WIDTH - 10;
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 10.0f, 10.0f);
        ImGui.beginChild("##Content", contentWidth, contentHeight, false);

        ImGui.indent(15.0f);
        renderSelectedPage();
        ImGui.unindent(15.0f);

        ImGui.endChild();
        ImGui.popStyleVar();

        // Footer drawn at absolute positions - no child regions, no layout interference
        renderFooter(windowWidth);
    }

    /**
     * Footer: a rule, then right-aligned OK (primary) / Apply / Cancel - Cancel always last.
     */
    private void renderFooter(float windowWidth) {
        ImDrawList drawList = ImGui.getWindowDrawList();
        ImVec2 winPos = ImGui.getWindowPos();
        float footerTop = winPos.y + ImGui.getWindowHeight() - FOOTER_HEIGHT;
        float footerRight = winPos.x + windowWidth;

        drawList.addLine(winPos.x, footerTop, footerRight, footerTop,
                ImGui.getColorU32(ImGuiCol.Separator), 1.0f);

        float buttonY = footerTop + (FOOTER_HEIGHT - BUTTON_HEIGHT) * 0.5f;
        float cancelX = footerRight - FOOTER_MARGIN - BUTTON_WIDTH;
        float applyX = cancelX - BUTTON_SPACING - BUTTON_WIDTH;
        float okX = applyX - BUTTON_SPACING - BUTTON_WIDTH;

        ImGui.setCursorScreenPos(okX, buttonY);
        boolean okClicked = ThemedWidgets.accentButton("OK", BUTTON_WIDTH, BUTTON_HEIGHT);
        ImGui.setCursorScreenPos(applyX, buttonY);
        boolean applyClicked = ImGui.button("Apply", BUTTON_WIDTH, BUTTON_HEIGHT);
        ImGui.setCursorScreenPos(cancelX, buttonY);
        boolean cancelClicked = ImGui.button("Cancel", BUTTON_WIDTH, BUTTON_HEIGHT);

        if (okClicked) {
            ok();
        } else if (applyClicked) {
            apply();
        } else if (cancelClicked) {
            cancel();
        }
    }

    /**
     * Sidebar navigation: a Mortar region of nav items (same look as HubSidebarNav),
     * or plain ImGui selectables when Skija is unavailable.
     */
    private void renderSidebar() {
        PreferencesState.PreferencePage[] pages = PreferencesState.PreferencePage.values();

        if (!navRegion.isAvailable()) {
            renderFallbackSidebar(pages);
            return;
        }

        // Logical px: the region scales to UI density.
        float width = MortarRegion.availWidth();
        float height = MortarRegion.availHeight();
        if (width < 1f || height < 1f) {
            return;
        }

        navRegion.update(ImGui.getIO().getDeltaTime());
        navRegion.begin(width, height);

        float y = NAV_PAD;
        for (PreferencesState.PreferencePage page : pages) {
            navRegion.add("nav." + page.name(), NAV_PAD, y, width - NAV_PAD * 2f, NAV_ITEM_HEIGHT,
                    state.getCurrentPage() == page, new MortarNavItem(page.getDisplayName()));
            y += NAV_ITEM_HEIGHT + NAV_ITEM_GAP;
        }

        MortarFrameResult input = navRegion.render();
        for (PreferencesState.PreferencePage page : pages) {
            if (input.isClicked("nav." + page.name())) {
                selectPage(page);
            }
        }
    }

    private void renderFallbackSidebar(PreferencesState.PreferencePage[] pages) {
        ImGui.dummy(0, NAV_PAD);
        ImGui.indent(NAV_PAD);
        for (PreferencesState.PreferencePage page : pages) {
            if (ImGui.selectable(page.getDisplayName(), state.getCurrentPage() == page,
                    0, ImGui.getContentRegionAvailX() - NAV_PAD, NAV_ITEM_HEIGHT * 0.75f)) {
                selectPage(page);
            }
        }
        ImGui.unindent(NAV_PAD);
    }

    private void selectPage(PreferencesState.PreferencePage page) {
        state.setCurrentPage(page);
        logger.debug("Switched to page: {}", page.name());
    }

    /**
     * Renders the currently selected page content.
     */
    private void renderSelectedPage() {
        pageRenderer.render(state.getCurrentPage());
    }

    /**
     * Sets the current page to display.
     */
    public void setCurrentPage(PreferencesState.PreferencePage page) {
        state.setCurrentPage(page);
    }

    /**
     * Sets the TextureCreatorImGui instance for texture editor preferences.
     */
    public void setTextureCreatorImGui(TextureCreatorImGui textureCreatorImGui) {
        pageRenderer.setTextureCreatorImGui(textureCreatorImGui);
    }

    /**
     * Route camera-sensitivity changes to the Scene Viewer's camera as well.
     *
     * <p>These are user preferences, so they belong to every 3D surface — this renderer
     * only knew about the model editor's viewport because it predates the second one.
     */
    public void setSceneCameraSink(java.util.function.BiConsumer<Float, Float> sink) {
        pageRenderer.setSceneCameraSink(sink);
    }
}
