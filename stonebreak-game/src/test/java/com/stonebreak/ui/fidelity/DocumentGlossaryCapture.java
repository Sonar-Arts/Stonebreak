package com.stonebreak.ui.fidelity;

import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.ui.glossaryScreen.GlossaryScreen;
import com.stonebreak.ui.runtime.GameUiDocuments;
import com.stonebreak.ui.runtime.providers.EntityPreviewProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The candidate side of the glossary's fidelity gate (#299): the shipped
 * {@code ui/documents/glossary.sbui} on a {@link DocumentStage} whose host serves a real
 * {@link GlossaryScreen} (selection and pinned data as in {@link LegacyGlossaryCapture}). A click's
 * effect is read back from that screen (selection, shown variant) or the recorded Back. Not a test class.
 */
public final class DocumentGlossaryCapture implements MigrationGate.Renderer {

    static final List<String> PARTS = List.of("row0", "row1", "row2", "row3", "left", "right", "back");

    private final SbuiArchive shipped;

    public DocumentGlossaryCapture(SbuiArchive shipped) {
        this.shipped = shipped;
    }

    public static DocumentGlossaryCapture shipped() throws java.io.IOException {
        return new DocumentGlossaryCapture(GameUiDocuments.readScreen(GlossaryScreen.DOCUMENT_ID));
    }

    static DocumentStage stage(SbuiArchive sbui, FidelityCase.Viewport vp, String variant) throws Exception {
        RecordingServices services = new RecordingServices();
        DocumentStage[] holder = new DocumentStage[1];
        // the screen needs a backend for its (unused) legacy renderer: the stage's raster one
        LegacyUiRaster probe = new LegacyUiRaster(vp.width(), vp.height(), vp.uiScale());
        try {
            services.glossary = LegacyGlossaryCapture.screen(probe.backend(), variant);
        } finally {
            probe.close();
        }
        holder[0] = new DocumentStage(sbui, vp.width(), vp.height(), vp.uiScale(), services,
            MultiplayerSession.Mode.SINGLEPLAYER, Map.of(EntityPreviewProvider.ID, DocumentStage.NO_GL));
        return holder[0];
    }

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        try (DocumentStage stage = stage(shipped, c.viewport(), c.variant())) {
            String hover = LegacyGlossaryCapture.hoverPart(c.variant());
            if (!hover.isEmpty()) {
                stage.hover(hover);
            }
            stage.paint();
            Map<String, float[]> rects = stage.rects(new ArrayList<>(List.of("panel")) {{ addAll(PARTS); }});
            Map<String, float[]> parts = LegacyGlossaryCapture.parts(rects);
            return new MigrationGate.Capture(stage.raster.capture(), rects, stage.hits(parts), actions(stage, parts));
        } catch (Exception e) {
            throw new IllegalStateException("could not capture the glossary document for " + c.id(), e);
        }
    }

    /** What each click asked of the screen (its log) or the host (Back), in {@link LegacyGlossaryCapture#actions} terms. */
    private static Map<String, String> actions(DocumentStage stage, Map<String, float[]> parts) {
        LegacyGlossaryCapture.LoggingGlossary g = (LegacyGlossaryCapture.LoggingGlossary) stage.services.glossary;
        Map<String, String> out = new LinkedHashMap<>();
        parts.forEach((name, r) -> {
            int index = g.getSelectedEntityIndex();
            EntityType type = g.getSelectedEntityType();
            int variant = g.getSelectedVariantIndex(type, 1000);
            g.log.clear();
            List<String> fired = new ArrayList<>(stage.clickAt(r[0] + r[2] / 2f, r[1] + r[3] / 2f));
            fired.addAll(g.log);
            out.put(name, fired.size() == 1 ? fired.getFirst() : "fired " + fired);
            g.select(index); // back to the case's state for the next part
            while (g.getSelectedVariantIndex(type, 1000) != variant) {
                g.cycleVariant(1);
            }
            g.log.clear();
            stage.settle();
        });
        return out;
    }
}
