package com.stonebreak.ui.characterCreation.renderers;

import com.stonebreak.player.PlayerLooks;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.characterCreation.CharacterCreationActionHandler;
import com.stonebreak.ui.characterCreation.CharacterCreationLayout;
import io.github.humbleui.skija.Canvas;

/** Hair choices in the Looks tab. */
public final class LooksTabRenderer {
    private final CosmeticOptionsRenderer options = new CosmeticOptionsRenderer(
            "Hair", PlayerLooks.HAIR_OPTIONS, PlayerLooks::getSelectedHairId,
            CharacterCreationActionHandler::onSelectHair);

    public void render(Canvas canvas, MasonryUI ui, CharacterCreationLayout.Rect content,
                       float mx, float my) {
        options.render(canvas, ui, content, mx, my);
    }

    public boolean handleClick(float mx, float my, CharacterCreationActionHandler actions) {
        return options.handleClick(mx, my, actions);
    }
}
