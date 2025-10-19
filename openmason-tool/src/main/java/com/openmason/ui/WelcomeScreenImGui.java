package com.openmason.ui;

import com.openmason.ui.themes.core.ThemeManager;
import com.openmason.ui.themes.core.ThemeDefinition;
import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Welcome screen for Open Mason application.
 * Displays logo and tool selection cards in a sleek, modern interface.
 *
 * Follows KISS, SOLID, YAGNI, DRY principles:
 * - Single responsibility: Display welcome screen
 * - Uses existing LogoManager and ThemeManager
 * - Extensible design for multiple tools
 */
public class WelcomeScreenImGui {

    private static final Logger logger = LoggerFactory.getLogger(WelcomeScreenImGui.class);

    // Window configuration
    private static final String WINDOW_TITLE = "Welcome to Open Mason";

    // Dynamic window size (calculated from viewport)
    private float windowWidth = 0.0f;
    private float windowHeight = 0.0f;

    // Layout constants
    private static final float LOGO_SIZE = 256.0f;
    private static final float TITLE_FONT_SIZE = 24.0f;
    private static final float SUBTITLE_FONT_SIZE = 16.0f;
    private static final float CARD_WIDTH = 240.0f;
    private static final float CARD_HEIGHT = 180.0f;
    private static final float CARD_PADDING = 18.0f;
    private static final float CARD_SPACING = 30.0f;
    private static final float CARD_ROUNDING = 8.0f;

    // Dependencies
    private final ThemeManager themeManager;
    private final LogoManager logoManager;

    // Tool cards
    private final List<ToolCard> toolCards;

    // State
    private boolean shouldClose = false;
    private float hoverAnimationTime = 0.0f;
    private int hoveredCardIndex = -1;

    /**
     * Create welcome screen with dependency injection.
     */
    public WelcomeScreenImGui(ThemeManager themeManager) {
        if (themeManager == null) {
            throw new IllegalArgumentException("ThemeManager cannot be null");
        }

        this.themeManager = themeManager;
        this.logoManager = LogoManager.getInstance();
        this.toolCards = new ArrayList<>();

        logger.info("Welcome screen initialized");
    }

    /**
     * Add a tool card to the welcome screen.
     * @param toolCard The tool card to add
     */
    public void addToolCard(ToolCard toolCard) {
        if (toolCard != null) {
            toolCards.add(toolCard);
        }
    }

    /**
     * Render the welcome screen.
     */
    public void render() {
        // Make the window fullscreen
        ImVec2 viewportSize = ImGui.getMainViewport().getSize();
        ImVec2 viewportPos = ImGui.getMainViewport().getPos();

        windowWidth = viewportSize.x;
        windowHeight = viewportSize.y;

        ImGui.setNextWindowPos(viewportPos.x, viewportPos.y);
        ImGui.setNextWindowSize(windowWidth, windowHeight);

        // Window flags: fullscreen, no decorations
        int windowFlags = ImGuiWindowFlags.NoResize |
                         ImGuiWindowFlags.NoCollapse |
                         ImGuiWindowFlags.NoMove |
                         ImGuiWindowFlags.NoTitleBar |
                         ImGuiWindowFlags.NoBringToFrontOnFocus;

        // Apply theme styling
        ThemeDefinition theme = themeManager.getCurrentTheme();
        applyWelcomeWindowStyling(theme);

        if (ImGui.begin(WINDOW_TITLE, windowFlags)) {
            // Add close button in top-right corner (since we removed title bar)
            renderCloseButton();

            // Add vertical spacing to center content
            float contentHeight = LOGO_SIZE + 300.0f; // Approximate total content height
            float topPadding = Math.max(0, (windowHeight - contentHeight) * 0.3f);
            ImGui.setCursorPosY(ImGui.getCursorPosY() + topPadding);

            // Logo section
            renderLogo();

            // Title and subtitle
            renderTitle();

            ImGui.spacing();
            ImGui.spacing();
            ImGui.separator();
            ImGui.spacing();
            ImGui.spacing();

            // Subtitle
            renderSubtitle("Select a tool to get started:");

            ImGui.spacing();
            ImGui.spacing();

            // Tool cards grid
            renderToolCards();

            ImGui.end();
        } else {
            // Window was closed via X button
            shouldClose = true;
        }

        // Pop styling
        ImGui.popStyleVar(2);
        ImGui.popStyleColor(1);
    }

