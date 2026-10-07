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
 * a {@link LegacyLoadingCapture#screen} in the same state. Not a test class.
 */
public final class DocumentLoadingCapture implements MigrationGate.Renderer {

    private final SbuiArchive shipped;

    public DocumentLoadingCapture(SbuiArchive shipped) {
        this.shipped = shipped;
    }

    public static DocumentLoadingCapture shipped() throws java.io.IOException {
        return new DocumentLoadingCapture(GameUiDocuments.readScreen(LoadingScreen.DOCUMENT_ID));
    }

    static DocumentStage stage(SbuiArchive sbui, FidelityCase.Viewport vp, String variant) throws Exception {
        RecordingServices services = new RecordingServices();
        services.loading = LoadingRecord.of(LegacyLoadingCapture.screen(null, variant));
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
