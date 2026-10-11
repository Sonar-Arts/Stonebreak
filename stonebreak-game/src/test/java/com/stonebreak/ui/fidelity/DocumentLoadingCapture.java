package com.stonebreak.ui.fidelity;

import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.ui.LoadingScreen;
import com.stonebreak.ui.runtime.GameUiDocuments;
import com.stonebreak.ui.runtime.contracts.LoadingRecord;

import java.util.List;

/**
 * The candidate side of the loading screen's fidelity gate (#299): the shipped
 * {@code ui/documents/loading.sbui} on a {@link DocumentStage} whose host publishes the progress of
 * a {@link #screen} in the case's state. Variants are the stage reported last: {@code first} (shown,
 * no report yet), {@code caves} ("Generating Caves"), {@code meshing} ("Meshing Chunk", a full bar)
 * and {@code density} ("Calculating Terrain Density", an alias that maps to the second stage). Not a
 * test class.
 */
public final class DocumentLoadingCapture implements MigrationGate.Renderer {

    /** The stage reported for a variant, or null for none. */
    public static String stage(String variant) {
        return switch (variant) {
            case "first" -> null;
            case "caves" -> "Generating Caves";
            case "meshing" -> "Meshing Chunk";
            case "density" -> "Calculating Terrain Density";
            default -> throw new IllegalArgumentException("loading variant: first|caves|meshing|density, not " + variant);
        };
    }

    /** A loading screen showing the variant's progress (never enters LOADING: no game here). */
    public static LoadingScreen screen(String variant) {
        LoadingScreen screen = new LoadingScreen();
        screen.showProgress();
        String stage = stage(variant);
        if (stage != null) {
            screen.updateProgress(stage);
        }
        return screen;
    }

    private final SbuiArchive shipped;

    public DocumentLoadingCapture(SbuiArchive shipped) {
        this.shipped = shipped;
    }

    public static DocumentLoadingCapture shipped() throws java.io.IOException {
        return new DocumentLoadingCapture(GameUiDocuments.readScreen(LoadingScreen.DOCUMENT_ID));
    }

    static DocumentStage stage(SbuiArchive sbui, FidelityCase.Viewport vp, String variant) throws Exception {
        RecordingServices services = new RecordingServices();
        services.loading = LoadingRecord.of(screen(variant));
        return new DocumentStage(sbui, vp.width(), vp.height(), vp.uiScale(), services,
            MultiplayerSession.Mode.MENU);
    }

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        try (DocumentStage stage = stage(shipped, c.viewport(), c.variant())) {
            stage.paint();
            return new MigrationGate.Capture(stage.raster.capture(), stage.rects(List.of("logo", "bar")));
        } catch (Exception e) {
            throw new IllegalStateException("could not capture the loading document for " + c.id(), e);
        }
    }
}
