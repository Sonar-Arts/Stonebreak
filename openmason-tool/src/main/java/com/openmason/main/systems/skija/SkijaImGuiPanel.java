package com.openmason.main.systems.skija;

import imgui.ImGui;
import io.github.humbleui.skija.Canvas;

import java.util.function.Consumer;

/**
 * Composes a Skia-painted offscreen surface into the current ImGui window as
 * an interactive item. Owns one {@link SkijaOffscreenSurface}; callers
 * provide a painter that draws in logical pixel coordinates with (0,0) at
 * the top-left.
 *
 * The image is overlaid with an invisible button: plain {@code ImGui.image}
 * items are hoverable but never <em>active</em>, so click-drag interaction
 * ({@code isItemActive}) would not work and clicks would fall through to the
 * window beneath. After {@link #draw} returns, standard ImGui item queries
 * ({@code isItemHovered}, {@code isItemActive}, ...) refer to that button,
 * and {@link #getItemRelativeMouseX()}/{@link #getItemRelativeMouseY()} give
 * mouse coordinates in the painter's coordinate space for hit-testing.
 */
public final class SkijaImGuiPanel implements AutoCloseable {

    private static final java.util.concurrent.atomic.AtomicInteger NEXT_ID =
            new java.util.concurrent.atomic.AtomicInteger();

    private final SkijaOffscreenSurface surface;
    private final String buttonId;

    private float lastItemMinX;
    private float lastItemMinY;

    public SkijaImGuiPanel(SkijaContext context) {
        this.surface = new SkijaOffscreenSurface(context);
        this.buttonId = "##skija_panel_" + NEXT_ID.incrementAndGet();
    }

    /**
     * Paint via {@code painter} and submit the result as an ImGui image item
     * of the given size. Sizes below 1px are skipped.
     */
    public void draw(float width, float height, Consumer<Canvas> painter) {
        int w = Math.round(width);
        int h = Math.round(height);
        if (w < 1 || h < 1) {
            return;
        }

        surface.ensureSize(w, h);
        Canvas canvas = surface.beginPaint();
        try {
            painter.accept(canvas);
        } finally {
            surface.endPaint();
        }

        lastItemMinX = ImGui.getCursorScreenPosX();
        lastItemMinY = ImGui.getCursorScreenPosY();

        // Allocation is rounded up; map UVs to the logical sub-rectangle.
        float u1 = (float) w / surface.getAllocatedWidth();
        float v1 = (float) h / surface.getAllocatedHeight();
        ImGui.image(surface.getTextureId(), width, height, 0.0f, 0.0f, u1, v1);

        // Overlay an invisible button so the panel is a true interactive
        // item (capturing clicks/drags instead of the window beneath)
        ImGui.setCursorScreenPos(lastItemMinX, lastItemMinY);
        ImGui.invisibleButton(buttonId, width, height);
    }

    /** Mouse X relative to the last drawn image, in painter coordinates. */
    public float getItemRelativeMouseX() {
        return ImGui.getMousePosX() - lastItemMinX;
    }

    /** Mouse Y relative to the last drawn image, in painter coordinates. */
    public float getItemRelativeMouseY() {
        return ImGui.getMousePosY() - lastItemMinY;
    }

    @Override
    public void close() {
        surface.close();
    }
}
