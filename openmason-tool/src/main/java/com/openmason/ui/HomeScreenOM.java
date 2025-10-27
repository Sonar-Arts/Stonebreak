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
 * Home screen for Open Mason application.
 *
 * Displays a fullscreen welcome interface with the following features:
 * - Centered Open Mason logo with scaling support
 * - Title text with multi-layer cyan glow effect
 * - Grid-based tool card layout with hover effects
 * - State-based styling (normal, hovered, disabled)
 * - Theme integration with graceful fallbacks
 * - Smooth animations for card interactions
 *
 * Tool cards are arranged in a 2-column grid with automatic wrapping.
 * Each card supports enabled/disabled states with appropriate visual feedback.
 */
public class HomeScreenOM {

    private static final Logger logger = LoggerFactory.getLogger(HomeScreenOM.class);

    private static final String WINDOW_TITLE = "Welcome to Open Mason";

    // Window dimensions - updated dynamically each frame from viewport
    private float windowWidth = 0.0f;
    private float windowHeight = 0.0f;

    // Layout configuration constants for visual consistency
    private static final float LOGO_SIZE = 256.0f;
    private static final float TITLE_FONT_SIZE = 24.0f;
    private static final float SUBTITLE_FONT_SIZE = 16.0f;
    private static final float CARD_WIDTH = 240.0f;
    private static final float CARD_HEIGHT = 180.0f;
    private static final float CARD_PADDING = 18.0f;
    private static final float CARD_SPACING = 30.0f;
    private static final float CARD_ROUNDING = 8.0f;

    // Core dependencies
    private final ThemeManager themeManager;
    private final LogoManager logoManager;
    private final List<ToolCard> toolCards;

    // UI state management
    private boolean shouldClose = false;
    private float hoverAnimationTime = 0.0f;  // Smooth animation timer for card hover effects
    private int hoveredCardIndex = -1;         // Currently hovered card index, -1 if none

    /**
     * Create Home screen with dependency injection.
     */
    public HomeScreenOM(ThemeManager themeManager) {
        if (themeManager == null) {
            throw new IllegalArgumentException("ThemeManager cannot be null");
        }

        this.themeManager = themeManager;
        this.logoManager = LogoManager.getInstance();
        this.toolCards = new ArrayList<>();

        logger.info("Home screen initialized");
    }

    /**
     * Add a tool card to the Home screen.
     * @param toolCard The tool card to add
     */
    public void addToolCard(ToolCard toolCard) {
        if (toolCard != null) {
            toolCards.add(toolCard);
        }
    }

    /**
     * Render the Home screen.
     * Creates a fullscreen window with centered content including logo, title, and tool cards.
     */
    public void render() {
        // Match window to viewport size for fullscreen effect
        ImVec2 viewportSize = ImGui.getMainViewport().getSize();
        ImVec2 viewportPos = ImGui.getMainViewport().getPos();

        windowWidth = viewportSize.x;
        windowHeight = viewportSize.y;

        ImGui.setNextWindowPos(viewportPos.x, viewportPos.y);
        ImGui.setNextWindowSize(windowWidth, windowHeight);

        // Configure as borderless fullscreen window with no decorations
        int windowFlags = ImGuiWindowFlags.NoResize |
                         ImGuiWindowFlags.NoCollapse |
                         ImGuiWindowFlags.NoMove |
                         ImGuiWindowFlags.NoTitleBar |
                         ImGuiWindowFlags.NoBringToFrontOnFocus;

        ThemeDefinition theme = themeManager.getCurrentTheme();
        applyHomeWindowStyling(theme);

        if (ImGui.begin(WINDOW_TITLE, windowFlags)) {
            renderCloseButton();

            // Vertically center the content with top-weighted positioning (30% from top)
            float contentHeight = LOGO_SIZE + 300.0f;
            float topPadding = Math.max(0, (windowHeight - contentHeight) * 0.3f);
            ImGui.setCursorPosY(ImGui.getCursorPosY() + topPadding);

            renderLogo();
            renderTitle();

            ImGui.spacing();
            ImGui.spacing();
            ImGui.separator();
            ImGui.spacing();
            ImGui.spacing();

            renderSubtitle("Select a tool to get started:");

            ImGui.spacing();
            ImGui.spacing();

            renderToolCards();

            ImGui.end();
        } else {
            shouldClose = true;
        }

        ImGui.popStyleVar(2);
        ImGui.popStyleColor(1);
    }

    /**
     * Update Home screen state (for animations).
     * @param deltaTime Time elapsed since last frame in seconds
     */
    public void update(float deltaTime) {
        // Smoothly animate hover state with 3x speed multiplier
        if (hoveredCardIndex >= 0) {
            hoverAnimationTime = Math.min(hoverAnimationTime + deltaTime * 3.0f, 1.0f);
        } else {
            hoverAnimationTime = Math.max(hoverAnimationTime - deltaTime * 3.0f, 0.0f);
        }
    }

