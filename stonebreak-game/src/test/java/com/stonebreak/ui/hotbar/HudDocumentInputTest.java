package com.stonebreak.ui.hotbar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.openmason.engine.cenda.CendaLua;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.stonebreak.ui.runtime.GameUiDocuments;
import com.stonebreak.ui.runtime.screens.DocumentScreen;
import com.stonebreak.ui.runtime.screens.UiLayer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

/**
 * #300 acceptance 4 for the HUD: informational elements never capture gameplay input. Every pointer
 * press, release, move and wheel over the hotbar, the hearts, the bars, the gauge and the tooltip, and
 * every key, falls through the HUD document's router (returns "not consumed"), so the camera, block
 * breaking, the hotbar's number keys and the scroll wheel keep working with the HUD up. The HUD opens on
 * the HUD layer and takes neither the cursor nor the keyboard.
 */
@Tag("regression")
class HudDocumentInputTest {

    private static final int W = 1920;
    private static final int H = 1080;

    @Test
    void noPointerOrKeyEventIsConsumed() throws Exception {
        assumeTrue(CendaLua.isAvailable(), "Cenda Lua host unavailable");
        try (DocumentHudCapture.Stage hud = new DocumentHudCapture.Stage(
                GameUiDocuments.readScreen(DocumentHudCapture.ID), W, H, 1f, HudFixtures.hud("tip"))) {
            var router = hud.view.input();
            for (String target : new String[]{"#hotbar", ".hud-slot", ".heart", "#stamina", "#dodge", "#gauge", "#tip-box"}) {
                UiElement el = hud.q(target);
                assertTrue(el != null && !el.computedStyle().collapsed(), target + " shows");
                UiRect r = el.rect();
                float x = r.x() + r.width() / 2f;
                float y = r.y() + Math.min(r.height() / 2f, 4f);
                for (int button = 0; button < 3; button++) {
                    assertFalse(router.pointerMove(x, y), target + ": move");
                    assertFalse(router.pointerDown(x, y, button, 0), target + ": press " + button);
                    assertFalse(router.pointerUp(x, y, button, 0), target + ": release " + button);
                }
                assertFalse(router.wheel(x, y, 0, 1, 0), target + ": wheel");
                hud.settle();
            }
            for (int key : new int[]{GLFW.GLFW_KEY_1, GLFW.GLFW_KEY_9, GLFW.GLFW_KEY_E, GLFW.GLFW_KEY_ESCAPE,
                    GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_TAB, GLFW.GLFW_KEY_ENTER}) {
                assertFalse(router.keyDown(key, 0, false), "key " + key);
                assertFalse(router.keyUp(key, 0), "key up " + key);
            }
            assertFalse(router.text('e'), "typed text");
        }
    }

    @Test
    void theHudTakesNeitherTheCursorNorTheKeyboard() {
        DocumentScreen.Options hud = DocumentScreen.Options.hud();
        assertEquals(UiLayer.HUD, hud.layer());
        assertFalse(hud.releasesPointer(), "the camera keeps the cursor");
        assertFalse(hud.claimsKeyboard(), "gameplay key polls keep every key");
        assertTrue(hud.perWorld(), "closed with the world");
    }
}
