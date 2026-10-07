package com.stonebreak.ui.fidelity;

import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.ui.runtime.GameUiDocuments;
import com.stonebreak.ui.runtime.providers.DirtBackdropProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The candidate side of the world select gate (#299): the shipped {@code world_select} document on a
 * {@link DocumentStage} bound to a {@link WorldSelectFixtures} screen in the case's state; hovers go
 * through the document's own pointer handling (so the host hears where the pointer rests). Not a
 * test class.
 */
public final class DocumentWorldSelectCapture implements MigrationGate.Renderer {

    static DocumentStage stage(FidelityCase c) throws Exception {
        RecordingServices services = new RecordingServices();
        services.window = new int[]{c.viewport().width(), c.viewport().height()};
        LegacyUiRaster probe = new LegacyUiRaster(c.viewport().width(), c.viewport().height(), c.viewport().uiScale());
        try {
            services.worldSelect = WorldSelectFixtures.screen(probe.backend(), c.variant());
        } finally {
            probe.close();
        }
        SbuiArchive sbui = GameUiDocuments.readScreen("world_select");
        return new DocumentStage(sbui, c.viewport().width(), c.viewport().height(), c.viewport().uiScale(), services,
            MultiplayerSession.Mode.MENU, Map.of(DirtBackdropProvider.ID, new DirtBackdropProvider()));
    }

    /** The parts the legacy capture reports, in its order (card parts first; a dialog case only the dialog). */
    static List<String> parts(DocumentStage stage, String variant) {
        if (variant.startsWith("delete")) {
            return LegacyWorldSelectCapture.DIALOG_PARTS;
        }
        List<String> out = new ArrayList<>(List.of("card", "folder", "backup"));
        for (int i = 0; i < 8; i++) {
            out.add("row" + i);
        }
        out.addAll(List.of("back", "create", "delete", "play"));
        return out;
    }

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        try (DocumentStage stage = stage(c)) {
            String hover = WorldSelectFixtures.hover(c.variant());
            if (!hover.isEmpty()) {
                stage.hover(hover);
            }
            stage.paint();
            var image = stage.raster.capture();
            // The probes below move the pointer from part to part; the legacy clicks are resolved
            // against the drawn state, so the screen stops following the pointer from here.
            stage.services.worldSelectLive.clear();
            Map<String, float[]> rects = stage.rects(parts(stage, c.variant()));
            Map<String, float[]> pressed = new LinkedHashMap<>(rects);
            LegacyWorldSelectCapture.PANELS.forEach(pressed::remove);
            return new MigrationGate.Capture(image, rects, stage.hits(pressed), stage.actions(pressed));
        } catch (Exception e) {
            throw new IllegalStateException("could not capture " + c.id(), e);
        }
    }
}
