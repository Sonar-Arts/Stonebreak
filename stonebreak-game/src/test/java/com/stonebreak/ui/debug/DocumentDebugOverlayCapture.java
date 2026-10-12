package com.stonebreak.ui.debug;

import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.stonebreak.ui.DebugOverlay;
import com.stonebreak.ui.fidelity.DocumentStage;
import com.stonebreak.ui.runtime.GameUiDocuments;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The candidate side of the F3 gate (#301): {@code ui/documents/debug_overlay.sbui} on a stage. Not a test class. */
public final class DocumentDebugOverlayCapture implements MigrationGate.Renderer {

    private final SbuiArchive shipped;

    public DocumentDebugOverlayCapture(SbuiArchive shipped) {
        this.shipped = shipped;
    }

    public static DocumentDebugOverlayCapture shipped() throws java.io.IOException {
        return new DocumentDebugOverlayCapture(GameUiDocuments.readScreen(DebugOverlay.DOCUMENT_ID));
    }

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        FidelityCase.Viewport vp = c.viewport();
        try (DocumentStage stage = new DocumentStage(shipped, vp.width(), vp.height(), vp.uiScale())) {
            stage.host.debug().publish(DebugOverlayFixtures.cards(c.variant()));
            stage.settle();
            stage.paint();
            Map<String, float[]> rects = new LinkedHashMap<>();
            List<UiElement> cards = stage.view.instance().qAll(".card");
            for (int i = 0; i < cards.size(); i++) {
                UiRect r = cards.get(i).rect();
                rects.put("card" + i, new float[]{r.x(), r.y(), r.width(), r.height()});
            }
            return new MigrationGate.Capture(stage.raster.capture(), rects);
        } catch (Exception e) {
            throw new IllegalStateException("could not capture the F3 document for " + c.id(), e);
        }
    }
}