    /**
     * Update welcome screen state (for animations).
     */
    public void update(float deltaTime) {
        // Update hover animation
        if (hoveredCardIndex >= 0) {
            hoverAnimationTime = Math.min(hoverAnimationTime + deltaTime * 3.0f, 1.0f);
        } else {
            hoverAnimationTime = Math.max(hoverAnimationTime - deltaTime * 3.0f, 0.0f);
        }
    }

    /**
     * Check if the welcome screen should close.
     * @return true if the window close button was clicked
     */
    public boolean shouldClose() {
        return shouldClose;
    }

    /**
     * Reset the close flag.
     */
    public void resetCloseFlag() {
        shouldClose = false;
    }

    // Private rendering methods

    private void renderCloseButton() {
        // Position close button in top-right corner
        float buttonSize = 30.0f;
        float padding = 10.0f;

        ImGui.setCursorPos(windowWidth - buttonSize - padding, padding);

        ThemeDefinition theme = themeManager.getCurrentTheme();
        ImVec4 closeColor = theme.getColor(ImGuiCol.Text);
        if (closeColor != null) {
            ImGui.pushStyleColor(ImGuiCol.Text, closeColor.x, closeColor.y, closeColor.z, closeColor.w);
        }

        if (ImGui.button("X##close", buttonSize, buttonSize)) {
            shouldClose = true;
        }

        if (closeColor != null) {
            ImGui.popStyleColor();
        }

        // Tooltip
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Exit Open Mason");
        }
    }

    private void renderLogo() {
        // Center the logo horizontally
        float logoDisplaySize = LOGO_SIZE;
        ImVec2 scaledSize = logoManager.getScaledLogoSize(logoDisplaySize, logoDisplaySize);

        float cursorX = (windowWidth - scaledSize.x) * 0.5f;
        ImGui.setCursorPosX(cursorX);

        // Render logo
        logoManager.renderLogo(scaledSize.x, scaledSize.y);

        ImGui.spacing();
    }

    private void renderTitle() {
        String title = "Open Mason";
        String subtitle = "Voxel Game Engine & Toolset";

        // Calculate text width for centering
        ImVec2 titleSize = ImGui.calcTextSize(title);
        ImVec2 subtitleSize = ImGui.calcTextSize(subtitle);

        ThemeDefinition theme = themeManager.getCurrentTheme();
        ImDrawList drawList = ImGui.getWindowDrawList();

        // Title with fancy glow effect
        float titleX = (windowWidth - titleSize.x) * 0.5f;

        // Set cursor position FIRST, then get screen position
        ImGui.setCursorPosX(titleX);
        ImVec2 titleScreenPos = ImGui.getCursorScreenPos();

        // Use a vibrant cyan/blue color for the title
        ImVec4 titleGlowColor = new ImVec4(0.3f, 0.8f, 1.0f, 1.0f);  // Bright cyan
        ImVec4 titleMainColor = new ImVec4(0.9f, 0.95f, 1.0f, 1.0f); // Almost white with slight blue tint

        // Draw multiple shadow/glow layers for depth
        for (int i = 3; i > 0; i--) {
            float offset = i * 2.0f;
            float alpha = 0.15f / i;
            int glowColor = ImColor.rgba(
                titleGlowColor.x,
                titleGlowColor.y,
                titleGlowColor.z,
                alpha
            );

            // Draw glow layers in all directions
            for (float dx = -offset; dx <= offset; dx += offset) {
                for (float dy = -offset; dy <= offset; dy += offset) {
                    if (dx == 0 && dy == 0) continue;
                    drawList.addText(
                        titleScreenPos.x + dx,
                        titleScreenPos.y + dy,
                        glowColor,
                        title
                    );
                }
            }
        }

        // Draw the main title text (cursor already positioned correctly)
        ImGui.pushStyleColor(ImGuiCol.Text, titleMainColor.x, titleMainColor.y, titleMainColor.z, titleMainColor.w);
        ImGui.text(title);
        ImGui.popStyleColor();

        // Subtitle with better visibility
        float subtitleX = (windowWidth - subtitleSize.x) * 0.5f;
        ImGui.setCursorPosX(subtitleX);

        // Use a softer but more visible color for subtitle
        ImVec4 subtitleColor = new ImVec4(0.65f, 0.75f, 0.85f, 1.0f); // Soft cyan-gray
        ImGui.pushStyleColor(ImGuiCol.Text, subtitleColor.x, subtitleColor.y, subtitleColor.z, subtitleColor.w);
        ImGui.text(subtitle);
        ImGui.popStyleColor();
    }

    private void renderSubtitle(String text) {
        ImVec2 textSize = ImGui.calcTextSize(text);
        float textX = (windowWidth - textSize.x) * 0.5f;
        ImGui.setCursorPosX(textX);
        ImGui.text(text);
    }

    private void renderToolCards() {
        if (toolCards.isEmpty()) {
            ImGui.text("No tools available");
            return;
        }

        // Calculate starting position to center the grid
        int cardsPerRow = 2;
        float gridWidth = (CARD_WIDTH * cardsPerRow) + (CARD_SPACING * (cardsPerRow - 1));
        float startX = (windowWidth - gridWidth) * 0.5f;

        ImGui.setCursorPosX(startX);

        ThemeDefinition theme = themeManager.getCurrentTheme();

        for (int i = 0; i < toolCards.size(); i++) {
            ToolCard card = toolCards.get(i);

            // Render card
            boolean clicked = renderToolCard(card, i, theme);

            if (clicked && card.isEnabled()) {
                card.select();
            }

            // Layout: 2 cards per row
            if ((i + 1) % cardsPerRow == 0) {
                ImGui.newLine();
                ImGui.setCursorPosX(startX);
            } else {
                ImGui.sameLine(0, CARD_SPACING);
            }
        }
    }

    private boolean renderToolCard(ToolCard card, int index, ThemeDefinition theme) {
        ImVec2 cursorPos = ImGui.getCursorScreenPos();
        ImDrawList drawList = ImGui.getWindowDrawList();

        // Calculate card bounds
        float x1 = cursorPos.x;
        float y1 = cursorPos.y;
        float x2 = x1 + CARD_WIDTH;
        float y2 = y1 + CARD_HEIGHT;

        // Check hover state
        boolean isHovered = ImGui.isMouseHoveringRect(x1, y1, x2, y2);
        if (isHovered) {
            hoveredCardIndex = index;
            // Set cursor to hand pointer for enabled cards
            if (card.isEnabled()) {
                ImGui.setMouseCursor(imgui.flag.ImGuiMouseCursor.Hand);
            }
        } else if (hoveredCardIndex == index) {
            hoveredCardIndex = -1;
        }

        // Get theme colors as ImVec4
        ImVec4 bgColor = theme.getColor(ImGuiCol.ChildBg);
        if (bgColor == null) bgColor = theme.getColor(ImGuiCol.WindowBg);
        if (bgColor == null) bgColor = new ImVec4(0.1f, 0.1f, 0.1f, 1.0f);

        ImVec4 borderCol = theme.getColor(ImGuiCol.Border);
        if (borderCol == null) borderCol = new ImVec4(0.4f, 0.4f, 0.4f, 1.0f);

        ImVec4 textCol = theme.getColor(ImGuiCol.Text);
        if (textCol == null) textCol = new ImVec4(1.0f, 1.0f, 1.0f, 1.0f);

        ImVec4 textDisabledCol = theme.getColor(ImGuiCol.TextDisabled);
        if (textDisabledCol == null) textDisabledCol = new ImVec4(0.5f, 0.5f, 0.5f, 1.0f);

        ImVec4 accentCol = theme.getColor(ImGuiCol.ButtonHovered);
        if (accentCol == null) accentCol = new ImVec4(0.26f, 0.59f, 0.98f, 1.0f);

        // Calculate colors based on state
        int backgroundColor;
        int borderColor;
        int textColor;

        if (!card.isEnabled()) {
            // Disabled state
            backgroundColor = ImColor.rgba(bgColor.x, bgColor.y, bgColor.z, 0.3f);
            borderColor = ImColor.rgba(borderCol.x, borderCol.y, borderCol.z, 0.3f);
            textColor = ImColor.rgba(textDisabledCol.x, textDisabledCol.y, textDisabledCol.z, 0.5f);
        } else if (isHovered) {
            // Hovered state
            backgroundColor = ImColor.rgba(accentCol.x, accentCol.y, accentCol.z, 0.2f);
            borderColor = ImColor.rgba(accentCol.x, accentCol.y, accentCol.z, 1.0f);
            textColor = ImColor.rgba(textCol.x, textCol.y, textCol.z, 1.0f);
        } else {
            // Normal state
            backgroundColor = ImColor.rgba(bgColor.x, bgColor.y, bgColor.z, 1.0f);
            borderColor = ImColor.rgba(borderCol.x, borderCol.y, borderCol.z, 1.0f);
            textColor = ImColor.rgba(textCol.x, textCol.y, textCol.z, 1.0f);
        }

        // Draw subtle drop shadow for depth
        if (card.isEnabled()) {
            float shadowOffset = isHovered ? 6.0f : 4.0f;
            int shadowColor = ImColor.rgba(0, 0, 0, isHovered ? 0.3f : 0.2f);
            drawList.addRectFilled(
                x1 + shadowOffset,
                y1 + shadowOffset,
                x2 + shadowOffset,
                y2 + shadowOffset,
                shadowColor,
                CARD_ROUNDING
            );
        }

        // Draw card background
        drawList.addRectFilled(x1, y1, x2, y2, backgroundColor, CARD_ROUNDING);

        // Draw card border
        float borderThickness = isHovered && card.isEnabled() ? 3.0f : 2.0f;
        drawList.addRect(x1, y1, x2, y2, borderColor, CARD_ROUNDING, 0, borderThickness);

        // Draw card content
        ImGui.setCursorScreenPos(x1 + CARD_PADDING, y1 + CARD_PADDING);

        // Card title (slightly larger)
        ImGui.pushStyleColor(ImGuiCol.Text, textColor);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 0, 8.0f);
        ImGui.text(card.getName());
        ImGui.popStyleVar();

        // Add separator line under title
        ImGui.setCursorScreenPos(x1 + CARD_PADDING, y1 + CARD_PADDING + 25);
        ImVec4 separatorColor = card.isEnabled() ? borderCol : textDisabledCol;
        int separator = ImColor.rgba(separatorColor.x, separatorColor.y, separatorColor.z, 0.3f);
        drawList.addLine(
            x1 + CARD_PADDING,
            y1 + CARD_PADDING + 25,
            x2 - CARD_PADDING,
            y1 + CARD_PADDING + 25,
            separator,
            1.0f
        );

        // Card description with proper wrapping using child window
        ImGui.setCursorScreenPos(x1 + CARD_PADDING, y1 + CARD_PADDING + 35);

        // Calculate available space for description
        float descWidth = CARD_WIDTH - (CARD_PADDING * 2);
        float descHeight = CARD_HEIGHT - 35 - CARD_PADDING - (card.isEnabled() ? 0 : 25); // Reserve space for "Coming Soon"

        // Use child window for proper text wrapping and clipping
        ImGui.beginChild("##card_desc_" + index, descWidth, descHeight, false, ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoBackground);

        // Now text wrapping will work correctly within the child window
        ImGui.pushTextWrapPos(descWidth);
        ImGui.textWrapped(card.getDescription());
        ImGui.popTextWrapPos();

        ImGui.endChild();

        // "Coming Soon" badge for disabled cards
        if (!card.isEnabled()) {
            ImGui.setCursorScreenPos(x1 + CARD_PADDING, y2 - CARD_PADDING - 20);
            ImGui.pushStyleColor(ImGuiCol.Text, ImColor.rgba(
                textDisabledCol.x,
                textDisabledCol.y,
                textDisabledCol.z,
                0.7f
            ));
            ImGui.text("Coming Soon");
            ImGui.popStyleColor();
        }

        ImGui.popStyleColor();

        // Invisible button for click detection
        ImGui.setCursorScreenPos(x1, y1);
        ImGui.pushStyleColor(ImGuiCol.Button, 0);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, 0);
        boolean clicked = ImGui.button("##card_" + index, CARD_WIDTH, CARD_HEIGHT);
        ImGui.popStyleColor(3);

        // Restore cursor position for next card
        ImGui.setCursorScreenPos(x1, y2);

        return clicked;
    }

    private void applyWelcomeWindowStyling(ThemeDefinition theme) {
        // Apply theme background color
        ImVec4 bgColor = theme.getColor(ImGuiCol.WindowBg);
        if (bgColor != null) {
            ImGui.pushStyleColor(ImGuiCol.WindowBg, bgColor.x, bgColor.y, bgColor.z, bgColor.w);
        } else {
            ImGui.pushStyleColor(ImGuiCol.WindowBg, 0.1f, 0.1f, 0.1f, 1.0f);
        }

        // Apply padding and rounding
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 20.0f, 20.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 12.0f);
    }
}
