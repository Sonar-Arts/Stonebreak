package com.stonebreak.ui.fidelity;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.GeometryRule;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import com.openmason.engine.ui.masonry.MKeys;
import com.openmason.engine.ui.runtime.UiRect;
import com.stonebreak.config.Settings;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The settings migration gate (#299): the shipped document against the legacy screen's committed
 * cases at {@code FLOAT_EXACT} geometry and {@code EXACT} pixels, with the same hit regions and
 * presses. Plus the interactions the pixels cannot show, run against the real menu: a dropdown
 * opened and chosen from, closed by a press elsewhere, a slider dragged, the wheel, the keys.
 */
@Tag("regression")
class SettingsDocumentGateTest {

    @Test
    void theSettingsScreenPassesTheMigrationGate() throws Exception {
        DeathDocumentGateTest.requireLua();
        MigrationGate.Report r = new MigrationGate(GeometryRule.FLOAT_EXACT, c -> PixelTolerance.EXACT)
            .run("settings", LegacySettingsBaselineTest.cases(), new LegacySettingsCapture(),
                new DocumentSettingsCapture());
        GateAssert.passed(r);
    }

    private static FidelityCase at(String variant) {
        return new FidelityCase("settings", variant, FidelityCase.STANDARD.get(0));
    }

    @Test
    void aDropdownOpensChoosesAndClosesOnAPressElsewhere() throws Exception {
        DeathDocumentGateTest.requireLua();
        try (DocumentSettingsCapture.Stage stage = DocumentSettingsCapture.stage(at("general"))) {
            stage.services.settingsLive.add("press");
            assertEquals(List.of("stonebreak:screen.settings.press row0"), stage.click("row0-dropdown"));
            assertFalse(stage.q("#row0-list").isCollapsed(), "the list shows");
            assertEquals(List.of("stonebreak:screen.settings.press item2"), stage.click("row0-item2"));
            assertEquals(2, Settings.getInstance().getCurrentResolutionIndex());
            assertTrue(stage.q("#row0-list").isCollapsed(), "choosing closes it");

            stage.click("row0-dropdown");
            assertEquals(List.of("stonebreak:screen.settings.press none"), stage.clickAt(4, 4));
            assertTrue(stage.q("#row0-list").isCollapsed(), "a press elsewhere closes it");
        }
    }

    @Test
    void aSliderFollowsTheDrag() throws Exception {
        DeathDocumentGateTest.requireLua();
        try (DocumentSettingsCapture.Stage stage = DocumentSettingsCapture.stage(at("audio"))) {
            stage.services.settingsLive.addAll(List.of("press", "drag", "release"));
            UiRect r = stage.rect("row0-slider");
            float y = r.y() + r.height() / 2f;
            stage.view.pointerMove(r.x() + r.width() * 0.25f, y);
            stage.view.pointerDown(r.x() + r.width() * 0.25f, y);
            stage.settle();
            assertEquals(0.25f, Settings.getInstance().getMasterVolume(), 1e-3f);
            stage.view.pointerMove(r.x() + r.width() * 0.75f, y + 200);
            stage.settle();
            assertEquals(0.75f, Settings.getInstance().getMasterVolume(), 1e-3f, "the drag follows off the track");
            stage.view.pointerUp(r.x() + r.width() * 0.75f, y + 200);
            stage.settle();
            assertFalse(stage.services.settingsMenu.dragging());
            String shown = com.openmason.engine.ui.runtime.UiTexts.label(stage.q("#row0-slider .house-slider-label"));
            assertTrue(shown.contains("75%"), "the label follows: " + shown);
        }
    }

    @Test
    void theWheelScrollsTheViewport() throws Exception {
        DeathDocumentGateTest.requireLua();
        try (DocumentSettingsCapture.Stage stage = DocumentSettingsCapture.stage(at("quality"))) {
            stage.services.settingsLive.add("wheel");
            UiRect v = stage.rect("viewport");
            stage.view.input().wheel(v.x() + v.width() / 2f, v.y() + v.height() / 2f, 0, -2, 0);
            for (int i = 0; i < 20; i++) {
                stage.settle(); // the easing steps once a frame
            }
            assertEquals(60f * stage.scale, stage.services.settingsMenu.getScrollContainer().getScrollOffset(), 1f);
        }
    }

    @Test
    void keysStepTheMenuLikeThePoll() throws Exception {
        DeathDocumentGateTest.requireLua();
        try (DocumentSettingsCapture.Stage stage = DocumentSettingsCapture.stage(at("general"))) {
            stage.services.settingsLive.add("key");
            var st = stage.services.settingsMenu.getStateManager();
            assertEquals(List.of("stonebreak:screen.settings.key " + MKeys.KEY_RIGHT), stage.key(MKeys.KEY_RIGHT));
            assertEquals("QUALITY", st.getSelectedCategory().name());
            stage.key(MKeys.KEY_LEFT);
            assertEquals("GENERAL", st.getSelectedCategory().name());
            stage.services.settingsLive.clear(); // Escape would leave the screen
            assertEquals(List.of("stonebreak:screen.settings.key " + MKeys.KEY_ESCAPE), stage.key(MKeys.KEY_ESCAPE));
        }
    }
}
