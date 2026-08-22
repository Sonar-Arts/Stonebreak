package com.stonebreak.rendering.UI.components.hotbar;

import com.stonebreak.player.Player;

/**
 * One class-specific resource gauge drawn to the right of the hotbar. The coordinator
 * only invokes a gauge while {@link #classId()} is the player's selected class.
 */
public interface ClassGauge {

    /** Width of every class gauge panel, in pixels. */
    int PANEL_WIDTH = 190;

    /** The {@code CLASS_ID} this gauge belongs to. */
    String classId();

    /** Draws the gauge into {@code panel}, whose cursor starts at the hotbar's top edge. */
    void draw(GaugePanel panel, Player player);
}
