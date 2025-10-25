package com.stonebreak.ui.worldSelect.renderers;

import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.ui.worldSelect.config.WorldSelectConfig;
import com.stonebreak.ui.worldSelect.managers.WorldStateManager;
import com.stonebreak.ui.worldSelect.managers.WorldDiscoveryManager;
import com.stonebreak.world.save.model.WorldData;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.system.MemoryStack;

import java.util.List;

import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Renders the world list and related UI elements for the WorldSelectScreen.
 * Handles world item rendering, scrollbars, and empty state display.
 */
public class WorldListRenderer {

    private final UIRenderer uiRenderer;
    private final WorldStateManager stateManager;
    private final WorldDiscoveryManager discoveryManager;

    public WorldListRenderer(UIRenderer uiRenderer, WorldStateManager stateManager, WorldDiscoveryManager discoveryManager) {
        this.uiRenderer = uiRenderer;
        this.stateManager = stateManager;
        this.discoveryManager = discoveryManager;
    }

    /**
     * Renders the complete world list UI.
     */
    public void renderWorldList(int windowWidth, int windowHeight) {
        float centerX = windowWidth / 2.0f;
        float centerY = windowHeight / 2.0f;

        // Draw screen title
        renderTitle(centerX, centerY);

        // Draw world list or empty state
        if (stateManager.hasWorlds()) {
            renderWorldItems(centerX, centerY);
            renderScrollbar(centerX, centerY);
        } else {
            renderEmptyState(centerX, centerY);
        }

        // Draw action buttons
        renderActionButtons(centerX, centerY);
    }

    /**
     * Renders the screen title.
     */
    private void renderTitle(float centerX, float centerY) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();

            nvgFontSize(vg, WorldSelectConfig.TITLE_FONT_SIZE);
            nvgFontFace(vg, "minecraft");
            nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
            nvgFillColor(vg, uiRenderer.nvgRGBA(
                WorldSelectConfig.TEXT_COLOR_R,
                WorldSelectConfig.TEXT_COLOR_G,
                WorldSelectConfig.TEXT_COLOR_B,
                WorldSelectConfig.TEXT_COLOR_A,
                NVGColor.malloc(stack)
            ));

