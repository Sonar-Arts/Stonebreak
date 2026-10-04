package com.stonebreak.ui.characterCreation.renderers;

import com.stonebreak.player.PlayerLooks;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.characterCreation.CharacterCreationActionHandler;
import com.stonebreak.ui.characterCreation.CharacterCreationLayout;
import io.github.humbleui.skija.Canvas;

/** Hair and accessory choices in the Looks tab, stacked one list above the other. */
public final class LooksTabRenderer {
    private static final float SECTION_GAP = 12f;

    private final CosmeticOptionsRenderer hair = new CosmeticOptionsRenderer(
            "Hair", PlayerLooks.HAIR_OPTIONS, PlayerLooks::getSelectedHairId,
            CharacterCreationActionHandler::onSelectHair);
    private final CosmeticOptionsRenderer accessories = new CosmeticOptionsRenderer(
            "Accessories", PlayerLooks.ACCESSORY_OPTIONS, PlayerLooks::getSelectedAccessoryId,
            CharacterCreationActionHandler::onSelectAccessory);

    public void render(Canvas canvas, MasonryUI ui, CharacterCreationLayout.Rect content,
                       float mx, float my) {
        hair.render(canvas, ui, content, mx, my);
        accessories.render(canvas, ui, below(content), mx, my);
    }

    public boolean handleClick(float mx, float my, CharacterCreationActionHandler actions) {
        return hair.handleClick(mx, my, actions) || accessories.handleClick(mx, my, actions);
    }

    private CharacterCreationLayout.Rect below(CharacterCreationLayout.Rect content) {
        float offset = hair.height() + SECTION_GAP;
        return new CharacterCreationLayout.Rect(content.x(), content.y() + offset,
                content.width(), Math.max(0f, content.height() - offset));
    }
}
