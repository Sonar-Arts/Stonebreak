package com.stonebreak.ui.modelSetup;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.stonebreak.ui.fidelity.DocumentStage;
import com.stonebreak.world.generation.diffusion.tgmpipe.ModelSetup.Outcome;
import com.stonebreak.world.generation.diffusion.tgmpipe.ModelSetup.Snapshot;
import com.stonebreak.world.generation.diffusion.tgmpipe.ModelSetup.Status;
import com.stonebreak.world.generation.diffusion.tgmpipe.ModelSetup.Step;
import com.stonebreak.world.generation.diffusion.tgmpipe.ModelSetup.StepId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The model setup screen's fidelity cases and the candidate side of its gate (Daedalus): the shipped
 * {@code ui/documents/model_setup.sbui} over a {@link ModelSetupScreen} on the variant's snapshot (the legacy render
 * test's four: {@code running}, {@code failed}, {@code unavailable}, {@code ready}; {@code hover-<n>-<variant>}
 * points at button n). Rects {@code panel}, {@code button0}, {@code button1}. Not a test class.
 */
public final class DocumentModelSetupCapture implements MigrationGate.Renderer {

    public static List<FidelityCase> cases() {
        List<FidelityCase> out = new ArrayList<>(FidelityCase.matrix("model-setup", List.of("running"),
            FidelityCase.STANDARD));
        out.add(new FidelityCase("model-setup", "failed", FidelityCase.STANDARD.get(0)));
        out.add(new FidelityCase("model-setup", "unavailable", FidelityCase.STANDARD.get(1)));
        out.add(new FidelityCase("model-setup", "ready", FidelityCase.STANDARD.get(3)));
        out.add(new FidelityCase("model-setup", "failed", new FidelityCase.Viewport(1280, 800, 1f)));
        out.add(new FidelityCase("model-setup", "hover-0-running", FidelityCase.STANDARD.get(0)));
        out.add(new FidelityCase("model-setup", "hover-1-failed", FidelityCase.STANDARD.get(0)));
        return out;
    }

    /** The snapshot a variant shows. */
    public static Snapshot snapshot(String variant) {
        String v = variant.startsWith("hover-") ? variant.substring("hover-0-".length()) : variant;
        return switch (v) {
            case "running" -> new Snapshot(Outcome.RUNNING, List.of(
                new Step(StepId.GPU, Status.DONE, "NVIDIA RTX PRO 6000 Blackwell Workstation Edition", -1),
                new Step(StepId.UV, Status.DONE, "uv 0.12.20 (downloaded)", -1),
                new Step(StepId.ENVIRONMENT, Status.RUNNING, "1.84 GB of 4.37 GB downloaded (3 of 9 large packages)", 0.42),
                new Step(StepId.VERIFY, Status.PENDING, "", -1),
                new Step(StepId.KERNELS, Status.PENDING, "", -1)), "Downloading torch (846.1MiB)", null, 83_000, true);
            case "failed" -> new Snapshot(Outcome.FAILED, List.of(
                new Step(StepId.GPU, Status.DONE, "NVIDIA GeForce RTX 5090", -1),
                new Step(StepId.UV, Status.DONE, "uv (/usr/bin/uv)", -1),
                new Step(StepId.ENVIRONMENT, Status.FAILED, "uv sync failed (exit 2): network unreachable", -1),
                new Step(StepId.VERIFY, Status.PENDING, "", -1),
                new Step(StepId.KERNELS, Status.PENDING, "", -1)), "",
                "uv sync failed (exit 2): error sending request for url (https://download.pytorch.org/whl/cu128/torch/)",
                83_000, true);
            case "unavailable" -> new Snapshot(Outcome.UNAVAILABLE, List.of(
                new Step(StepId.GPU, Status.FAILED, "No NVIDIA GPU found", -1),
                new Step(StepId.UV, Status.SKIPPED, "", -1),
                new Step(StepId.ENVIRONMENT, Status.SKIPPED, "", -1),
                new Step(StepId.VERIFY, Status.SKIPPED, "", -1),
                new Step(StepId.KERNELS, Status.SKIPPED, "", -1)), "",
                "no NVIDIA graphics card found (the terrain model needs one); standard terrain still works", 83_000, true);
            case "ready" -> new Snapshot(Outcome.READY, List.of(
                new Step(StepId.GPU, Status.DONE, "NVIDIA RTX PRO 6000 Blackwell Workstation Edition", -1),
                new Step(StepId.UV, Status.SKIPPED, "not needed", -1),
                new Step(StepId.ENVIRONMENT, Status.SKIPPED, "already installed", -1),
                new Step(StepId.VERIFY, Status.DONE, "NVIDIA RTX PRO 6000 Blackwell Workstation Edition (cuda:1)", -1),
                new Step(StepId.KERNELS, Status.DONE, "3 kernel groups in 14.5 s", -1)), "", null, 83_000, true);
            default -> throw new IllegalArgumentException("model-setup variant " + variant);
        };
    }

    /** The button a hover variant points at, or -1. */
    public static int hovered(String variant) {
        return variant.startsWith("hover-") ? variant.charAt("hover-".length()) - '0' : -1;
    }

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        FidelityCase.Viewport vp = c.viewport();
        try (DocumentStage stage = ModelSetupDocumentTest.stage(snapshot(c.variant()), new ArrayList<>(), vp.width(),
                vp.height(), vp.uiScale())) {
            int hover = hovered(c.variant());
            List<UiElement> buttons = stage.view.instance().qAll(".ms-button");
            if (hover >= 0) {
                UiRect r = buttons.get(hover).rect();
                stage.view.pointerMove(r.x() + r.width() / 2f, r.y() + r.height() / 2f);
                stage.settle();
            }
            stage.paint();
            Map<String, float[]> rects = new LinkedHashMap<>();
            put(rects, "panel", stage.q("#panel"));
            for (int i = 0; i < buttons.size(); i++) {
                put(rects, "button" + i, buttons.get(i));
            }
            return new MigrationGate.Capture(stage.raster.capture(), rects);
        } catch (Exception e) {
            throw new IllegalStateException("could not capture the model setup document for " + c.id(), e);
        }
    }

    private static void put(Map<String, float[]> out, String name, UiElement el) {
        UiRect r = el.rect();
        out.put(name, new float[]{r.x(), r.y(), r.width(), r.height()});
    }
}
