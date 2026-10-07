package com.stonebreak.ui.fidelity;

import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.stonebreak.ui.DeathMenu;
import com.stonebreak.ui.runtime.GameUiDocuments;

import java.util.List;
import java.util.Map;

/**
 * The candidate side of the death menu's fidelity gate (#299): the shipped
 * {@code ui/documents/death.sbui} on a {@link DocumentStage}, with the variants of
 * {@link LegacyDeathCapture}. Not a test class.
 */
public final class DocumentDeathCapture implements MigrationGate.Renderer {

    private final SbuiArchive shipped;

    public DocumentDeathCapture(SbuiArchive shipped) {
        this.shipped = shipped;
    }

    public static DocumentDeathCapture shipped() throws java.io.IOException {
        return new DocumentDeathCapture(GameUiDocuments.readScreen(DeathMenu.DOCUMENT_ID));
    }

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        FidelityCase.Viewport vp = c.viewport();
        try (DocumentStage stage = new DocumentStage(shipped, vp.width(), vp.height(), vp.uiScale())) {
            if (c.variant().endsWith("-hover-respawn")) {
                stage.hover("respawn");
            }
            stage.paint();
            Map<String, float[]> rects = stage.rects(List.of("respawn"));
            Map<String, float[]> hits = stage.hits(rects);
            Map<String, String> actions = stage.actions(rects);
            return new MigrationGate.Capture(stage.raster.capture(), rects, hits, actions);
        } catch (Exception e) {
            throw new IllegalStateException("could not capture the death document for " + c.id(), e);
        }
    }
}
