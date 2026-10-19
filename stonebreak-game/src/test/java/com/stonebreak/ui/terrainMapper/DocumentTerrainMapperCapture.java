package com.stonebreak.ui.terrainMapper;

import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.ui.UiWallClock;
import com.stonebreak.ui.fidelity.DocumentStage;
import com.stonebreak.ui.fidelity.RecordingServices;
import com.stonebreak.ui.runtime.GameUiDocuments;
import com.stonebreak.ui.runtime.providers.TerrainMapProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The candidate side of the terrain mapper's gate (#301): {@code ui/documents/terrain_mapper.sbui} opened as
 * the game opens it, bound to a real {@link TerrainMapperScreen} in the fixture's state (its actions run the
 * screen's mouse rules), the map drawn by {@link TerrainMapProvider} over that screen. Not a test class.
 */
public final class DocumentTerrainMapperCapture implements MigrationGate.Renderer {

    private final SbuiArchive shipped;
    private final boolean oracleRects;

    public DocumentTerrainMapperCapture(SbuiArchive shipped) {
        this(shipped, false);
    }

    private DocumentTerrainMapperCapture(SbuiArchive shipped, boolean oracleRects) {
        this.shipped = shipped;
        this.oracleRects = oracleRects;
    }

    /**
     * Re-freezing the reference after an intended visual change (the Daedalus generator choice): the document's
     * pixels with {@link TerrainMapperFixtures#rects}' legacy-formula geometry, independent of the document.
     */
    public static DocumentTerrainMapperCapture reference() throws java.io.IOException {
        return new DocumentTerrainMapperCapture(GameUiDocuments.readScreen(TerrainMapperScreen.DOCUMENT_ID), true);
    }

    public static DocumentTerrainMapperCapture shipped() throws java.io.IOException {
        return new DocumentTerrainMapperCapture(GameUiDocuments.readScreen(TerrainMapperScreen.DOCUMENT_ID));
    }

    /** The document on a stage serving {@code screen} in a {@code w}x{@code h} window. */
    public static DocumentStage stage(SbuiArchive sbui, TerrainMapperScreen screen, int w, int h, float uiScale,
                                      double clock) throws Exception {
        return stage(sbui, screen, w, h, uiScale, clock, () -> null);
    }

    /** As above, the map's status text in {@code typeface}. */
    public static DocumentStage stage(SbuiArchive sbui, TerrainMapperScreen screen, int w, int h, float uiScale,
                                      double clock, java.util.function.Supplier<io.github.humbleui.skija.Typeface> typeface)
            throws Exception {
        RecordingServices services = new RecordingServices() {
            @Override
            public TerrainMapperScreen terrainMapper() {
                return screen;
            }

            @Override
            public int[] screenSize() {
                return new int[]{w, h};
            }
        };
        DocumentStage stage = new DocumentStage(sbui, w, h, uiScale, services, MultiplayerSession.Mode.SINGLEPLAYER,
            Map.of(TerrainMapProvider.ID, new TerrainMapProvider(
                () -> TerrainMapperFixtures.sampledMap(screen.getStateManager()), typeface)));
        UiWallClock.pin(clock, UiWallClock.DEFAULT_SEED); // the stage's raster pinned its own reading
        stage.host.terrainMapper().poll();
        stage.settle();
        return stage;
    }

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        FidelityCase.Viewport vp = c.viewport();
        int w = vp.width();
        int h = vp.height();
        TerrainMapperScreen screen = new TerrainMapperScreen();
        TerrainMapperFixtures.prepare(screen.getStateManager(), c.variant());
        try (com.stonebreak.ui.fidelity.UiRaster fonts = new com.stonebreak.ui.fidelity.UiRaster(w, h, vp.uiScale());
             DocumentStage stage = stage(shipped, screen, w, h, vp.uiScale(), TerrainMapperFixtures.clock(c.variant()),
                 fonts.backend()::getMinecraftTypeface)) {
            String hover = TerrainMapperFixtures.hover(c.variant());
            if (!hover.isEmpty()) {
                float[] p = TerrainMapperFixtures.pointer(TerrainMapperFixtures.rects(w, h, screen.getStateManager()),
                    hover);
                stage.view.pointerMove(p[0], p[1]);
                if ("map".equals(hover)) {
                    screen.getStateManager().setHoverValue(137, -61, 0.25f); // as a sampled map would report
                }
                stage.host.terrainMapper().poll();
                stage.settle();
            }
            stage.paint();
            return new MigrationGate.Capture(stage.raster.capture(), oracleRects
                ? TerrainMapperFixtures.rects(w, h, screen.getStateManager()) : rects(stage));
        } catch (Exception e) {
            throw new IllegalStateException("could not capture the terrain mapper document for " + c.id(), e);
        } finally {
            screen.dispose();
        }
    }

    /** The element of an oracle name. */
    public static UiElement element(DocumentStage stage, String name) {
        if (name.startsWith("mode")) {
            return stage.view.instance().qAll(".mode").get(Integer.parseInt(name.substring(4)));
        }
        if (name.startsWith("gen")) {
            return stage.view.instance().qAll(".gen").get(Integer.parseInt(name.substring(3)));
        }
        return switch (name) {
            case "name", "seed" -> stage.q("#" + name + "-field");
            case "spawn", "center" -> {
                UiElement on = stage.q("#" + name + "-on");
                yield on != null && !on.isCollapsed() ? on : stage.q("#" + name + "-off");
            }
            default -> stage.q("#" + name);
        };
    }

    static Map<String, float[]> rects(DocumentStage stage) {
        Map<String, float[]> out = new LinkedHashMap<>();
        for (String n : List.of("sidebar", "map", "footer", "name", "seed")) {
            put(out, n, element(stage, n));
        }
        List<UiElement> gens = stage.view.instance().qAll(".gen");
        for (int i = 0; i < gens.size(); i++) {
            put(out, "gen" + i, gens.get(i));
        }
        List<UiElement> modes = stage.view.instance().qAll(".mode");
        for (int i = 0; i < modes.size(); i++) {
            put(out, "mode" + i, modes.get(i));
        }
        for (String n : List.of("spawn", "center", "back", "character", "create", "simulate")) {
            put(out, n, element(stage, n));
        }
        return out;
    }

    private static void put(Map<String, float[]> out, String name, UiElement el) {
        if (el != null && !el.isCollapsed()) {
            UiRect r = el.rect();
            out.put(name, new float[]{r.x(), r.y(), r.width(), r.height()});
        }
    }
}
