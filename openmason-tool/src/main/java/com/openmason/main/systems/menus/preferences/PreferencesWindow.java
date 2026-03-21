package com.openmason.main.systems.menus.preferences;

import com.openmason.main.systems.menus.textureCreator.TextureCreatorImGui;
import com.openmason.main.systems.menus.panes.propertyPane.PropertyPanelImGui;
import com.openmason.main.systems.menus.windows.WindowTitleBar;
import com.openmason.main.systems.themes.core.ThemeDefinition;
import com.openmason.main.systems.themes.core.ThemeManager;
import com.openmason.main.systems.ViewportController;
import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Unified preferences window for Open Mason.
 * <p>
 * Uses a deferred-apply model with OK and Apply buttons.
 * Settings are synced from persistence when the window opens,
 * and only saved/applied when the user clicks OK or Apply.
 * </p>
 */
public class PreferencesWindow {

    private static final Logger logger = LoggerFactory.getLogger(PreferencesWindow.class);

    private static final String WINDOW_TITLE = "Preferences";
    private static final float SIDEBAR_WIDTH = 170.0f;
    private static final float MIN_WINDOW_WIDTH = 800.0f;
    private static final float MIN_WINDOW_HEIGHT = 600.0f;
    private static final float FOOTER_HEIGHT = 40.0f;

    // Navigation button style constants (matching HubSidebarNav)
    private static final float NAV_ITEM_HEIGHT = 36.0f;
    private static final float NAV_ITEM_ROUNDING = 8.0f;
    private static final float NAV_ITEM_SPACING = 4.0f;
    private static final float NAV_TEXT_LEFT_PADDING = 16.0f;
    private static final float NAV_SIDEBAR_PADDING = 10.0f;
    private static final float ACCENT_WIDTH = 3.0f;
    private static final float ACCENT_ROUNDING = 1.5f;
    private static final float ACCENT_INSET_Y = 8.0f;

    // Window visibility state
    private final ImBoolean visible;

    // State management
    private final PreferencesState state;

    // Unified page renderer
    private final PreferencesPageRenderer pageRenderer;

    // Theme for custom-drawn sidebar
    private final ThemeManager themeManager;

    // Window state
    private final WindowTitleBar titleBar;
    private boolean iniFileSet = false;
    private boolean wasVisible = false;

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
        this.themeManager = themeManager;

        this.pageRenderer = new PreferencesPageRenderer(
                preferencesManager,
                themeManager,
                textureCreatorImGui,
                viewport,
                propertyPanel
        );

