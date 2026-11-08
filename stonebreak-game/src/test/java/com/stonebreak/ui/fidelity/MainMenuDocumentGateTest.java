package com.stonebreak.ui.fidelity;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.FrozenCaptures;
import com.openmason.engine.ui.fidelity.GeometryRule;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import com.openmason.engine.ui.masonry.MKeys;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The main menu's gate (live-world redesign, contract {@code stonebreak:screen.main-menu} 2): the shipped
 * {@code main_menu} document against its reviewed baselines at {@code FLOAT_EXACT} geometry and
 * {@code EXACT} pixels — revealed over the live world, its "Loading main menu" wait, over the static
 * backdrop, and with an entry hovered — with the same hit regions and actions.
 *
 * <p>The redesign replaced the legacy menu, so there is no legacy reference: its baselines were
 * recorded once from the reviewed document ({@link #refreezeTheBaselines}, opt-in) and are a
 * regression lock from then on. An intended visual change re-records them in a reviewed commit.
 */
@Tag("regression")
class MainMenuDocumentGateTest {

    static List<FidelityCase> cases() {
        List<FidelityCase> out = new ArrayList<>(FidelityCase.matrix("main-menu", List.of("live"), FidelityCase.STANDARD));
        FidelityCase.Viewport hd = FidelityCase.STANDARD.get(0);
        for (String v : List.of("loading", "static", "live-hover-singleplayer", "live-hover-quit")) {
            out.add(new FidelityCase("main-menu", v, hd));
        }
        return out;
    }

    @Test
    void theShippedMainMenuMatchesItsBaselines() throws Exception {
        DeathDocumentGateTest.requireLua();
        MigrationGate.Report r = new MigrationGate(GeometryRule.FLOAT_EXACT, c -> PixelTolerance.EXACT)
            .run("main-menu", cases(), FrozenCaptures.forTests("main-menu").replay(), new DocumentMainMenuCapture());
        GateAssert.passed(r);
    }

    @Test
    void theKeyboardWalksTheEntries() throws Exception {
        DeathDocumentGateTest.requireLua();
        try (DocumentStage stage = DocumentMainMenuCapture.stage(new FidelityCase(
                "main-menu", "live", FidelityCase.STANDARD.get(0)))) {
            stage.key(MKeys.KEY_TAB);
            stage.key(MKeys.KEY_DOWN);
            assertEquals(List.of("stonebreak:screen.main-menu.multiplayer"), stage.key(MKeys.KEY_ENTER),
                "Tab focuses the first entry, Down the next");
            assertEquals(List.of("stonebreak:screen.main-menu.quit"), stage.click("quit"));
        }
    }

    /**
     * Opt-in ({@code -Dstonebreak.ui.mainmenu.refreeze=true}): re-records every baseline and the geometry
     * from the shipped document. Only for a reviewed, intended change of the menu's look.
     */
    @Test
    void refreezeTheBaselines() {
        assumeTrue(Boolean.getBoolean("stonebreak.ui.mainmenu.refreeze"), "opt-in re-freeze");
        DeathDocumentGateTest.requireLua();
        FrozenCaptures.forTests("main-menu").record(cases(), new DocumentMainMenuCapture());
    }
}
