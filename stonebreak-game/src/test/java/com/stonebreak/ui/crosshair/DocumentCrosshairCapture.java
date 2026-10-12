package com.stonebreak.ui.crosshair;

import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.ui.fidelity.DocumentStage;
import com.stonebreak.ui.fidelity.RecordingServices;
import com.stonebreak.ui.runtime.GameUiDocuments;


/**
 * The candidate side of the crosshair's fidelity gate (#301): the shipped {@code ui/documents/crosshair.sbui}
 * on a {@link DocumentStage}, its {@code stonebreak:crosshair} data read from a configuration of
 * {@link CrosshairFixtures} (the applied configuration the game publishes). Not a test class.
 */
public final class DocumentCrosshairCapture implements MigrationGate.Renderer {

    private final SbuiArchive shipped;

    public DocumentCrosshairCapture(SbuiArchive shipped) {
        this.shipped = shipped;
    }

    public static DocumentCrosshairCapture shipped() throws java.io.IOException {
        return new DocumentCrosshairCapture(GameUiDocuments.readScreen(Crosshair.DOCUMENT_ID));
    }

    /** Services publishing {@code crosshair} as the applied configuration. */
    public static RecordingServices services(CrosshairSettings crosshair) {
        return new RecordingServices() {
            @Override
            public CrosshairSettings crosshair() {
                return crosshair;
            }
        };
    }

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        FidelityCase.Viewport vp = c.viewport();
        try (DocumentStage stage = new DocumentStage(shipped, vp.width(), vp.height(), vp.uiScale(),
                services(CrosshairFixtures.crosshair(c.variant())), MultiplayerSession.Mode.SINGLEPLAYER)) {
            stage.paint();
            return new MigrationGate.Capture(stage.raster.capture(),
                stage.rects(java.util.List.of("arm1", "arm2", "arm3", "arm4", "ring", "dot")));
        } catch (Exception e) {
            throw new IllegalStateException("could not capture the crosshair document for " + c.id(), e);
        }
    }
}
