package com.stonebreak.ui.fidelity;

import com.openmason.engine.ui.fidelity.GeometryRule;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * The main menu migration gate (#299): the shipped {@code main_menu} document against the legacy
 * renderer's committed cases at {@code FLOAT_EXACT} geometry and {@code EXACT} pixels, idle and through
 * the title easter egg (the scene, the logo's motion and drop shadow, the splash and the screen shake),
 * with the same hit regions and actions.
 */
@Tag("regression")
class MainMenuDocumentGateTest {

    /**
     * Frames in motion: the legacy menu moves the title and shakes the screen with canvas transforms,
     * the document with layout offsets and its element transform, so float rounding lands a nearest
     * texel or a glyph's sub-pixel position one pixel apart. A pixel also matches a baseline pixel one
     * pixel away; a few drifting anti-aliased pixels are allowed. On the tilted title a whole texel row
     * can flip (a thin line along the logo), so a clump may reach 96 pixels: still far below a missing
     * label or icon.
     */
    static final PixelTolerance MOTION = new PixelTolerance(2, 0.002, 1,
        List.of(new PixelTolerance.Region(0, 0, 4096, 4096)), 96, 96);

    @Test
    void theShippedMainMenuPassesTheMigrationGate() throws Exception {
        DeathDocumentGateTest.requireLua();
        MigrationGate.Report r = new MigrationGate(GeometryRule.FLOAT_EXACT,
                c -> LegacyMainMenuCapture.still(c.variant()) ? PixelTolerance.EXACT : MOTION)
            .run("main-menu", LegacyMainMenuBaselineTest.cases(), new LegacyMainMenuCapture(), new DocumentMainMenuCapture());
        GateAssert.passed(r);
    }

    @Test
    void theKeyboardWalksTheButtonsAndTheTitleDrivesTheEasterEgg() throws Exception {
        DeathDocumentGateTest.requireLua();
        com.stonebreak.ui.MainMenu[] menu = new com.stonebreak.ui.MainMenu[1];
        try (DocumentStage stage = DocumentMainMenuCapture.stage(new com.openmason.engine.ui.fidelity.FidelityCase(
                "main-menu", "idle", com.openmason.engine.ui.fidelity.FidelityCase.STANDARD.get(0)), menu)) {
            stage.key(com.openmason.engine.ui.masonry.MKeys.KEY_TAB);
            stage.key(com.openmason.engine.ui.masonry.MKeys.KEY_DOWN);
            org.junit.jupiter.api.Assertions.assertEquals(List.of("stonebreak:screen.main-menu.multiplayer"),
                stage.key(com.openmason.engine.ui.masonry.MKeys.KEY_ENTER), "Tab focuses the first button, Down the next");
            org.junit.jupiter.api.Assertions.assertEquals(List.of("stonebreak:screen.main-menu.title"), stage.click("logo"));
        } finally {
            if (menu[0] != null) {
                menu[0].dispose();
            }
        }
    }
}
