package com.openmason.main.systems.menus.mainHub.components;

import com.openmason.main.systems.LogoManager;
import com.openmason.main.systems.menus.mainHub.model.NavigationItem;
import com.openmason.main.systems.menus.mainHub.state.HubState;
import com.openmason.main.systems.themes.core.ThemeDefinition;
import com.openmason.main.systems.themes.core.ThemeManager;
import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;

import java.util.ArrayList;
import java.util.List;

/**
 * Left sidebar navigation panel with polished custom rendering.
 * Single Responsibility: Render navigation menu and handle selection.
 */
public class HubSidebarNav {

    // Layout
    private static final float LOGO_SIZE = 100.0f;
    private static final float SIDEBAR_PADDING = 14.0f;
    private static final float LOGO_BOTTOM_SPACING = 6.0f;

    // Navigation items
    private static final float NAV_ITEM_HEIGHT = 36.0f;
    private static final float NAV_ITEM_ROUNDING = 8.0f;
    private static final float NAV_ITEM_SPACING = 4.0f;
    private static final float NAV_TEXT_LEFT_PADDING = 16.0f;

    // Accent indicator
    private static final float ACCENT_WIDTH = 3.0f;
    private static final float ACCENT_ROUNDING = 1.5f;
    private static final float ACCENT_INSET_Y = 8.0f;

    // Glow effect
    private static final float GLOW_RADIUS = 60.0f;
    private static final float GLOW_ALPHA = 0.06f;

    // Section label
    private static final float SECTION_LABEL_BOTTOM_SPACING = 6.0f;

    private final ThemeManager themeManager;
    private final HubState hubState;
    private final LogoManager logoManager;
    private final List<NavigationItem> navItems;
    private Runnable onPreferencesClicked;

    public HubSidebarNav(ThemeManager themeManager, HubState hubState, LogoManager logoManager) {
        this.themeManager = themeManager;
        this.hubState = hubState;
        this.logoManager = logoManager;
        this.navItems = createNavigationItems();

        if (!navItems.isEmpty()) {
            NavigationItem firstItem = navItems.get(0);
            hubState.setSelectedNavItem(firstItem);
            hubState.setCurrentView(firstItem.getViewType());
        }
    }

    /**
     * Set callback for preferences button.
     */
    public void setOnPreferencesClicked(Runnable callback) {
        this.onPreferencesClicked = callback;
    }

    /**
     * Render the sidebar navigation.
     */
    public void render() {
        renderSidebarBackground();
        renderLogoSection();
        renderGradientSeparator();
        ImGui.dummy(0, 10.0f);
        renderSectionLabel("NAVIGATION");
        ImGui.dummy(0, SECTION_LABEL_BOTTOM_SPACING);

        for (int i = 0; i < navItems.size(); i++) {
            renderNavigationItem(navItems.get(i));
            if (i < navItems.size() - 1) {
                ImGui.dummy(0, NAV_ITEM_SPACING);
            }
        }

        // Preferences button (action button, not a navigation item)
        ImGui.dummy(0, NAV_ITEM_SPACING);
        renderPreferencesButton();

        renderBottomAccent();
    }

    /**
     * Render a subtle gradient overlay on the sidebar background.
     */
    private void renderSidebarBackground() {
        ImDrawList drawList = ImGui.getWindowDrawList();
        ImVec2 winPos = ImGui.getWindowPos();
        float winWidth = ImGui.getWindowWidth();
        float winHeight = ImGui.getWindowHeight();

        // Subtle top-to-bottom gradient: slightly lighter at top, darker at bottom
        int topColor = ImColor.rgba(1.0f, 1.0f, 1.0f, 0.02f);
        int bottomColor = ImColor.rgba(0.0f, 0.0f, 0.0f, 0.04f);
        drawList.addRectFilledMultiColor(
                winPos.x, winPos.y,
                winPos.x + winWidth, winPos.y + winHeight,
                topColor, topColor, bottomColor, bottomColor
        );
    }

