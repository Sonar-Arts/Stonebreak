package com.stonebreak.ui.modelSetup;

import com.openmason.engine.ui.runtime.UiElement;
import com.stonebreak.ui.chat.ChatDocumentGateTest;
import com.stonebreak.ui.fidelity.DocumentStage;
import com.stonebreak.ui.fidelity.RecordingServices;
import com.stonebreak.ui.runtime.GameUiDocuments;
import com.stonebreak.ui.runtime.providers.SetupIconProvider;
import com.stonebreak.world.generation.diffusion.tgmpipe.ModelSetup.Outcome;
import com.stonebreak.world.generation.diffusion.tgmpipe.ModelSetup.Snapshot;
import com.stonebreak.world.generation.diffusion.tgmpipe.ModelSetup.Status;
import com.stonebreak.world.generation.diffusion.tgmpipe.ModelSetup.Step;
import com.stonebreak.world.generation.diffusion.tgmpipe.ModelSetup.StepId;
import com.stonebreak.network.MultiplayerSession;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The terrain model's launch setup (Daedalus) as its shipped {@code ui/documents/model_setup.sbui} over a
 * {@link ModelSetupScreen} on fixed snapshots: one row per step (a bar only under a measured running step), the
 * outcome's buttons and their rules, Escape continuing to the menu. Not the live setup: nothing is installed.
 */
@Tag("regression")
class ModelSetupDocumentTest {

    /** A setup run as the fixtures show it: GPU done, the CUDA compiler downloading at 40 %, the rest waiting. */
    static Snapshot running() {
        return new Snapshot(Outcome.RUNNING, List.of(
            new Step(StepId.GPU, Status.DONE, "NVIDIA RTX PRO 6000 (sm_120)", -1),
            new Step(StepId.NVRTC, Status.RUNNING, "Downloading the CUDA compiler: 34 / 85 MB", 0.4),
            new Step(StepId.MODEL, Status.PENDING, null, -1),
            new Step(StepId.VERIFY, Status.PENDING, null, -1),
            new Step(StepId.KERNELS, Status.PENDING, null, -1)),
            "Downloading the CUDA compiler: 34 / 85 MB", null, 83_000, true);
    }

    static Snapshot failed() {
        return new Snapshot(Outcome.FAILED, List.of(
            new Step(StepId.GPU, Status.DONE, "NVIDIA RTX PRO 6000 (sm_120)", -1),
            new Step(StepId.NVRTC, Status.DONE, "NVRTC 12.9.86", -1),
            new Step(StepId.MODEL, Status.FAILED, "terrain model checkpoints/exp2-r3 has Git LFS pointer files", -1),
            new Step(StepId.VERIFY, Status.PENDING, null, -1),
            new Step(StepId.KERNELS, Status.PENDING, null, -1)),
            "", "The model release has Git LFS pointer files: run 'git lfs pull'.", 12_000, true);
    }

    /** The document on a stage over {@code snap}; {@code events} records menu / retry. */
    static DocumentStage stage(Snapshot snap, List<String> events, int w, int h, float scale) throws Exception {
        ModelSetupScreen screen = new ModelSetupScreen(() -> snap, () -> events.add("menu"), () -> events.add("retry"));
        RecordingServices services = new RecordingServices() {
            @Override
            public ModelSetupScreen modelSetup() {
                return screen;
            }

            @Override
            public int[] screenSize() {
                return new int[]{w, h};
            }
        };
        DocumentStage stage = new DocumentStage(GameUiDocuments.readScreen(ModelSetupScreen.DOCUMENT_ID), w, h, scale,
            services, MultiplayerSession.Mode.MENU, Map.of(SetupIconProvider.ID, new SetupIconProvider()));
        stage.host.modelSetup().poll();
        stage.settle();
        return stage;
    }