        this.titleBar = new WindowTitleBar(WINDOW_TITLE, true, false);
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
            wasVisible = false;
            return;
        }

        // Detect window open transition and sync state from persistence
        if (!wasVisible) {
            pageRenderer.onWindowOpened();
            wasVisible = true;
        }

        // Set initial size and position (first time only)
        if (!iniFileSet) {
            ImGui.setNextWindowSize(MIN_WINDOW_WIDTH, MIN_WINDOW_HEIGHT);
            ImGui.setNextWindowPos(200, 100);
            iniFileSet = true;
        }

        // Set size constraints
        ImGui.setNextWindowSizeConstraints(
                MIN_WINDOW_WIDTH, MIN_WINDOW_HEIGHT,
                Float.MAX_VALUE, Float.MAX_VALUE
        );

        // Configure window flags for standalone floating window
        int windowFlags = ImGuiWindowFlags.NoBringToFrontOnFocus |
                ImGuiWindowFlags.NoDocking |
                ImGuiWindowFlags.NoTitleBar |
                ImGuiWindowFlags.NoCollapse |
                ImGuiWindowFlags.NoScrollbar;

        // Remove window padding for tight layout
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0.0f, 0.0f);

        // Begin window
        if (ImGui.begin(WINDOW_TITLE, visible, windowFlags)) {
            try {
                WindowTitleBar.Result result = titleBar.render();
                if (result.minimizeClicked() || result.closeClicked()) {
                    visible.set(false);
                }
                renderContent();
            } catch (Exception e) {
                logger.error("Error rendering preferences window", e);
                ImGui.textColored(1.0f, 0.0f, 0.0f, 1.0f, "Error rendering preferences");
                ImGui.text("Check logs for details");
            }
        }
        ImGui.end();

        ImGui.popStyleVar();
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
                ImGui.getColorU32(0.5f, 0.5f, 0.5f, 0.5f),
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

        // Footer drawn at absolute positions — no child regions, no layout interference
        renderFooter(windowWidth, windowHeight);
    }

    /**
     * Renders the footer using absolute screen coordinates.
     * Bypasses ImGui layout entirely to avoid padding/spacing issues.
     */
    private void renderFooter(float windowWidth, float totalHeight) {
        float buttonWidth = 80.0f;
        float buttonHeight = 24.0f;
        float buttonSpacing = 8.0f;
        float rightMargin = 15.0f;
        float totalButtonsWidth = buttonWidth * 2 + buttonSpacing;

        ImDrawList drawList = ImGui.getWindowDrawList();
        ImVec2 winPos = ImGui.getWindowPos();
        float winHeight = ImGui.getWindowHeight();
        ThemeDefinition theme = themeManager.getCurrentTheme();
        ImVec4 accentBase = getAccentColor(theme);

        // Footer region: bottom FOOTER_HEIGHT pixels of the window
        float footerTop = winPos.y + winHeight - FOOTER_HEIGHT;
        float footerBottom = winPos.y + winHeight;
        float footerLeft = winPos.x;
        float footerRight = winPos.x + windowWidth;

        // Gradient separator line at top of footer
        float sepCenter = (footerLeft + footerRight) * 0.5f;
        int sepBright = ImColor.rgba(accentBase.x, accentBase.y, accentBase.z, 0.25f);
        int sepFade = ImColor.rgba(accentBase.x, accentBase.y, accentBase.z, 0.0f);

        drawList.addRectFilledMultiColor(footerLeft, footerTop, sepCenter, footerTop + 1.0f,
                sepFade, sepBright, sepBright, sepFade);
        drawList.addRectFilledMultiColor(sepCenter, footerTop, footerRight, footerTop + 1.0f,
                sepBright, sepFade, sepFade, sepBright);

        // Center buttons vertically within footer
        float buttonY = footerTop + (FOOTER_HEIGHT - buttonHeight) * 0.5f;
        float applyX = footerRight - rightMargin - totalButtonsWidth;
        float okX = applyX + buttonWidth + buttonSpacing;

        // Apply button
        renderFooterButtonAbsolute(drawList, "Apply", "apply_btn", applyX, buttonY,
                buttonWidth, buttonHeight, false, theme, accentBase, () -> {
            pageRenderer.applyAllSettings();
            logger.info("Preferences applied");
        });

        // OK button
        renderFooterButtonAbsolute(drawList, "OK", "ok_btn", okX, buttonY,
                buttonWidth, buttonHeight, true, theme, accentBase, () -> {
            pageRenderer.applyAllSettings();
            visible.set(false);
            logger.info("Preferences applied and window closed");
        });
    }

    /**
     * Renders a footer button at an absolute screen position using
     * ImGui.setCursorScreenPos for the invisible button hit area.
     */
    private void renderFooterButtonAbsolute(ImDrawList drawList, String label, String id,
                                             float x, float y, float width, float height,
                                             boolean primary, ThemeDefinition theme,
                                             ImVec4 accentBase, Runnable onClick) {
        ImGui.setCursorScreenPos(x, y);
        ImGui.invisibleButton("##" + id, width, height);
        boolean isHovered = ImGui.isItemHovered();
        if (ImGui.isItemClicked()) {
            onClick.run();
        }

        float x2 = x + width;
        float y2 = y + height;

        if (primary) {
            float bgAlpha = isHovered ? 0.30f : 0.18f;
            drawList.addRectFilled(x, y, x2, y2,
                    ImColor.rgba(accentBase.x, accentBase.y, accentBase.z, bgAlpha), NAV_ITEM_ROUNDING);
            float borderAlpha = isHovered ? 0.7f : 0.5f;
            drawList.addRect(x, y, x2, y2,
                    ImColor.rgba(accentBase.x, accentBase.y, accentBase.z, borderAlpha), NAV_ITEM_ROUNDING, 0, 1.0f);
        } else {
            if (isHovered) {
                ImVec4 hoverBase = theme.getColor(ImGuiCol.HeaderHovered);
                int hoverColor = hoverBase != null
                        ? ImColor.rgba(hoverBase.x, hoverBase.y, hoverBase.z, 0.15f)
                        : ImColor.rgba(1.0f, 1.0f, 1.0f, 0.08f);
                drawList.addRectFilled(x, y, x2, y2, hoverColor, NAV_ITEM_ROUNDING);
                drawList.addRect(x, y, x2, y2,
                        ImColor.rgba(accentBase.x, accentBase.y, accentBase.z, 0.5f), NAV_ITEM_ROUNDING, 0, 1.0f);
            } else {
                ImVec4 frameBg = theme.getColor(ImGuiCol.FrameBg);
                int normalBg = frameBg != null
                        ? ImColor.rgba(frameBg.x, frameBg.y, frameBg.z, 0.25f)
                        : ImColor.rgba(1.0f, 1.0f, 1.0f, 0.03f);
                drawList.addRectFilled(x, y, x2, y2, normalBg, NAV_ITEM_ROUNDING);
                ImVec4 borderBase = theme.getColor(ImGuiCol.Border);
                int normalBorder = borderBase != null
                        ? ImColor.rgba(borderBase.x, borderBase.y, borderBase.z, 0.6f)
                        : ImColor.rgba(0.5f, 0.5f, 0.5f, 0.3f);
                drawList.addRect(x, y, x2, y2, normalBorder, NAV_ITEM_ROUNDING, 0, 1.0f);
            }
        }

        // Centered label
        ImVec2 textSize = ImGui.calcTextSize(label);
        float textX = x + (width - textSize.x) * 0.5f;
        float textY = y + (height - textSize.y) * 0.5f;
        ImVec4 textBase = theme.getColor(ImGuiCol.Text);
        float tr = textBase != null ? textBase.x : 0.88f;
        float tg = textBase != null ? textBase.y : 0.89f;
        float tb = textBase != null ? textBase.z : 0.91f;
        drawList.addText(textX, textY, ImColor.rgba(tr, tg, tb, isHovered ? 1.0f : 0.9f), label);
    }

    /**
     * Renders the sidebar navigation with polished custom-drawn buttons
     * matching the home screen navigation style.
     */
    private void renderSidebar() {
        ImGui.dummy(0, NAV_SIDEBAR_PADDING);

        for (int i = 0; i < PreferencesState.PreferencePage.values().length; i++) {
            PreferencesState.PreferencePage page = PreferencesState.PreferencePage.values()[i];
            renderSidebarNavButton(page);
            if (i < PreferencesState.PreferencePage.values().length - 1) {
                ImGui.dummy(0, NAV_ITEM_SPACING);
            }
        }
    }

    /**
     * Renders a single sidebar navigation button with the polished style
     * from HubSidebarNav (gradient fill, accent bar, hover effects).
     */
    private void renderSidebarNavButton(PreferencesState.PreferencePage page) {
        ThemeDefinition theme = themeManager.getCurrentTheme();
        boolean isSelected = state.getCurrentPage() == page;

        float availWidth = ImGui.getContentRegionAvailX();
        float itemWidth = availWidth - (NAV_SIDEBAR_PADDING * 2);
        ImGui.setCursorPosX(ImGui.getCursorPosX() + NAV_SIDEBAR_PADDING);

        ImVec2 screenPos = ImGui.getCursorScreenPos();

        ImGui.invisibleButton("##pref_nav_" + page.name(), itemWidth, NAV_ITEM_HEIGHT);
        boolean isHovered = ImGui.isItemHovered();
        boolean isClicked = ImGui.isItemClicked();

        if (isClicked) {
            state.setCurrentPage(page);
            logger.debug("Switched to page: {}", page.name());
        }

        ImDrawList drawList = ImGui.getWindowDrawList();
        float x1 = screenPos.x;
        float y1 = screenPos.y;
        float x2 = x1 + itemWidth;
        float y2 = y1 + NAV_ITEM_HEIGHT;

        ImVec4 accentBase = getAccentColor(theme);

        if (isSelected) {
            // Gradient fill: accent color fading from left to transparent on right
            int leftColor = ImColor.rgba(accentBase.x, accentBase.y, accentBase.z, 0.20f);
            int rightColor = ImColor.rgba(accentBase.x, accentBase.y, accentBase.z, 0.05f);
            drawList.addRectFilledMultiColor(x1, y1, x2, y2, leftColor, rightColor, rightColor, leftColor);

            // Rounded border overlay
            int borderColor = ImColor.rgba(accentBase.x, accentBase.y, accentBase.z, 0.4f);
            drawList.addRect(x1, y1, x2, y2, borderColor, NAV_ITEM_ROUNDING, 0, 1.0f);

            // Left accent bar with glow
            int accentSolid = ImColor.rgba(accentBase.x, accentBase.y, accentBase.z, 1.0f);
            int accentGlow = ImColor.rgba(accentBase.x, accentBase.y, accentBase.z, 0.25f);

            drawList.addRectFilled(
                    x1 - 2.0f, y1 + ACCENT_INSET_Y - 2.0f,
                    x1 + ACCENT_WIDTH + 4.0f, y2 - ACCENT_INSET_Y + 2.0f,
                    accentGlow, 3.0f
            );
            drawList.addRectFilled(
                    x1, y1 + ACCENT_INSET_Y,
                    x1 + ACCENT_WIDTH, y2 - ACCENT_INSET_Y,
                    accentSolid, ACCENT_ROUNDING
            );
        } else if (isHovered) {
            // Hover: slightly brighter background
            ImVec4 hoverBase = theme.getColor(ImGuiCol.HeaderHovered);
            int hoverColor;
            if (hoverBase != null) {
                hoverColor = ImColor.rgba(hoverBase.x, hoverBase.y, hoverBase.z, 0.15f);
            } else {
                hoverColor = ImColor.rgba(1.0f, 1.0f, 1.0f, 0.08f);
            }
            drawList.addRectFilled(x1, y1, x2, y2, hoverColor, NAV_ITEM_ROUNDING);

            int hoverBorder = ImColor.rgba(accentBase.x, accentBase.y, accentBase.z, 0.5f);
            drawList.addRect(x1, y1, x2, y2, hoverBorder, NAV_ITEM_ROUNDING, 0, 1.0f);

            int hintAccent = ImColor.rgba(accentBase.x, accentBase.y, accentBase.z, 0.45f);
            drawList.addRectFilled(
                    x1, y1 + ACCENT_INSET_Y + 2.0f,
                    x1 + ACCENT_WIDTH * 0.6f, y2 - ACCENT_INSET_Y - 2.0f,
                    hintAccent, ACCENT_ROUNDING
            );
        } else {
            // Normal state: subtle background fill + border
            ImVec4 frameBg = theme.getColor(ImGuiCol.FrameBg);
            int normalBg;
            if (frameBg != null) {
                normalBg = ImColor.rgba(frameBg.x, frameBg.y, frameBg.z, 0.25f);
            } else {
                normalBg = ImColor.rgba(1.0f, 1.0f, 1.0f, 0.03f);
            }
            drawList.addRectFilled(x1, y1, x2, y2, normalBg, NAV_ITEM_ROUNDING);

            ImVec4 borderBase = theme.getColor(ImGuiCol.Border);
            int normalBorder;
            if (borderBase != null) {
                normalBorder = ImColor.rgba(borderBase.x, borderBase.y, borderBase.z, 0.6f);
            } else {
                normalBorder = ImColor.rgba(0.5f, 0.5f, 0.5f, 0.3f);
            }
            drawList.addRect(x1, y1, x2, y2, normalBorder, NAV_ITEM_ROUNDING, 0, 1.0f);
        }

        // Label text
        String label = page.getDisplayName();
        float textX = x1 + NAV_TEXT_LEFT_PADDING;
        ImGui.setWindowFontScale(1.0f);
        float textHeight = ImGui.calcTextSize(label).y;
        float textY = y1 + (NAV_ITEM_HEIGHT - textHeight) * 0.5f;

        ImVec4 textBase = theme.getColor(ImGuiCol.Text);
        float tr = textBase != null ? textBase.x : 0.88f;
        float tg = textBase != null ? textBase.y : 0.89f;
        float tb = textBase != null ? textBase.z : 0.91f;

        int textColor;
        if (isSelected) {
            textColor = ImColor.rgba(tr, tg, tb, 1.0f);
            drawList.addText(textX, textY, textColor, label);
            drawList.addText(textX + 0.5f, textY, textColor, label);
        } else {
            textColor = ImColor.rgba(tr, tg, tb, isHovered ? 1.0f : 0.9f);
            drawList.addText(textX, textY, textColor, label);
        }
    }

    /**
     * Get the accent color from the theme with fallback.
     */
    private ImVec4 getAccentColor(ThemeDefinition theme) {
        ImVec4 accent = theme.getColor(ImGuiCol.HeaderActive);
        if (accent == null) accent = theme.getColor(ImGuiCol.ButtonHovered);
        if (accent == null) accent = new ImVec4(0.36f, 0.61f, 0.84f, 1.0f);
        return accent;
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
}
