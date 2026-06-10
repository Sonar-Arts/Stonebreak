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

    /**
     * Handle clicks/drags on the hue bar item.
     *
     * @return true if the hue changed
     */
    public boolean handleHueInput(ColorSelectionState state, float height) {
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

    private static boolean isItemEngaged() {
        return ImGui.isItemActive() && (ImGui.isMouseDragging(0, 0) || ImGui.isMouseClicked(0));
    }
}
