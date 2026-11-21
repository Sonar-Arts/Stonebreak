package com.stonebreak.ui.fidelity;

import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.player.PlayerStats;
import com.stonebreak.ui.runtime.GameUiDocuments;
import com.stonebreak.ui.runtime.contracts.StatsRecord;
import com.stonebreak.ui.statisticsScreen.StatisticsScreen;

import java.util.List;
import java.util.Map;

/**
 * The candidate side of the statistics screen's fidelity gate (#299): the shipped
 * {@code ui/documents/statistics.sbui} on a {@link DocumentStage} whose host publishes the variant's
 * statistics ({@link #stats}). Variants: {@code played} ({@link #sample()}: grouping, kilometres,
 * hours), {@code fresh} (no player: every value zero), each optionally {@code -hover-back}. Not a
 * test class.
 */
public final class DocumentStatisticsCapture implements MigrationGate.Renderer {

    /** A played world: four-digit counts (grouping), kilometres and metres, hours of air time, minutes fishing. */
    public static PlayerStats sample() {
        PlayerStats s = new PlayerStats();
        s.restore(1234, 4567.25, 15432.5, 9876.4, 4321.0, 512.75, 3725.0);
        s.restoreKillsByType(Map.of(EntityType.COW, 12L, EntityType.SHEEP, 7L, EntityType.CHICKEN, 1203L,
            EntityType.GOBLIN, 12L));
        s.restoreActivity(845.0, 37, 2048);
        return s;
    }

    /** The stats a variant shows, or null for no player. */
    static PlayerStats stats(String variant) {
        return variant.startsWith("played") ? sample() : null;
    }

    private final SbuiArchive shipped;

    public DocumentStatisticsCapture(SbuiArchive shipped) {
        this.shipped = shipped;
    }

    public static DocumentStatisticsCapture shipped() throws java.io.IOException {
        return new DocumentStatisticsCapture(GameUiDocuments.readScreen(StatisticsScreen.DOCUMENT_ID));
    }

    static DocumentStage stage(SbuiArchive sbui, FidelityCase.Viewport vp, String variant) throws Exception {
        RecordingServices services = new RecordingServices();
        services.stats = StatsRecord.of(stats(variant));
        return new DocumentStage(sbui, vp.width(), vp.height(), vp.uiScale(), services,
            MultiplayerSession.Mode.SINGLEPLAYER);
    }

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        try (DocumentStage stage = stage(shipped, c.viewport(), c.variant())) {
            if (c.variant().endsWith("-hover-back")) {
                stage.hover("back");
            }
            stage.paint();
            Map<String, float[]> rects = stage.rects(List.of("panel", "back"));
            Map<String, float[]> buttons = Map.of("back", rects.get("back"));
            return new MigrationGate.Capture(stage.raster.capture(), rects, stage.hits(buttons), stage.actions(buttons));
        } catch (Exception e) {
            throw new IllegalStateException("could not capture the statistics document for " + c.id(), e);
        }
    }
}
