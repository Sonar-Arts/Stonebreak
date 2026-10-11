package com.stonebreak.ui.startupIntro;

import com.openmason.engine.cenda.CendaLua;
import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.FrozenCaptures;
import com.openmason.engine.ui.fidelity.GeometryRule;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import com.stonebreak.ui.fidelity.DocumentStage;
import com.stonebreak.ui.fidelity.GateAssert;
import com.stonebreak.ui.runtime.GameUiDocuments;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_A;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;

/**
 * The startup intro migration gate (#301): the shipped {@code ui/documents/intro.sbui} against the legacy
 * renderer's frozen captures on every committed moment of a seeded timeline (logo geometry
 * {@code FLOAT_EXACT}, pixels {@code EXACT}), plus its skip input and its {@code intro} clock.
 */
@Tag("regression")
class IntroDocumentGateTest {

    /**
     * Every frozen intro capture: a seeded scene at six moments of its timeline at 1920x1080, and the
     * revealed logo at the other standard viewports (the intro scales itself to fit 1920x1080 and
     * ignores the UI scale).
     */
    static List<FidelityCase> cases() {
        List<FidelityCase> out = new ArrayList<>(FidelityCase.matrix("intro", IntroFixtures.VARIANTS,
            List.of(FidelityCase.STANDARD.get(0))));
        out.addAll(FidelityCase.matrix("intro", List.of("t2-4"), FidelityCase.STANDARD.subList(1, 4)));
        return out;
    }

    static void requireLua() {
        if (!CendaLua.isAvailable()) {
            assumeTrue(Boolean.getBoolean("ui.script.allowMissingNative"), "Cenda Lua host unavailable");
            throw new AssertionError("Cenda Lua host unavailable (build openmason-engine/cenda/build-kernels.sh): "
                + com.openmason.engine.ui.script.UiNativeHealth.check().problems());
        }
    }

    @Test
    void theShippedIntroDocumentPassesTheMigrationGate() throws Exception {
        requireLua();
        MigrationGate.Report r = new MigrationGate(GeometryRule.FLOAT_EXACT, c -> PixelTolerance.EXACT)
            .run("intro", cases(), FrozenCaptures.forTests("intro").replay(), DocumentIntroCapture.shipped());
        GateAssert.passed(r);
    }

    /** Esc, Space, Enter and a left press skip (presses only: repeats and other keys do nothing). */
    @Test
    void theLegacySkipInputsSkip() throws Exception {
        requireLua();
        var sbui = GameUiDocuments.readScreen(SonarArtsIntroScreen.DOCUMENT_ID);
        IntroScene scene = IntroFixtures.scene("t1-2", 1920, 1080);
        try (DocumentStage stage = DocumentIntroCapture.stage(sbui, scene, 1920, 1080, 1f)) {
            String skip = "stonebreak:screen.intro.skip";
            assertEquals(List.of(), stage.key(GLFW_KEY_A));
            assertEquals(List.of(skip), stage.key(GLFW_KEY_ESCAPE));
            assertEquals(List.of(skip), stage.key(GLFW_KEY_SPACE));
            assertEquals(List.of(skip), stage.key(GLFW_KEY_ENTER));
            assertEquals(List.of(skip), stage.clickAt(300, 900), "a left press anywhere");
        }
    }

    /** The document's {@code intro} clock is the scene's own time, whatever the frame steps were. */
    @Test
    void theIntroClockFollowsTheScene() throws Exception {
        requireLua();
        IntroScene scene = IntroFixtures.scene("t2-4", 1920, 1080);
        try (DocumentStage stage = DocumentIntroCapture.stage(GameUiDocuments.readScreen(SonarArtsIntroScreen.DOCUMENT_ID),
                scene, 1920, 1080, 1f)) {
            stage.view.instance().clocks().set(IntroDocument.CLOCK, scene.time());
            assertEquals(scene.time(), stage.view.instance().clocks().now(IntroDocument.CLOCK), 0);
            assertEquals(144 * IntroFixtures.DT, scene.time(), 1e-4);
        }
    }
}