            float titleY = centerY + WorldSelectConfig.SCREEN_TITLE_Y_OFFSET;
            nvgText(vg, centerX, titleY, "Select World");
        }
    }

    /**
     * Renders individual world items in the list.
     */
    private void renderWorldItems(float centerX, float centerY) {
        long vg = uiRenderer.getVG();
        List<String> worldList = stateManager.getWorldList();

        float listX = centerX - WorldSelectConfig.LIST_WIDTH / 2.0f;
        float listY = centerY - WorldSelectConfig.LIST_HEIGHT / 2.0f;

        int startIndex = stateManager.getVisibleStartIndex();
        int endIndex = stateManager.getVisibleEndIndex();

        try (MemoryStack stack = stackPush()) {
            // Draw list background
            nvgBeginPath(vg);
            nvgRoundedRect(vg, listX - 5, listY - 5, WorldSelectConfig.LIST_WIDTH + 10, WorldSelectConfig.LIST_HEIGHT + 10, 8);
            nvgFillColor(vg, uiRenderer.nvgRGBA(
                WorldSelectConfig.BG_COLOR_R,
                WorldSelectConfig.BG_COLOR_G,
                WorldSelectConfig.BG_COLOR_B,
                WorldSelectConfig.BG_COLOR_A,
                NVGColor.malloc(stack)
            ));
            nvgFill(vg);

            // Draw world items
            for (int i = startIndex; i < endIndex; i++) {
                String worldName = worldList.get(i);
                float itemY = listY + (i - startIndex) * WorldSelectConfig.ITEM_HEIGHT;

                renderWorldItem(worldName, i, listX, itemY, WorldSelectConfig.LIST_WIDTH, WorldSelectConfig.ITEM_HEIGHT, stack);
            }
        }
    }

    /**
     * Renders a single world item.
     */
    private void renderWorldItem(String worldName, int index, float x, float y, float width, float height, MemoryStack stack) {
        long vg = uiRenderer.getVG();
        boolean isSelected = index == stateManager.getSelectedIndex();
        boolean isHovered = index == stateManager.getHoveredIndex();

        // Choose background color based on state
        int bgR, bgG, bgB, bgA;
        if (isSelected) {
            bgR = WorldSelectConfig.ITEM_SELECTED_COLOR_R;
            bgG = WorldSelectConfig.ITEM_SELECTED_COLOR_G;
            bgB = WorldSelectConfig.ITEM_SELECTED_COLOR_B;
            bgA = WorldSelectConfig.ITEM_SELECTED_COLOR_A;
        } else if (isHovered) {
            bgR = WorldSelectConfig.ITEM_HOVERED_COLOR_R;
            bgG = WorldSelectConfig.ITEM_HOVERED_COLOR_G;
            bgB = WorldSelectConfig.ITEM_HOVERED_COLOR_B;
            bgA = WorldSelectConfig.ITEM_HOVERED_COLOR_A;
        } else {
            bgR = WorldSelectConfig.ITEM_BG_COLOR_R;
            bgG = WorldSelectConfig.ITEM_BG_COLOR_G;
            bgB = WorldSelectConfig.ITEM_BG_COLOR_B;
            bgA = WorldSelectConfig.ITEM_BG_COLOR_A;
        }

        // Draw item background
        nvgBeginPath(vg);
        nvgRoundedRect(vg, x, y, width, height, WorldSelectConfig.ITEM_CORNER_RADIUS);
        nvgFillColor(vg, uiRenderer.nvgRGBA(bgR, bgG, bgB, bgA, NVGColor.malloc(stack)));
        nvgFill(vg);

        // Draw world name
        nvgFontSize(vg, WorldSelectConfig.ITEM_FONT_SIZE);
        nvgFontFace(vg, "minecraft");
        nvgTextAlign(vg, NVG_ALIGN_LEFT | NVG_ALIGN_MIDDLE);
        nvgFillColor(vg, uiRenderer.nvgRGBA(
            WorldSelectConfig.TEXT_COLOR_R,
            WorldSelectConfig.TEXT_COLOR_G,
            WorldSelectConfig.TEXT_COLOR_B,
            WorldSelectConfig.TEXT_COLOR_A,
            NVGColor.malloc(stack)
        ));

        float textX = x + WorldSelectConfig.PADDING_MEDIUM;
        float textY = y + height / 2.0f;
        nvgText(vg, textX, textY - 5, worldName);

        // Draw world info if enabled
        if (WorldSelectConfig.SHOW_WORLD_INFO) {
            renderWorldInfo(worldName, textX, textY + 5, stack);
        }
    }

    /**
     * Renders additional world information (last played, etc.).
     */
    private void renderWorldInfo(String worldName, float x, float y, MemoryStack stack) {
        WorldData worldData = discoveryManager.getWorldData(worldName);
        if (worldData == null) return;

        long vg = uiRenderer.getVG();

        // Format world info
        StringBuilder info = new StringBuilder();

        if (worldData.getLastPlayed() != null) {
            long lastPlayedMillis = worldData.getLastPlayed().toEpochSecond(java.time.ZoneOffset.UTC) * 1000;
            info.append("Last played: ").append(formatTimestamp(lastPlayedMillis));
        }

        if (worldData.getSeed() != 0) {
            if (info.length() > 0) info.append(" | ");
            info.append("Seed: ").append(worldData.getSeed());
        }

        if (info.length() > 0) {
            nvgFontSize(vg, WorldSelectConfig.WORLD_INFO_FONT_SIZE);
            nvgFontFace(vg, "minecraft");
            nvgTextAlign(vg, NVG_ALIGN_LEFT | NVG_ALIGN_MIDDLE);
            nvgFillColor(vg, uiRenderer.nvgRGBA(
                WorldSelectConfig.TEXT_SECONDARY_COLOR_R,
                WorldSelectConfig.TEXT_SECONDARY_COLOR_G,
                WorldSelectConfig.TEXT_SECONDARY_COLOR_B,
                WorldSelectConfig.TEXT_SECONDARY_COLOR_A,
                NVGColor.malloc(stack)
            ));

            nvgText(vg, x, y, info.toString());
        }
    }

    /**
     * Renders the scrollbar if needed.
     */
    private void renderScrollbar(float centerX, float centerY) {
        List<String> worldList = stateManager.getWorldList();
        if (worldList.size() <= WorldSelectConfig.ITEMS_PER_PAGE) {
            return; // No scrollbar needed
        }

        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();

            float listX = centerX - WorldSelectConfig.LIST_WIDTH / 2.0f;
            float listY = centerY - WorldSelectConfig.LIST_HEIGHT / 2.0f;

            float scrollbarX = listX + WorldSelectConfig.LIST_WIDTH + WorldSelectConfig.SCROLL_BAR_MARGIN;
            float scrollbarHeight = WorldSelectConfig.LIST_HEIGHT;

            // Draw scrollbar track
            nvgBeginPath(vg);
            nvgRect(vg, scrollbarX, listY, WorldSelectConfig.SCROLL_BAR_WIDTH, scrollbarHeight);
            nvgFillColor(vg, uiRenderer.nvgRGBA(64, 64, 64, 180, NVGColor.malloc(stack)));
            nvgFill(vg);

            // Calculate scrollbar thumb position and size
            int totalItems = worldList.size();
            int visibleItems = WorldSelectConfig.ITEMS_PER_PAGE;
            float thumbHeight = (scrollbarHeight * visibleItems) / totalItems;
            float thumbY = listY + (scrollbarHeight - thumbHeight) * stateManager.getScrollOffset() / (totalItems - visibleItems);

            // Draw scrollbar thumb
            nvgBeginPath(vg);
            nvgRect(vg, scrollbarX, thumbY, WorldSelectConfig.SCROLL_BAR_WIDTH, thumbHeight);
            nvgFillColor(vg, uiRenderer.nvgRGBA(120, 120, 120, 255, NVGColor.malloc(stack)));
            nvgFill(vg);
        }
    }

    /**
     * Renders the empty state when no worlds are found.
     */
    private void renderEmptyState(float centerX, float centerY) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();

            nvgFontSize(vg, WorldSelectConfig.EMPTY_TEXT_FONT_SIZE);
            nvgFontFace(vg, "minecraft");
            nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
            nvgFillColor(vg, uiRenderer.nvgRGBA(
                WorldSelectConfig.TEXT_SECONDARY_COLOR_R,
                WorldSelectConfig.TEXT_SECONDARY_COLOR_G,
                WorldSelectConfig.TEXT_SECONDARY_COLOR_B,
                WorldSelectConfig.TEXT_SECONDARY_COLOR_A,
                NVGColor.malloc(stack)
            ));

            nvgText(vg, centerX, centerY, WorldSelectConfig.EMPTY_LIST_TEXT);
        }
    }

    /**
     * Renders action buttons (Create New World, Back).
     */
    private void renderActionButtons(float centerX, float centerY) {
        float buttonY = centerY + WorldSelectConfig.LIST_HEIGHT / 2.0f + WorldSelectConfig.BUTTON_MARGIN;
        float buttonX = centerX - WorldSelectConfig.BUTTON_WIDTH / 2.0f;

        // Create New World button
        uiRenderer.drawButton(
            "Create New World",
            buttonX, buttonY,
            WorldSelectConfig.BUTTON_WIDTH, WorldSelectConfig.BUTTON_HEIGHT,
            false // TODO: Add hover state tracking
        );

        // Back button
        buttonY += WorldSelectConfig.BUTTON_HEIGHT + WorldSelectConfig.BUTTON_SPACING;
        uiRenderer.drawButton(
            "Back",
            buttonX, buttonY,
            WorldSelectConfig.BUTTON_WIDTH, WorldSelectConfig.BUTTON_HEIGHT,
            false // TODO: Add hover state tracking
        );
    }

    /**
     * Formats a timestamp for display purposes.
     */
    private String formatTimestamp(long timestamp) {
        if (timestamp <= 0) return "Unknown";

        java.time.LocalDateTime dateTime = java.time.LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(timestamp),
            java.time.ZoneId.systemDefault()
        );
        java.time.LocalDateTime now = java.time.LocalDateTime.now();

        if (dateTime.toLocalDate().equals(now.toLocalDate())) {
            return String.format("Today at %d:%02d", dateTime.getHour(), dateTime.getMinute());
        }

        return String.format("%d/%d/%d", dateTime.getMonthValue(), dateTime.getDayOfMonth(), dateTime.getYear());
    }
}