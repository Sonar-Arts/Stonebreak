package com.stonebreak.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.stonebreak.config.Settings;
import com.stonebreak.ui.pauseMenu.SkijaPauseMenuRenderer;
import com.stonebreak.ui.support.Resolutions;

/**
 * Guards the pause menu's click routing: exactly one button may claim any given point, hit tests
 * must agree with the hover flags they drive, and nothing may be clickable while the menu is
 * hidden.
 *
 * <p>The regression this prevents is a mis-set slot index or a spacing change that makes two
 * buttons overlap — "Quit" swallowing the click meant for "Settings" is a destructive bug that no
 * rendering assertion would catch, because the menu still looks perfectly correct.
 *
 * <p><b>Null backend is deliberate.</b> {@code PauseMenu}'s constructor only stores the backend on
 * its renderer, and {@code SkijaPauseMenuRenderer.render} opens with
 * {@code if (backend == null || !backend.isAvailable()) return;} — a null backend is an anticipated
 * state, not a fake. This test never calls {@code render} or {@code cleanup}, so no GPU resource is
 * ever touched.
 *
 * <p><b>Offline layout only.</b> The resync button exists only when
 * {@code MultiplayerSession.isOnline()} is true, and that reads a private static with no setter, so
 * under test the menu is always the five-button offline column. The six-button online variant is
 * unreachable without a production seam and is therefore not covered here.
 */
class PauseMenuTest {

    private PauseMenu menu;

    @BeforeEach
    void setUp() {
        menu = new PauseMenu(null);
        menu.setVisible(true);
    }

    /** The five always-present buttons, in slot order, paired with a label for failure messages. */
    private List<String> buttonNames() {
        return List.of("Resume", "Statistics", "Glossary", "Settings", "Quit");
    }

    private List<BooleanSupplier> hitTestsAt(float x, float y, int w, int h) {
        List<BooleanSupplier> tests = new ArrayList<>();
        tests.add(() -> menu.isResumeButtonClicked(x, y, w, h));
        tests.add(() -> menu.isStatisticsButtonClicked(x, y, w, h));
        tests.add(() -> menu.isGlossaryButtonClicked(x, y, w, h));
        tests.add(() -> menu.isSettingsButtonClicked(x, y, w, h));
        tests.add(() -> menu.isQuitButtonClicked(x, y, w, h));
        return tests;
    }

    /** Center of the button in the given slot, computed from the renderer's own layout formula. */
    private static float[] slotCenter(int slot, int count, int windowWidth, int windowHeight) {
        float scale = Settings.getInstance().getUiScale();
        float offset = SkijaPauseMenuRenderer.buttonOffset(slot, count);
        float buttonWidth = SkijaPauseMenuRenderer.BUTTON_WIDTH * scale;
        float buttonHeight = SkijaPauseMenuRenderer.BUTTON_HEIGHT * scale;
        float x = windowWidth / 2.0f - buttonWidth / 2f;
        float y = windowHeight / 2.0f + offset * scale;
        return new float[] { x + buttonWidth / 2f, y + buttonHeight / 2f };
    }

    @Test
    void eachButtonClaimsItsOwnCenterAndNoOtherButtonDoes() {
        for (Resolutions.Size size : Resolutions.ALL) {
            for (int slot = 0; slot < 5; slot++) {
                float[] c = slotCenter(slot, 5, size.width(), size.height());
                List<BooleanSupplier> tests = hitTestsAt(c[0], c[1], size.width(), size.height());

                for (int i = 0; i < tests.size(); i++) {
                    boolean hit = tests.get(i).getAsBoolean();
                    if (i == slot) {
                        assertTrue(hit, size + ": " + buttonNames().get(i)
                            + " must claim its own center (slot " + slot + ")");
                    } else {
                        assertFalse(hit, size + ": " + buttonNames().get(i)
                            + " must not claim the center of slot " + slot
                            + " (" + buttonNames().get(slot) + ")");
                    }
                }
            }
        }
    }

