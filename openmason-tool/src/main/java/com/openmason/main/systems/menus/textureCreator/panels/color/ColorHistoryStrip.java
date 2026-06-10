package com.openmason.main.systems.menus.textureCreator.panels.color;

import imgui.ImGui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Recent-colors model + grid renderer for the color panel's History tab.
 * Most recent first, duplicates collapse to the front, capped at
 * {@link #MAX_HISTORY_SIZE}.
 */
public final class ColorHistoryStrip {

    private static final int MAX_HISTORY_SIZE = 10;
    private static final float SWATCH_SIZE = 40f;
    private static final float PADDING = 4f;
    private static final int COLUMNS = 5;

    private final List<Integer> colors = new ArrayList<>();

    /** Add a color to the front of the history (deduplicated, capped). */
    public void add(int color) {
        colors.remove((Integer) color);
        colors.add(0, color);
        while (colors.size() > MAX_HISTORY_SIZE) {
            colors.remove(colors.size() - 1);
        }
    }

    public List<Integer> getColors() {
        return new ArrayList<>(colors);
    }

    public void setColors(List<Integer> history) {
        colors.clear();
        if (history != null) {
            colors.addAll(history);
            while (colors.size() > MAX_HISTORY_SIZE) {
                colors.remove(colors.size() - 1);
            }
        }
    }

    /**
     * Render the history grid. Clicking a swatch selects it and moves it to
     * the front of the history.
     */
    public void render(IntConsumer onColorSelected) {
        ImGui.text("Recent Colors");
        ImGui.separator();
        ImGui.spacing();

        if (colors.isEmpty()) {
            ImGui.textDisabled("No colors in history yet");
            ImGui.text("Colors will appear here as you use them");
            return;
        }

        for (int i = 0; i < colors.size(); i++) {
            int historyColor = colors.get(i);
            SwatchRenderer.render("##history_" + i, historyColor, SWATCH_SIZE, () -> {
                onColorSelected.accept(historyColor);
                add(historyColor); // move to front
            });

            if ((i + 1) % COLUMNS != 0 && i < colors.size() - 1) {
                ImGui.sameLine();
                ImGui.dummy(PADDING, 0);
                ImGui.sameLine();
            }
        }
    }
}
