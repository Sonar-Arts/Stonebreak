package com.openmason.main.systems.uiEditor.view;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Region drags after a texture shrank or a slice stopped fitting must repair, never throw (#294 review). */
class SpritesPanelClampTest {

    @Test
    void invertedBoundsResolveToTheLowerBound() {
        // a sprite at x=60 on a texture cropped to 32: dragging its right edge clamps to [61, 32]
        assertEquals(61, SpritesPanel.clamp(70, 61, 32));
        // a slice whose insets exceed the width: [0, -2] resolves to 0
        assertEquals(0, SpritesPanel.clamp(3, 0, -2));
        assertEquals(5, SpritesPanel.clamp(5, 0, 10));
        assertEquals(10, SpritesPanel.clamp(12, 0, 10));
    }
}