    /**
     * Every outcome renders and looks different (ported from the legacy renderer's test, same snapshots); the
     * frames go to {@code target/model-setup-*.png} for eyeballing.
     */
    @Test
    void everyStateRendersAndLooksDifferent() throws Exception {
        ChatDocumentGateTest.requireLua();
        // The gate's four snapshots (DocumentModelSetupCapture#snapshot), one per outcome.
        var running = render(DocumentModelSetupCapture.snapshot("running"), "running");
        var failed = render(DocumentModelSetupCapture.snapshot("failed"), "failed");
        var unavailable = render(DocumentModelSetupCapture.snapshot("unavailable"), "unavailable");
        var ready = render(DocumentModelSetupCapture.snapshot("ready"), "ready");
        assertTrue(differ(running, failed) > 1000);
        assertTrue(differ(failed, unavailable) > 1000);
        assertTrue(differ(unavailable, ready) > 1000);
    }

    private static com.openmason.engine.ui.fidelity.FidelityImage render(Snapshot snap, String name) throws Exception {
        try (DocumentStage stage = stage(snap, new ArrayList<>(), 1280, 800, 1f)) {
            stage.paint();
            var image = stage.raster.capture();
            image.write(java.nio.file.Path.of("target", "model-setup-" + name + ".png"));
            return image;
        }
    }

    private static int differ(com.openmason.engine.ui.fidelity.FidelityImage a,
                              com.openmason.engine.ui.fidelity.FidelityImage b) {
        int n = 0;
        for (int y = 0; y < 800; y += 2) {
            for (int x = 0; x < 1280; x += 2) {
                if (a.pixel(x, y) != b.pixel(x, y)) n++;
            }
        }
        return n;
    }

    @Test
    void aRunningSetupShowsItsStepsAndContinuesInTheBackground() throws Exception {
        ChatDocumentGateTest.requireLua();
        List<String> events = new ArrayList<>();
        try (DocumentStage stage = stage(running(), events, 1920, 1080, 1f)) {
            List<UiElement> steps = stage.view.instance().qAll(".ms-step");
            assertEquals(5, steps.size());
            List<UiElement> bars = stage.view.instance().qAll(".ms-bar");
            for (int i = 0; i < bars.size(); i++) {
                assertEquals(i == 1, !bars.get(i).isCollapsed(), "only the measured running step (the NVRTC download) has a bar: " + i);
            }
            List<UiElement> buttons = stage.view.instance().qAll(".ms-button");
            assertEquals(1, buttons.size());
            assertEquals(990f, buttons.get(0).rect().y(), 1e-3, "90 above the window's foot");
            var r = buttons.get(0).rect();
            stage.clickAt(r.x() + r.width() / 2f, r.y() + r.height() / 2f);
            assertEquals(List.of("menu"), events, "Continue in background");
            stage.key(256);
            assertEquals(List.of("menu", "menu"), events, "Escape continues too");
        }
    }

    @Test
    void aFailedSetupOffersRetryAndContinue() throws Exception {
        ChatDocumentGateTest.requireLua();
        List<String> events = new ArrayList<>();
        try (DocumentStage stage = stage(failed(), events, 1280, 720, 1f)) {
            List<UiElement> buttons = stage.view.instance().qAll(".ms-button");
            assertEquals(2, buttons.size());
            assertTrue(buttons.get(0).rect().x() < buttons.get(1).rect().x());
            var retry = buttons.get(0).rect();
            stage.clickAt(retry.x() + retry.width() / 2f, retry.y() + retry.height() / 2f);
            assertEquals(List.of("retry"), events);
            var cont = buttons.get(1).rect();
            stage.clickAt(cont.x() + cont.width() / 2f, cont.y() + cont.height() / 2f);
            assertEquals(List.of("retry", "menu"), events);
            assertFalse(stage.view.instance().qAll(".ms-bar").stream().anyMatch(b -> !b.isCollapsed()),
                "no running step, no bar");
        }
    }
}
