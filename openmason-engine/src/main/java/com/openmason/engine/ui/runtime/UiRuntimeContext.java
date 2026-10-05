package com.openmason.engine.ui.runtime;

import com.openmason.engine.format.omui.UiStyleSheet;
import com.openmason.engine.ui.runtime.widget.WidgetRegistry;

import java.util.List;
import java.util.Objects;

/**
 * What a host (game or editor preview) supplies to instantiate documents. One context is
 * shared by the preview and the game so validation, theming and measuring agree.
 *
 * @param widgets  registered widget types (built-ins plus host providers)
 * @param source   dependency lookup for components and shared sheets
 * @param theme    theme sheets, the lowest cascade layer, applied to the whole tree
 * @param measurer intrinsic sizes of measured widgets
 * @param pixelGrid Yoga's point-scale factor: {@code 1} (the {@code flex-1} default) snaps
 *                  every edge to whole device pixels; {@code 0} keeps fractional geometry,
 *                  which is what today's float-math screens (pause) draw
 */
public record UiRuntimeContext(WidgetRegistry widgets, UiDocumentSource source, List<UiStyleSheet> theme,
                               ContentMeasurer measurer, float pixelGrid) {

    public static final float DEVICE_PIXEL_GRID = 1f;
    public static final float NO_PIXEL_GRID = 0f;

    public UiRuntimeContext {
        Objects.requireNonNull(widgets, "widgets");
        source = source == null ? UiDocumentSource.EMPTY : source;
        theme = List.copyOf(theme == null ? List.of() : theme);
        measurer = measurer == null ? ContentMeasurer.NONE : measurer;
        if (!(pixelGrid >= 0)) {
            throw new IllegalArgumentException("pixelGrid must be >= 0");
        }
    }

    /** Built-in widgets, no dependencies, no theme. */
    public static UiRuntimeContext basic() {
        return new UiRuntimeContext(WidgetRegistry.withBuiltIns(), UiDocumentSource.EMPTY, List.of(),
            ContentMeasurer.NONE, DEVICE_PIXEL_GRID);
    }

    public UiRuntimeContext withSource(UiDocumentSource s) {
        return new UiRuntimeContext(widgets, s, theme, measurer, pixelGrid);
    }

    public UiRuntimeContext withTheme(List<UiStyleSheet> t) {
        return new UiRuntimeContext(widgets, source, t, measurer, pixelGrid);
    }

    public UiRuntimeContext withMeasurer(ContentMeasurer m) {
        return new UiRuntimeContext(widgets, source, theme, m, pixelGrid);
    }

    public UiRuntimeContext withPixelGrid(float grid) {
        return new UiRuntimeContext(widgets, source, theme, measurer, grid);
    }
}