    /**
     * Render logo with ambient glow and title.
     */
    private void renderLogoSection() {
        float sidebarWidth = ImGui.getWindowWidth();
        ImVec2 scaledSize = logoManager.getScaledLogoSize(LOGO_SIZE, LOGO_SIZE);
        ImDrawList drawList = ImGui.getWindowDrawList();
        ThemeDefinition theme = themeManager.getCurrentTheme();

        // Center logo
        float logoX = (sidebarWidth - scaledSize.x) * 0.5f;
        ImGui.setCursorPosX(logoX);

        // Get screen position for glow effect before rendering logo
        ImVec2 logoScreenPos = ImGui.getCursorScreenPos();
        float glowCenterX = logoScreenPos.x + scaledSize.x * 0.5f;
        float glowCenterY = logoScreenPos.y + scaledSize.y * 0.5f;

        // Ambient glow behind logo
        ImVec4 accentBase = getAccentColor(theme);
        renderRadialGlow(drawList, glowCenterX, glowCenterY, GLOW_RADIUS, accentBase, GLOW_ALPHA);

        logoManager.renderLogo(scaledSize.x, scaledSize.y);

        ImGui.dummy(0, LOGO_BOTTOM_SPACING);

        // Title - rendered larger
        ImGui.setWindowFontScale(1.2f);
        String title = "Project Hub";
        ImVec2 titleSize = ImGui.calcTextSize(title);
        ImGui.setCursorPosX((sidebarWidth - titleSize.x) * 0.5f);

        ImVec4 textColor = theme.getColor(ImGuiCol.Text);
        if (textColor != null) {
            // Render with slight double-pass for bold effect
            ImVec2 titleScreenPos = ImGui.getCursorScreenPos();
            int titleColor = ImColor.rgba(textColor.x, textColor.y, textColor.z, 1.0f);
            drawList.addText(titleScreenPos.x, titleScreenPos.y, titleColor, title);
            drawList.addText(titleScreenPos.x + 0.6f, titleScreenPos.y, titleColor, title);
            ImGui.dummy(titleSize.x, titleSize.y);
        } else {
            ImGui.text(title);
        }
        ImGui.setWindowFontScale(1.0f);

        // Subtitle - uses theme Text color for legibility in any theme
        ImGui.setWindowFontScale(0.85f);
        String subtitle = "Open Mason";
        ImVec2 subtitleSize = ImGui.calcTextSize(subtitle);
        ImGui.setCursorPosX((sidebarWidth - subtitleSize.x) * 0.5f);

        if (textColor != null) {
            ImGui.pushStyleColor(ImGuiCol.Text, textColor.x, textColor.y, textColor.z, 0.7f);
        }
        ImGui.text(subtitle);
        if (textColor != null) {
            ImGui.popStyleColor();
        }
        ImGui.setWindowFontScale(1.0f);
    }

    /**
     * Render a gradient separator that fades from edges to a bright center.
     */
    private void renderGradientSeparator() {
        ImGui.dummy(0, 10.0f);
        ImDrawList drawList = ImGui.getWindowDrawList();
        ImVec2 cursorPos = ImGui.getCursorScreenPos();
        float availWidth = ImGui.getContentRegionAvailX();
        ThemeDefinition theme = themeManager.getCurrentTheme();

        float lineY = cursorPos.y;
        float leftX = cursorPos.x + SIDEBAR_PADDING;
        float rightX = cursorPos.x + availWidth - SIDEBAR_PADDING;
        float centerX = (leftX + rightX) * 0.5f;

        ImVec4 accentBase = getAccentColor(theme);
        int accentBright = ImColor.rgba(accentBase.x, accentBase.y, accentBase.z, 0.4f);
        int transparent = ImColor.rgba(accentBase.x, accentBase.y, accentBase.z, 0.0f);

        // Left half: transparent -> bright
        drawList.addRectFilledMultiColor(
                leftX, lineY - 0.5f,
                centerX, lineY + 0.5f,
                transparent, accentBright, accentBright, transparent
        );
        // Right half: bright -> transparent
        drawList.addRectFilledMultiColor(
                centerX, lineY - 0.5f,
                rightX, lineY + 0.5f,
                accentBright, transparent, transparent, accentBright
        );

        ImGui.dummy(0, 1.0f);
    }

    /**
     * Render a small uppercase section label.
     */
    private void renderSectionLabel(String label) {
        ThemeDefinition theme = themeManager.getCurrentTheme();
        ImDrawList drawList = ImGui.getWindowDrawList();

        ImGui.setWindowFontScale(0.75f);
        ImVec2 labelSize = ImGui.calcTextSize(label);
        float labelX = ImGui.getCursorScreenPos().x + SIDEBAR_PADDING + 4.0f;
        float labelY = ImGui.getCursorScreenPos().y;

        ImVec4 sectionColor = theme.getColor(ImGuiCol.Text);
        int color;
        if (sectionColor != null) {
            color = ImColor.rgba(sectionColor.x, sectionColor.y, sectionColor.z, 0.5f);
        } else {
            color = ImColor.rgba(0.5f, 0.5f, 0.5f, 1.0f);
        }

        drawList.addText(labelX, labelY, color, label);
        ImGui.dummy(labelSize.x, labelSize.y);
        ImGui.setWindowFontScale(1.0f);
    }

