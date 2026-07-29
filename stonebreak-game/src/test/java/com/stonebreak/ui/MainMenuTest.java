package com.stonebreak.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.stonebreak.config.Settings;
import com.stonebreak.ui.support.Resolutions;

/**
 * Guards the main menu's button column geometry.
 *
 * <p>The regression this exists to catch: {@code handleMouseMove} and {@code handleMouseClick} used
 * to recompute the button rectangles independently, each with its own copy of the same four-branch
 * if/else chain. Editing one and not the other made hover and click disagree — the cursor
 * highlights "Settings" while the click fires "Quit". Both now route through
 * {@link MainMenu#buttonIndexAt}, and these tests pin the geometry that single source produces.
 *
 * <p>{@code buttonIndexAt} is static and package-private precisely so it can be exercised without
 * constructing a {@code MainMenu}, whose constructor needs a live Skija backend.
 *
 * <p><b>UI scale is read, never written.</b> {@code Settings} is a process-global singleton shared
 * across the whole surefire JVM, so every assertion here holds at whatever scale the developer's
 * {@code settings.json} supplies.
 */
class MainMenuTest {

    private static final int BUTTON_COUNT = 4;

    /** Center of button {@code index}, mirroring the production column math. */
    private static float[] buttonCenter(int index, int windowWidth, int windowHeight) {
        float s = Settings.getInstance().getUiScale();
        float bw = 400f * s;
        float bh = 40f * s;
        float sp = 50f * s;
        float x = windowWidth / 2.0f - bw / 2f;
        float y = windowHeight / 2.0f - 20f * s + sp * index;
        return new float[] { x + bw / 2f, y + bh / 2f };
    }

    @Test
    void eachButtonCenterResolvesToItsOwnIndex() {
        for (Resolutions.Size size : Resolutions.ALL) {
            for (int i = 0; i < BUTTON_COUNT; i++) {
                float[] c = buttonCenter(i, size.width(), size.height());
                assertEquals(i, MainMenu.buttonIndexAt(c[0], c[1], size.width(), size.height()),
                    size + ": center of button " + i + " must resolve back to button " + i);
            }
        }
    }

    @Test
    void pointsFarFromTheColumnResolveToNoButton() {
        for (Resolutions.Size size : Resolutions.ALL) {
            assertEquals(-1, MainMenu.buttonIndexAt(-500f, -500f, size.width(), size.height()),
                size + ": a point off the top-left claimed a button");
            assertEquals(-1, MainMenu.buttonIndexAt(size.width() + 500f, size.height() + 500f,
                    size.width(), size.height()),
                size + ": a point past the bottom-right claimed a button");
        }
    }

    @Test
    void aPointLeftOrRightOfTheColumnResolvesToNoButton() {
        for (Resolutions.Size size : Resolutions.ALL) {
            float s = Settings.getInstance().getUiScale();
            float bw = 400f * s;
            float left = size.width() / 2.0f - bw / 2f;
            float[] c = buttonCenter(0, size.width(), size.height());

            assertEquals(-1, MainMenu.buttonIndexAt(left - 1f, c[1], size.width(), size.height()),
                size + ": a point just left of the column claimed a button");
            assertEquals(-1, MainMenu.buttonIndexAt(left + bw + 1f, c[1], size.width(), size.height()),
                size + ": a point just right of the column claimed a button");
        }
    }

    @Test
    void theGapBetweenTwoButtonsBelongsToNeither() {
        // Spacing (50) exceeds button height (40), so there is a real 10px dead band between rows.
        // If someone changes spacing to match the height, this test tells them the gap is gone.
        for (Resolutions.Size size : Resolutions.ALL) {
            float s = Settings.getInstance().getUiScale();
            float bh = 40f * s;
            float sp = 50f * s;
            float top = size.height() / 2.0f - 20f * s;
            float[] c = buttonCenter(0, size.width(), size.height());

            float gapY = top + bh + (sp - bh) / 2f; // midpoint of the dead band under button 0
            assertEquals(-1, MainMenu.buttonIndexAt(c[0], gapY, size.width(), size.height()),
                size + ": the dead band between buttons 0 and 1 must claim no button");
        }
    }
}
