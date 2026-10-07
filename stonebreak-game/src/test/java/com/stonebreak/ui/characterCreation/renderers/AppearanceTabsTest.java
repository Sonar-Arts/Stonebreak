package com.stonebreak.ui.characterCreation.renderers;

import com.stonebreak.config.Settings;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.openmason.engine.ui.masonry.MasonryUI;
import com.stonebreak.ui.characterCreation.CharacterCreationActionHandler;
import com.stonebreak.ui.characterCreation.CharacterCreationLayout.Rect;
import io.github.humbleui.skija.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real raster rendering followed by clicks on the independent appearance lists. */
class AppearanceTabsTest {
    @Test
    void looksSelectsHairAndClothingSelectsHats() throws Exception {
        Settings settings = mock(Settings.class);
        when(settings.getSelectedHat()).thenReturn("TOP_HAT");
        when(settings.getSelectedHair()).thenReturn("MALE_HAIR_1");
        try (var mocked = mockStatic(Settings.class);
             var input = getClass().getResourceAsStream("/fonts/Minecraft.ttf");
             var data = Data.makeFromBytes(input.readAllBytes());
             var typeface = FontMgr.getDefault().makeFromData(data);
             var bitmap = new Bitmap()) {
            mocked.when(Settings::getInstance).thenReturn(settings);
            bitmap.allocPixels(ImageInfo.makeN32Premul(600, 400));
            try (var canvas = new Canvas(bitmap)) {
                var ui = new MasonryUI(new SkijaUIBackend() {
                    @Override public Typeface getMinecraftTypeface() { return typeface; }
                    @Override public Canvas getCanvas() { return canvas; }
                    @Override public boolean isAvailable() { return true; }
                });
                try {
                    var looks = new LooksTabRenderer();
                    var clothing = new ClothingTabRenderer();
                    var actions = mock(CharacterCreationActionHandler.class);
                    var content = new Rect(0, 0, 600, 400);
                    looks.render(canvas, ui, content, -1, -1);
                    clothing.render(canvas, ui, content, -1, -1);
                    // Rows: None, first option, second option. Right-aligned Equip buttons.
                    assertFalse(looks.handleClick(534, 121, actions), "equipped hair is inert");
                    assertFalse(clothing.handleClick(534, 121, actions), "equipped hat is inert");
                    assertTrue(looks.handleClick(534, 179, actions));
                    verify(actions).onSelectHair("MALE_HAIR_2");
                    assertTrue(clothing.handleClick(534, 179, actions), "Clothing's third row is a hat");
                    verify(actions).onSelectHat("PIRATE_HAT");
                    // Accessories stack under the three hair rows: heading at 222, Gold Earring is row 2.
                    assertTrue(looks.handleClick(534, 343, actions));
                    verify(actions).onSelectAccessory("GOLD_EARRING");
                    assertTrue(clothing.handleClick(534, 63, actions));
                    verify(actions).onSelectHat("NONE");
                    assertTrue(looks.handleClick(534, 63, actions));
                    verify(actions).onSelectHair("NONE");
                    when(settings.getSelectedHat()).thenReturn("NONE");
                    clothing.render(canvas, ui, content, -1, -1);
                    assertTrue(clothing.handleClick(534, 121, actions));
                    verify(actions).onSelectHat("TOP_HAT");
                    verifyNoMoreInteractions(actions);
                } finally {
                    ui.fonts().dispose();
                }
            }
        }
    }
}