    /**
     * Check if the Home screen should close.
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

    /**
     * Render the close button in the top-right corner.
     * Positioned manually since we use NoTitleBar window flags.
     */
    private void renderCloseButton() {
        float buttonSize = 30.0f;
        float padding = 10.0f;

        // Position in top-right corner
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

        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Exit Open Mason");
        }
    }

    /**
     * Render the Open Mason logo, horizontally centered.
     */
    private void renderLogo() {
        float logoDisplaySize = LOGO_SIZE;
        ImVec2 scaledSize = logoManager.getScaledLogoSize(logoDisplaySize, logoDisplaySize);

        // Center horizontally
        float cursorX = (windowWidth - scaledSize.x) * 0.5f;
        ImGui.setCursorPosX(cursorX);

        logoManager.renderLogo(scaledSize.x, scaledSize.y);

        ImGui.spacing();
    }

    /**
     * Render the title and subtitle with a cyan glow effect.
     * Uses multi-layer glow technique for depth and visual impact.
     */
    private void renderTitle() {
        String title = "Open Mason";
        String subtitle = "Voxel Game Engine & Toolset";

        ImVec2 titleSize = ImGui.calcTextSize(title);
        ImVec2 subtitleSize = ImGui.calcTextSize(subtitle);

        ThemeDefinition theme = themeManager.getCurrentTheme();
        ImDrawList drawList = ImGui.getWindowDrawList();

        // Center title and get screen position for glow effect
        float titleX = (windowWidth - titleSize.x) * 0.5f;
        ImGui.setCursorPosX(titleX);
        ImVec2 titleScreenPos = ImGui.getCursorScreenPos();

        ImVec4 titleGlowColor = new ImVec4(0.3f, 0.8f, 1.0f, 1.0f);  // Bright cyan
        ImVec4 titleMainColor = new ImVec4(0.9f, 0.95f, 1.0f, 1.0f); // Almost white with blue tint

        // Render multi-layer glow effect (3 layers with increasing offset and decreasing alpha)
        for (int i = 3; i > 0; i--) {
            float offset = i * 2.0f;
            float alpha = 0.15f / i;
            int glowColor = ImColor.rgba(
                titleGlowColor.x,
                titleGlowColor.y,
                titleGlowColor.z,
                alpha
            );

            // Draw glow in 8 directions (omitting center)
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

        // Render main title text on top of glow
        ImGui.pushStyleColor(ImGuiCol.Text, titleMainColor.x, titleMainColor.y, titleMainColor.z, titleMainColor.w);
        ImGui.text(title);
        ImGui.popStyleColor();

        // Render subtitle with softer cyan-gray color
        float subtitleX = (windowWidth - subtitleSize.x) * 0.5f;
        ImGui.setCursorPosX(subtitleX);

        ImVec4 subtitleColor = new ImVec4(0.65f, 0.75f, 0.85f, 1.0f);
        ImGui.pushStyleColor(ImGuiCol.Text, subtitleColor.x, subtitleColor.y, subtitleColor.z, subtitleColor.w);
        ImGui.text(subtitle);
        ImGui.popStyleColor();
    }

    /**
     * Render a centered subtitle text.
     * @param text The text to render
     */
    private void renderSubtitle(String text) {
        ImVec2 textSize = ImGui.calcTextSize(text);
        float textX = (windowWidth - textSize.x) * 0.5f;
        ImGui.setCursorPosX(textX);
        ImGui.text(text);
    }

    /**
     * Render all tool cards in a centered grid layout.
     * Cards are arranged in rows of 2 with automatic wrapping.
     */
    private void renderToolCards() {
        if (toolCards.isEmpty()) {
            ImGui.text("No tools available");
            return;
        }

        // Calculate centered grid positioning (2 cards per row)
        int cardsPerRow = 2;
        float gridWidth = (CARD_WIDTH * cardsPerRow) + (CARD_SPACING * (cardsPerRow - 1));
        float startX = (windowWidth - gridWidth) * 0.5f;

        ImGui.setCursorPosX(startX);

        ThemeDefinition theme = themeManager.getCurrentTheme();

        // Render each card and handle grid layout
        for (int i = 0; i < toolCards.size(); i++) {
            ToolCard card = toolCards.get(i);

            boolean clicked = renderToolCard(card, i, theme);

            if (clicked && card.isEnabled()) {
                card.select();
            }

            // Move to next row or add horizontal spacing
            if ((i + 1) % cardsPerRow == 0) {
                ImGui.newLine();
                ImGui.setCursorPosX(startX);
            } else {
                ImGui.sameLine(0, CARD_SPACING);
            }
        }
    }

    /**
     * Render an individual tool card with state-based styling.
     * Handles hover effects, disabled state, shadows, and click detection.
     *
     * @param card The tool card to render
     * @param index The card's index in the list
     * @param theme Current theme for color resolution
     * @return true if the card was clicked
     */
    private boolean renderToolCard(ToolCard card, int index, ThemeDefinition theme) {
        ImVec2 cursorPos = ImGui.getCursorScreenPos();
        ImDrawList drawList = ImGui.getWindowDrawList();

        // Calculate card bounds
        float x1 = cursorPos.x;
        float y1 = cursorPos.y;
        float x2 = x1 + CARD_WIDTH;
        float y2 = y1 + CARD_HEIGHT;

        // Track hover state and update cursor
        boolean isHovered = ImGui.isMouseHoveringRect(x1, y1, x2, y2);
        if (isHovered) {
            hoveredCardIndex = index;
            if (card.isEnabled()) {
                ImGui.setMouseCursor(imgui.flag.ImGuiMouseCursor.Hand);
            }
        } else if (hoveredCardIndex == index) {
            hoveredCardIndex = -1;
        }

        // Resolve theme colors with fallbacks
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

        // Calculate state-based colors (disabled, hovered, or normal)
        int backgroundColor;
        int borderColor;
        int textColor;

        if (!card.isEnabled()) {
            backgroundColor = ImColor.rgba(bgColor.x, bgColor.y, bgColor.z, 0.3f);
            borderColor = ImColor.rgba(borderCol.x, borderCol.y, borderCol.z, 0.3f);
            textColor = ImColor.rgba(textDisabledCol.x, textDisabledCol.y, textDisabledCol.z, 0.5f);
        } else if (isHovered) {
            backgroundColor = ImColor.rgba(accentCol.x, accentCol.y, accentCol.z, 0.2f);
            borderColor = ImColor.rgba(accentCol.x, accentCol.y, accentCol.z, 1.0f);
            textColor = ImColor.rgba(textCol.x, textCol.y, textCol.z, 1.0f);
        } else {
            backgroundColor = ImColor.rgba(bgColor.x, bgColor.y, bgColor.z, 1.0f);
            borderColor = ImColor.rgba(borderCol.x, borderCol.y, borderCol.z, 1.0f);
            textColor = ImColor.rgba(textCol.x, textCol.y, textCol.z, 1.0f);
        }

        // Draw drop shadow for depth (only for enabled cards)
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

        // Draw card background and border
        drawList.addRectFilled(x1, y1, x2, y2, backgroundColor, CARD_ROUNDING);

        float borderThickness = isHovered && card.isEnabled() ? 3.0f : 2.0f;
        drawList.addRect(x1, y1, x2, y2, borderColor, CARD_ROUNDING, 0, borderThickness);

        // Render card title
        ImGui.setCursorScreenPos(x1 + CARD_PADDING, y1 + CARD_PADDING);

        ImGui.pushStyleColor(ImGuiCol.Text, textColor);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 0, 8.0f);
        ImGui.text(card.getName());
        ImGui.popStyleVar();

        // Draw separator line below title
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

        // Render card description with text wrapping using child window for proper clipping
        ImGui.setCursorScreenPos(x1 + CARD_PADDING, y1 + CARD_PADDING + 35);

        float descWidth = CARD_WIDTH - (CARD_PADDING * 2);
        float descHeight = CARD_HEIGHT - 35 - CARD_PADDING - (card.isEnabled() ? 0 : 25);

        ImGui.beginChild("##card_desc_" + index, descWidth, descHeight, false, ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoBackground);

        ImGui.pushTextWrapPos(descWidth);
        ImGui.textWrapped(card.getDescription());
        ImGui.popTextWrapPos();

        ImGui.endChild();

        // Show "Coming Soon" badge for disabled cards
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

        // Invisible button overlay for click detection
        ImGui.setCursorScreenPos(x1, y1);
        ImGui.pushStyleColor(ImGuiCol.Button, 0);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, 0);
        boolean clicked = ImGui.button("##card_" + index, CARD_WIDTH, CARD_HEIGHT);
        ImGui.popStyleColor(3);

        // Restore cursor for next card
        ImGui.setCursorScreenPos(x1, y2);

        return clicked;
    }

    /**
     * Apply Home screen window styling using theme colors.
     * Pushes style colors and variables onto ImGui stack (must be popped after window ends).
     *
     * @param theme The current theme definition
     */
    private void applyHomeWindowStyling(ThemeDefinition theme) {
        // Apply theme background color with fallback
        ImVec4 bgColor = theme.getColor(ImGuiCol.WindowBg);
        if (bgColor != null) {
            ImGui.pushStyleColor(ImGuiCol.WindowBg, bgColor.x, bgColor.y, bgColor.z, bgColor.w);
        } else {
            ImGui.pushStyleColor(ImGuiCol.WindowBg, 0.1f, 0.1f, 0.1f, 1.0f);
        }

        // Configure window spacing and rounding
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 20.0f, 20.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 12.0f);
    }
}
