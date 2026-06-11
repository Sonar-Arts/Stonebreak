package com.openmason.main.systems.menus.textureCreator.panels.color;

import com.openmason.main.systems.menus.textureCreator.color.ColorUtils;
import imgui.ImGui;

/**
 * Hit-testing and drag handling for the Skija-painted color picker. Each
 * handler must be called immediately after the corresponding image item is
 * submitted (it operates on ImGui's "last item" state), keeping painting and
 * input strictly separated.
 */
public final class ColorPickerInteraction {

    /**
     * Handle clicks/drags on the SV square item.
     *
     * @return true if the color changed
     */
    public boolean handleSVInput(ColorSelectionState state, float width, float height) {
        if (!isItemEngaged()) {
            return false;
        }
        float localX = ColorUtils.clamp(ImGui.getMousePosX() - ImGui.getItemRectMinX(), 0, width);
        float localY = ColorUtils.clamp(ImGui.getMousePosY() - ImGui.getItemRectMinY(), 0, height);

        state.setSaturationValue(localX / width, 1.0f - (localY / height));
        return true;
    }

    /** Hue change per mouse-wheel notch, in degrees. */
    private static final float HUE_WHEEL_STEP = 6f;
    /** Alpha change per mouse-wheel notch (0-255 scale). */
    private static final int ALPHA_WHEEL_STEP = 8;

    /**
     * Handle clicks/drags and mouse wheel on the hue bar item. Scrolling up
     * moves the marker up the bar (toward hue 0).
     *
     * @return true if the hue changed
     */
    public boolean handleHueInput(ColorSelectionState state, float height) {
        float wheel = hoveredWheel();
        if (wheel != 0) {
            state.setHue(state.getHue() - wheel * HUE_WHEEL_STEP);
            return true;
        }
        if (!isItemEngaged()) {
            return false;
        }
        float localY = ColorUtils.clamp(ImGui.getMousePosY() - ImGui.getItemRectMinY(), 0, height);
        float newHue = (localY / height) * 360.0f;
        if (Math.abs(newHue - state.getHue()) > 0.1f) {
            state.setHue(newHue);
            return true;
        }
        return false;
    }

    /**
     * Handle clicks/drags and mouse wheel on the horizontal alpha bar item.
     * Scrolling up increases alpha.
     *
     * @return true if the alpha changed
     */
    public boolean handleAlphaInput(ColorSelectionState state, float width) {
        float wheel = hoveredWheel();
        if (wheel != 0) {
            state.setAlpha(state.getAlpha() + Math.round(wheel * ALPHA_WHEEL_STEP));
            return true;
        }
        if (!isItemEngaged()) {
            return false;
        }
        float localX = ColorUtils.clamp(ImGui.getMousePosX() - ImGui.getItemRectMinX(), 0, width);
        int newAlpha = Math.round((localX / width) * 255.0f);
        if (newAlpha != state.getAlpha()) {
            state.setAlpha(newAlpha);
            return true;
        }
        return false;
    }

    /** Wheel delta when the last item is hovered, 0 otherwise. */
    private static float hoveredWheel() {
        return ImGui.isItemHovered() ? ImGui.getIO().getMouseWheel() : 0f;
    }

    private static boolean isItemEngaged() {
        return ImGui.isItemActive() && (ImGui.isMouseDragging(0, 0) || ImGui.isMouseClicked(0));
    }
}
