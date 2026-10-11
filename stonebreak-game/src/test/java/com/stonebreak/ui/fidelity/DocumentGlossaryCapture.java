package com.stonebreak.ui.fidelity;

import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.player.EntityDiscoveries;
import com.stonebreak.player.PlayerStats;
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
 * {@link GlossaryScreen} on the variant's selection, reading pinned discoveries and kills
 * ({@link #discoveries()}, {@link #stats()}). A click's effect is read back from that screen
 * (selection, shown variant) or the recorded Back.
 *
 * <p>Variants {@code <entity>[-variant2][-hover-<part>]}: {@code cow} (two of three variants seen,
 * weakness known, 1,234 defeated), {@code sheep} (seen, never defeated), {@code chicken} (complete),
 * {@code goose} (unseen); {@code -variant2} steps the cow's cycler once; parts are {@code back},
 * {@code row0..row3}, {@code left}, {@code right}. The 3D model is GL and not drawn here. Not a test class.
 */
public final class DocumentGlossaryCapture implements MigrationGate.Renderer {

    static final List<String> PARTS = List.of("row0", "row1", "row2", "row3", "left", "right", "back");

    public static final String[] ENTITIES = {"cow", "sheep", "chicken", "goose"};

    public static EntityDiscoveries discoveries() {
        EntityDiscoveries d = new EntityDiscoveries();
        d.recordVariantSeen(EntityType.COW, "Default");
        d.recordVariantSeen(EntityType.COW, "Angus");
        d.recordVariantSeen(EntityType.SHEEP, "Default");
        d.recordVariantSeen(EntityType.CHICKEN, "Default");
        d.recordWeaknessDiscovered(EntityType.COW);
        return d;
    }

    public static PlayerStats stats() {
        PlayerStats s = new PlayerStats();
        s.restoreKillsByType(Map.of(EntityType.COW, 1234L, EntityType.CHICKEN, 3L));
        return s;
    }

    /** A glossary that also logs what was asked of it (the gate reads the effect of clicks). */
    static final class LoggingGlossary extends GlossaryScreen {
        final List<String> log = new ArrayList<>();

        @Override
        public boolean select(int index) {
            log.add("select " + index);
            return super.select(index);
        }

        @Override
        public boolean cycleVariant(int delta) {
            log.add(delta > 0 ? "cycle +1" : "cycle -1");
            return super.cycleVariant(delta);
        }
    }

    /** A glossary on the variant's selection, reading the pinned data. */
    static LoggingGlossary screen(String variant) {
        LoggingGlossary g = new LoggingGlossary();
        EntityDiscoveries d = discoveries();
        PlayerStats s = stats();
        g.setDataSource(() -> d, () -> s);
        g.setVisible(true);
        String entity = variant.split("-")[0];
        int index = java.util.Arrays.asList(ENTITIES).indexOf(entity);
        if (index < 0) {
            throw new IllegalArgumentException("glossary variant starts with an entity: " + variant);
        }
        g.select(index);
        if (variant.contains("-variant2")) {
            g.cycleVariant(1);
        }
        g.log.clear();
        return g;
    }

    static String hoverPart(String variant) {
        int i = variant.indexOf("-hover-");
        return i < 0 ? "" : variant.substring(i + "-hover-".length());
    }

    /** The clickable parts that are displayed. */
    static Map<String, float[]> parts(Map<String, float[]> rects) {
        Map<String, float[]> out = new LinkedHashMap<>();
        for (String p : PARTS) {
            if (rects.containsKey(p)) {
                out.put(p, rects.get(p));
            }
        }
        return out;
    }

    private final SbuiArchive shipped;

    public DocumentGlossaryCapture(SbuiArchive shipped) {
        this.shipped = shipped;
    }

    public static DocumentGlossaryCapture shipped() throws java.io.IOException {
        return new DocumentGlossaryCapture(GameUiDocuments.readScreen(GlossaryScreen.DOCUMENT_ID));
    }

    static DocumentStage stage(SbuiArchive sbui, FidelityCase.Viewport vp, String variant) throws Exception {
        RecordingServices services = new RecordingServices();
        services.glossary = screen(variant);
        return new DocumentStage(sbui, vp.width(), vp.height(), vp.uiScale(), services,
            MultiplayerSession.Mode.SINGLEPLAYER, Map.of(EntityPreviewProvider.ID, DocumentStage.NO_GL));
    }

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        try (DocumentStage stage = stage(shipped, c.viewport(), c.variant())) {
            String hover = hoverPart(c.variant());
            if (!hover.isEmpty()) {
                stage.hover(hover);
            }
            stage.paint();
            Map<String, float[]> rects = stage.rects(new ArrayList<>(List.of("panel")) {{ addAll(PARTS); }});
            Map<String, float[]> parts = parts(rects);
            return new MigrationGate.Capture(stage.raster.capture(), rects, stage.hits(parts), actions(stage, parts));
        } catch (Exception e) {
            throw new IllegalStateException("could not capture the glossary document for " + c.id(), e);
        }
    }

    /**
     * What each click asked of the screen (its log: {@code select <i>}, {@code cycle -1|+1}) or the
     * host (Back).
     */
    private static Map<String, String> actions(DocumentStage stage, Map<String, float[]> parts) {
        LoggingGlossary g = (LoggingGlossary) stage.services.glossary;
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