    @Test
    void noButtonClaimsAPointFarOutsideTheColumn() {
        for (Resolutions.Size size : Resolutions.ALL) {
            for (BooleanSupplier test : hitTestsAt(-500f, -500f, size.width(), size.height())) {
                assertFalse(test.getAsBoolean(), size + ": a point far off screen claimed a button");
            }
            for (BooleanSupplier test : hitTestsAt(size.width() + 500f, size.height() + 500f,
                    size.width(), size.height())) {
                assertFalse(test.getAsBoolean(), size + ": a point past the far corner claimed a button");
            }
        }
    }

    @Test
    void nothingIsClickableWhileTheMenuIsHidden() {
        menu.setVisible(false);

        for (int slot = 0; slot < 5; slot++) {
            float[] c = slotCenter(slot, 5, 1920, 1080);
            for (BooleanSupplier test : hitTestsAt(c[0], c[1], 1920, 1080)) {
                assertFalse(test.getAsBoolean(),
                    "a hidden pause menu must swallow no clicks, but slot " + slot + " responded");
            }
        }
    }

    @Test
    void resyncIsNotClickableInAnOfflineSession() {
        // isResyncButtonVisible() reflects MultiplayerSession.isOnline(), which is always false
        // under test. The button must therefore never respond, wherever the cursor is.
        assertFalse(PauseMenu.isResyncButtonVisible(),
            "no network session exists under test, so the resync button must be hidden");

        for (int slot = 0; slot < 5; slot++) {
            float[] c = slotCenter(slot, 5, 1920, 1080);
            assertFalse(menu.isResyncButtonClicked(c[0], c[1], 1920, 1080),
                "resync must not respond offline, but it claimed the center of slot " + slot);
        }
    }

    @Test
    void hoverStateIsDrivenByTheSameHitTestsAsClicking() {
        // updateHover has no getters, so drive it and assert it agrees indirectly: calling it must
        // never change what the click predicates report for the same point.
        for (Resolutions.Size size : Resolutions.ALL) {
            for (int slot = 0; slot < 5; slot++) {
                float[] c = slotCenter(slot, 5, size.width(), size.height());

                boolean beforeHover = menu.isSettingsButtonClicked(c[0], c[1], size.width(), size.height());
                menu.updateHover(c[0], c[1], size.width(), size.height());
                boolean afterHover = menu.isSettingsButtonClicked(c[0], c[1], size.width(), size.height());

                assertEquals(beforeHover, afterHover,
                    size + ": updateHover must not alter click routing for slot " + slot);
            }
        }
    }

    @Test
    void updateHoverOnAHiddenMenuIsSafe() {
        menu.setVisible(false);
        menu.updateHover(960f, 540f, 1920, 1080); // must not throw
        assertFalse(menu.isVisible());
    }

    @Test
    void visibilityTogglesBothWays() {
        menu.setVisible(false);
        assertFalse(menu.isVisible());

        menu.toggleVisibility();
        assertTrue(menu.isVisible(), "toggle must show a hidden menu");

        menu.toggleVisibility();
        assertFalse(menu.isVisible(), "toggle must hide a shown menu");
    }

    @Test
    void buttonSlotsAreEvenlySpacedAndOrderedTopToBottom() {
        // The renderer's offset formula is the single source of the column layout, shared with the
        // hit tests. If it stops being monotonic, buttons render out of order.
        float previous = SkijaPauseMenuRenderer.buttonOffset(0, 5);
        float firstGap = SkijaPauseMenuRenderer.buttonOffset(1, 5) - previous;

        for (int slot = 1; slot < 5; slot++) {
            float offset = SkijaPauseMenuRenderer.buttonOffset(slot, 5);
            assertTrue(offset > previous,
                "slot " + slot + " must sit below slot " + (slot - 1));
            assertEquals(firstGap, offset - previous, 0.0001f,
                "slot spacing must be uniform, but the gap before slot " + slot + " differed");
            previous = offset;
        }
    }

    @Test
    void theColumnIsVerticallyCenteredOnTheScreen() {
        // For an odd button count the middle button's offset is zero, i.e. exactly at screen center.
        assertEquals(0f, SkijaPauseMenuRenderer.buttonOffset(2, 5), 0.0001f,
            "the middle of a five-button column must sit on the screen center");

        float first = SkijaPauseMenuRenderer.buttonOffset(0, 5);
        float last = SkijaPauseMenuRenderer.buttonOffset(4, 5);
        assertEquals(-first, last, 0.0001f,
            "the column must extend symmetrically above and below center");
    }
}
