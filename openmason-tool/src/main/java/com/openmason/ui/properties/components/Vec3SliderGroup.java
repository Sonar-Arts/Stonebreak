package com.openmason.ui.properties.components;

import imgui.ImGui;
import imgui.type.ImFloat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Reusable component for rendering three related sliders (X, Y, Z).
 * Eliminates code duplication for position, rotation, and scale controls.
 * Follows DRY and KISS principles.
 */
public class Vec3SliderGroup {

    private static final Logger logger = LoggerFactory.getLogger(Vec3SliderGroup.class);

    private final String label;
    private final ImFloat x;
    private final ImFloat y;
    private final ImFloat z;
    private final float min;
    private final float max;
    private final String format;
    private final Consumer<Integer> onChanged;
    private final float sliderWidth;

    /**
     * Create a Vec3 slider group with default 200f slider width.
     *
     * @param label The label for this group (e.g., "Position", "Rotation", "Scale")
     * @param x The X value
     * @param y The Y value
     * @param z The Z value
     * @param min Minimum slider value
     * @param max Maximum slider value
     * @param format Printf-style format string (e.g., "%.2f", "%.1f°")
     * @param onChanged Callback invoked when any slider changes (receives axis index 0=X, 1=Y, 2=Z)
     */
    public Vec3SliderGroup(String label, ImFloat x, ImFloat y, ImFloat z,
                          float min, float max, String format,
                          Consumer<Integer> onChanged) {
        this(label, x, y, z, min, max, format, onChanged, 200f);
    }

    /**
     * Create a Vec3 slider group with custom slider width.
     *
     * @param label The label for this group (e.g., "Position", "Rotation", "Scale")
     * @param x The X value
     * @param y The Y value
     * @param z The Z value
     * @param min Minimum slider value
     * @param max Maximum slider value
     * @param format Printf-style format string (e.g., "%.2f", "%.1f°")
     * @param onChanged Callback invoked when any slider changes (receives axis index 0=X, 1=Y, 2=Z)
     * @param sliderWidth Width of each slider in pixels (default 200f to match ColorPanel)
     */
    public Vec3SliderGroup(String label, ImFloat x, ImFloat y, ImFloat z,
                          float min, float max, String format,
                          Consumer<Integer> onChanged, float sliderWidth) {
        this.label = label;
        this.x = x;
        this.y = y;
        this.z = z;
        this.min = min;
        this.max = max;
        this.format = format;
        this.onChanged = onChanged;
        this.sliderWidth = sliderWidth;
    }

    /**
     * Render the slider group.
     *
     * @return true if any slider was changed
     */
    public boolean render() {
        boolean changed = false;

        ImGui.text(label + ":");

        // Set fixed slider width to prevent infinite stretching
        ImGui.pushItemWidth(sliderWidth);

        if (ImGui.sliderFloat("X##" + label + "X", x.getData(), min, max, format)) {
            if (onChanged != null) {
                onChanged.accept(0); // X axis
            }
            changed = true;
        }

        if (ImGui.sliderFloat("Y##" + label + "Y", y.getData(), min, max, format)) {
            if (onChanged != null) {
                onChanged.accept(1); // Y axis
            }
            changed = true;
        }

        if (ImGui.sliderFloat("Z##" + label + "Z", z.getData(), min, max, format)) {
            if (onChanged != null) {
                onChanged.accept(2); // Z axis
            }
            changed = true;
        }

        // Restore default width
        ImGui.popItemWidth();

        return changed;
    }

    /**
     * Render with uniform scaling support.
     * In uniform mode, changing one slider affects all others proportionally.
     *
     * @param uniformMode Whether uniform scaling is enabled
     * @param onUniformScale Callback for applying uniform scaling (receives changed axis and new value)
     * @return true if any slider was changed
     */
    public boolean renderWithUniformScale(boolean uniformMode,
                                          java.util.function.BiConsumer<Integer, Float> onUniformScale) {
        boolean changed = false;

        ImGui.text(label + ":");

        // Set fixed slider width to prevent infinite stretching
        ImGui.pushItemWidth(sliderWidth);

        // X slider
        if (ImGui.sliderFloat("X##" + label + "X", x.getData(), min, max, format)) {
            if (uniformMode && onUniformScale != null) {
                onUniformScale.accept(0, x.get());
            } else if (onChanged != null) {
                onChanged.accept(0);
            }
            changed = true;
        }

        // Y slider
        if (ImGui.sliderFloat("Y##" + label + "Y", y.getData(), min, max, format)) {
            if (uniformMode && onUniformScale != null) {
                onUniformScale.accept(1, y.get());
            } else if (onChanged != null) {
                onChanged.accept(1);
            }
            changed = true;
        }

        // Z slider
        if (ImGui.sliderFloat("Z##" + label + "Z", z.getData(), min, max, format)) {
            if (uniformMode && onUniformScale != null) {
                onUniformScale.accept(2, z.get());
            } else if (onChanged != null) {
                onChanged.accept(2);
            }
            changed = true;
        }

        // Restore default width
        ImGui.popItemWidth();

        return changed;
    }

    // Static factory methods for common configurations

    /**
     * Create a position slider group (-10 to 10, format "%.2f").
     */
    public static Vec3SliderGroup createPosition(ImFloat x, ImFloat y, ImFloat z,
                                                 Consumer<Integer> onChanged) {
        return new Vec3SliderGroup("Position", x, y, z, -10.0f, 10.0f, "%.2f", onChanged);
    }

    /**
     * Create a rotation slider group (-180 to 180, format "%.1f°").
     */
    public static Vec3SliderGroup createRotation(ImFloat x, ImFloat y, ImFloat z,
                                                 Consumer<Integer> onChanged) {
        return new Vec3SliderGroup("Rotation", x, y, z, -180.0f, 180.0f, "%.1f°", onChanged);
    }

    /**
     * Create a scale slider group (custom min/max, format "%.2f").
     */
    public static Vec3SliderGroup createScale(ImFloat x, ImFloat y, ImFloat z,
                                              float min, float max,
                                              Consumer<Integer> onChanged) {
        return new Vec3SliderGroup("Scale", x, y, z, min, max, "%.2f", onChanged);
    }
}