    /**
     * Render a single navigation item with polished custom drawing.
     */
    private void renderNavigationItem(NavigationItem item) {
        ThemeDefinition theme = themeManager.getCurrentTheme();
        boolean isSelected = hubState.getSelectedNavItem() == item;

        float availWidth = ImGui.getContentRegionAvailX();
        float itemWidth = availWidth - (SIDEBAR_PADDING * 2);
        ImGui.setCursorPosX(ImGui.getCursorPosX() + SIDEBAR_PADDING);

        ImVec2 screenPos = ImGui.getCursorScreenPos();

        ImGui.invisibleButton("##nav_" + item.getId(), itemWidth, NAV_ITEM_HEIGHT);
        boolean isHovered = ImGui.isItemHovered();
        boolean isClicked = ImGui.isItemClicked();

        if (isClicked) {
            item.select();
            hubState.setSelectedNavItem(item);
            hubState.clearSelection();
        }

        ImDrawList drawList = ImGui.getWindowDrawList();
        float x1 = screenPos.x;
        float y1 = screenPos.y;
        float x2 = x1 + itemWidth;
        float y2 = y1 + NAV_ITEM_HEIGHT;

        ImVec4 accentBase = getAccentColor(theme);

        // Selected state: gradient background + accent bar + glow
        if (isSelected) {
            // Gradient fill: accent color fading from left to transparent on right
            int leftColor = ImColor.rgba(accentBase.x, accentBase.y, accentBase.z, 0.20f);
            int rightColor = ImColor.rgba(accentBase.x, accentBase.y, accentBase.z, 0.05f);
            drawList.addRectFilledMultiColor(x1, y1, x2, y2, leftColor, rightColor, rightColor, leftColor);

            // Rounded border overlay for shape
            int borderColor = ImColor.rgba(accentBase.x, accentBase.y, accentBase.z, 0.4f);
            drawList.addRect(x1, y1, x2, y2, borderColor, NAV_ITEM_ROUNDING, 0, 1.0f);

            // Left accent bar with glow
            int accentSolid = ImColor.rgba(accentBase.x, accentBase.y, accentBase.z, 1.0f);
            int accentGlow = ImColor.rgba(accentBase.x, accentBase.y, accentBase.z, 0.25f);

            // Glow behind accent bar
            drawList.addRectFilled(
                    x1 - 2.0f, y1 + ACCENT_INSET_Y - 2.0f,
                    x1 + ACCENT_WIDTH + 4.0f, y2 - ACCENT_INSET_Y + 2.0f,
                    accentGlow, 3.0f
            );
            // Solid accent bar
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

            // Brighter border on hover
            int hoverBorder = ImColor.rgba(accentBase.x, accentBase.y, accentBase.z, 0.5f);
            drawList.addRect(x1, y1, x2, y2, hoverBorder, NAV_ITEM_ROUNDING, 0, 1.0f);

            // Hint of accent bar on hover
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

            // Visible border
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
        float textX = x1 + NAV_TEXT_LEFT_PADDING;
        ImGui.setWindowFontScale(1.0f);
        float textHeight = ImGui.calcTextSize(item.getLabel()).y;
        float textY = y1 + (NAV_ITEM_HEIGHT - textHeight) * 0.5f;

        ImVec4 textBase = theme.getColor(ImGuiCol.Text);
        float tr = textBase != null ? textBase.x : 0.88f;
        float tg = textBase != null ? textBase.y : 0.89f;
        float tb = textBase != null ? textBase.z : 0.91f;

        int textColor;
        if (isSelected) {
            // Full brightness, slight bold via double-pass
            textColor = ImColor.rgba(tr, tg, tb, 1.0f);
            drawList.addText(textX, textY, textColor, item.getLabel());
            drawList.addText(textX + 0.5f, textY, textColor, item.getLabel());
        } else {
            // Full alpha — always legible in both light and dark themes
            textColor = ImColor.rgba(tr, tg, tb, isHovered ? 1.0f : 0.9f);
            drawList.addText(textX, textY, textColor, item.getLabel());
        }
    }

    /**
     * Render the Preferences button using the same style as navigation items,
     * but without selection state (it opens a window instead of switching views).
     */
    private void renderPreferencesButton() {
        ThemeDefinition theme = themeManager.getCurrentTheme();

        float availWidth = ImGui.getContentRegionAvailX();
        float itemWidth = availWidth - (SIDEBAR_PADDING * 2);
        ImGui.setCursorPosX(ImGui.getCursorPosX() + SIDEBAR_PADDING);

        ImVec2 screenPos = ImGui.getCursorScreenPos();

        ImGui.invisibleButton("##nav_preferences", itemWidth, NAV_ITEM_HEIGHT);
        boolean isHovered = ImGui.isItemHovered();
        boolean isClicked = ImGui.isItemClicked();

        if (isClicked && onPreferencesClicked != null) {
            onPreferencesClicked.run();
        }

        ImDrawList drawList = ImGui.getWindowDrawList();
        float x1 = screenPos.x;
        float y1 = screenPos.y;
        float x2 = x1 + itemWidth;
        float y2 = y1 + NAV_ITEM_HEIGHT;

        ImVec4 accentBase = getAccentColor(theme);

        if (isHovered) {
            // Hover: slightly brighter background
            ImVec4 hoverBase = theme.getColor(ImGuiCol.HeaderHovered);
            int hoverColor;
            if (hoverBase != null) {
                hoverColor = ImColor.rgba(hoverBase.x, hoverBase.y, hoverBase.z, 0.15f);
            } else {
                hoverColor = ImColor.rgba(1.0f, 1.0f, 1.0f, 0.08f);
            }
            drawList.addRectFilled(x1, y1, x2, y2, hoverColor, NAV_ITEM_ROUNDING);

            // Brighter border on hover
            int hoverBorder = ImColor.rgba(accentBase.x, accentBase.y, accentBase.z, 0.5f);
            drawList.addRect(x1, y1, x2, y2, hoverBorder, NAV_ITEM_ROUNDING, 0, 1.0f);

            // Hint of accent bar on hover
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

            // Visible border
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
        String label = "Preferences";
        float textX = x1 + NAV_TEXT_LEFT_PADDING;
        ImGui.setWindowFontScale(1.0f);
        float textHeight = ImGui.calcTextSize(label).y;
        float textY = y1 + (NAV_ITEM_HEIGHT - textHeight) * 0.5f;

        ImVec4 textBase = theme.getColor(ImGuiCol.Text);
        float tr = textBase != null ? textBase.x : 0.88f;
        float tg = textBase != null ? textBase.y : 0.89f;
        float tb = textBase != null ? textBase.z : 0.91f;

        int textColor = ImColor.rgba(tr, tg, tb, isHovered ? 1.0f : 0.9f);
        drawList.addText(textX, textY, textColor, label);
    }

    /**
     * Render a subtle accent dot at the bottom of the sidebar.
     */
    private void renderBottomAccent() {
        ThemeDefinition theme = themeManager.getCurrentTheme();
        ImDrawList drawList = ImGui.getWindowDrawList();
        float sidebarWidth = ImGui.getWindowWidth();
        float windowHeight = ImGui.getWindowHeight();
        ImVec2 winPos = ImGui.getWindowPos();

        ImVec4 accentBase = getAccentColor(theme);
        int dotColor = ImColor.rgba(accentBase.x, accentBase.y, accentBase.z, 0.25f);
        float dotX = winPos.x + sidebarWidth * 0.5f;
        float dotY = winPos.y + windowHeight - 16.0f;
        drawList.addCircleFilled(dotX, dotY, 2.0f, dotColor, 12);
    }

    /**
     * Render a soft radial glow effect using layered transparent circles.
     */
    private void renderRadialGlow(ImDrawList drawList, float cx, float cy, float radius, ImVec4 color, float maxAlpha) {
        int layers = 6;
        for (int i = layers; i >= 1; i--) {
            float t = (float) i / layers;
            float r = radius * t;
            float alpha = maxAlpha * (1.0f - t) * 1.5f;
            if (alpha > maxAlpha) alpha = maxAlpha;
            int c = ImColor.rgba(color.x, color.y, color.z, alpha);
            drawList.addCircleFilled(cx, cy, r, c, 24);
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
     * Create navigation items.
     */
    private List<NavigationItem> createNavigationItems() {
        List<NavigationItem> items = new ArrayList<>();

        items.add(new NavigationItem(
                "templates",
                "Templates",
                NavigationItem.ViewType.TEMPLATES,
                () -> hubState.setCurrentView(NavigationItem.ViewType.TEMPLATES)
        ));

        items.add(new NavigationItem(
                "recent-projects",
                "Projects",
                NavigationItem.ViewType.RECENT_PROJECTS,
                () -> hubState.setCurrentView(NavigationItem.ViewType.RECENT_PROJECTS)
        ));

        items.add(new NavigationItem(
                "learn",
                "Learn",
                NavigationItem.ViewType.LEARN,
                () -> hubState.setCurrentView(NavigationItem.ViewType.LEARN)
        ));

        return items;
    }
}
