package com.stonebreak.ui.fidelity;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.ui.MainMenu;
import com.stonebreak.ui.runtime.GameUiDocuments;
import com.stonebreak.ui.runtime.providers.MenuStageProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The candidate side of the main menu's fidelity gate (#299): the shipped {@code main_menu} document on
 * a {@link DocumentStage}, bound to a {@link MainMenu} driven exactly as {@link LegacyMainMenuCapture}
 * drives the legacy one, its scene drawn by a {@link MenuStageProvider} over that menu's stage with the
 * same pinned space clock. Not a test class.
 */
public final class DocumentMainMenuCapture implements MigrationGate.Renderer {

    static DocumentStage stage(FidelityCase c, MainMenu[] menuOut) throws Exception {
        int w = c.viewport().width();
        int h = c.viewport().height();
        float s = c.viewport().uiScale();
        RecordingServices services = new RecordingServices();
        services.window = new int[]{w, h};
        LegacyUiRaster probe = new LegacyUiRaster(w, h, s);
        try {
            services.mainMenu = LegacyMainMenuCapture.menu(probe.backend(), c.variant(), w, h, s);
        } finally {
            probe.close();
        }
        menuOut[0] = services.mainMenu;
        MainMenu menu = services.mainMenu;
        return new DocumentStage(GameUiDocuments.readScreen(MainMenu.DOCUMENT_ID), w, h, s, services,
            MultiplayerSession.Mode.MENU, Map.of(MenuStageProvider.ID,
                new MenuStageProvider(menu::getStage, () -> LegacyMainMenuCapture.SPACE_TIME)));
    }

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        MainMenu[] menu = new MainMenu[1];
        try (DocumentStage stage = stage(c, menu)) {
            String hover = LegacyMainMenuCapture.hover(c.variant());
            if (!hover.isEmpty()) {
                stage.hover(hover);
            }
            stage.paint();
            List<String> parts = new ArrayList<>(List.of("logo"));
            parts.addAll(LegacyMainMenuCapture.BUTTONS);
            Map<String, float[]> rects = stage.layoutRects(parts);
            if (!LegacyMainMenuCapture.still(c.variant())) {
                return new MigrationGate.Capture(stage.raster.capture(), rects); // hits follow the motion
            }
            Map<String, float[]> clickable = new LinkedHashMap<>(rects);
            return new MigrationGate.Capture(stage.raster.capture(), rects, stage.hits(clickable),
                stage.actions(clickable));
        } catch (Exception e) {
            throw new IllegalStateException("could not capture " + c.id(), e);
        } finally {
            if (menu[0] != null) {
                menu[0].dispose();
            }
        }
    }
}
