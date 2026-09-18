package com.stonebreak.ui.characterCreation.renderers;

import com.stonebreak.player.PlayerLooks;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.characterCreation.CharacterCreationActionHandler;
import com.stonebreak.ui.characterCreation.CharacterCreationLayout;
import io.github.humbleui.skija.Canvas;

/** Hats choices in the Clothing tab. */
public final class ClothingTabRenderer {
    private final CosmeticOptionsRenderer options = new CosmeticOptionsRenderer(
            "Hats", PlayerLooks.HAT_OPTIONS, PlayerLooks::getSelectedHatId,
            CharacterCreationActionHandler::onSelectHat);

    public void render(Canvas canvas, MasonryUI ui, CharacterCreationLayout.Rect content,
                       float mx, float my) {
        options.render(canvas, ui, content, mx, my);
    }

    public boolean handleClick(float mx, float my, CharacterCreationActionHandler actions) {
        return options.handleClick(mx, my, actions);
    }
}
